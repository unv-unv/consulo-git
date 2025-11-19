/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.rebase;

import consulo.application.AccessToken;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.component.ProcessCanceledException;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.collection.MultiMap;
import consulo.util.lang.ExceptionUtil;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ThreeState;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitUtil;
import git4idea.branch.GitRebaseParams;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandlerListener;
import git4idea.commands.GitUntrackedFilesOverwrittenByOperationDetector;
import git4idea.merge.GitConflictResolver;
import git4idea.rebase.GitSuccessfulRebase.SuccessType;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.stash.GitChangesSaver;
import git4idea.util.GitFreezingProcess;
import git4idea.util.GitUntrackedFilesHelper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.annotations.Contract;

import javax.swing.event.HyperlinkEvent;
import java.util.*;

import static consulo.util.collection.ContainerUtil.*;
import static consulo.util.lang.ObjectUtil.assertNotNull;
import static consulo.util.lang.ObjectUtil.notNull;
import static consulo.versionControlSystem.distributed.DvcsUtil.getShortRepositoryName;
import static git4idea.GitUtil.getRootsFromRepositories;
import static java.util.Collections.singleton;

public class GitRebaseProcess {
    private static final Logger LOG = Logger.getInstance(GitRebaseProcess.class);

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final Git myGit;
    @Nonnull
    private final ChangeListManager myChangeListManager;
    @Nonnull
    protected final NotificationService myNotificationService;
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;

    @Nonnull
    private final GitRebaseSpec myRebaseSpec;
    @Nullable
    private final GitRebaseResumeMode myCustomMode;
    @Nonnull
    private final GitChangesSaver mySaver;
    @Nonnull
    private final ProgressManager myProgressManager;

    public GitRebaseProcess(@Nonnull Project project, @Nonnull GitRebaseSpec rebaseSpec, @Nullable GitRebaseResumeMode customMode) {
        myProject = project;
        myRebaseSpec = rebaseSpec;
        myCustomMode = customMode;
        mySaver = rebaseSpec.getSaver();

        myGit = ServiceManager.getService(Git.class);
        myChangeListManager = ChangeListManager.getInstance(myProject);
        myNotificationService = NotificationService.getInstance();
        myRepositoryManager = GitUtil.getRepositoryManager(myProject);
        myProgressManager = ProgressManager.getInstance();
    }

    public void rebase() {
        new GitFreezingProcess(myProject, "rebase", this::doRebase).execute();
    }

    /**
     * Given a GitRebaseSpec this method either starts, or continues the ongoing rebase in multiple repositories.
     * <ul>
     * <li>It does nothing with "already successfully rebased repositories" (the ones which have {@link GitRebaseStatus} == SUCCESSFUL,
     * and just remembers them to use in the resulting notification.</li>
     * <li>If there is a repository with rebase in progress, it calls `git rebase --continue` (or `--skip`).
     * It is assumed that there is only one such repository.</li>
     * <li>For all remaining repositories rebase on which didn't start yet, it calls {@code git rebase <original parameters>}</li>
     * </ul>
     */
    @RequiredUIAccess
    private void doRebase() {
        LOG.info("Started rebase");
        LOG.debug("Started rebase with the following spec: " + myRebaseSpec);

        Map<GitRepository, GitRebaseStatus> statuses = new LinkedHashMap<>(myRebaseSpec.getStatuses());
        Collection<GitRepository> toRefresh = new LinkedHashSet<>();
        List<GitRepository> repositoriesToRebase = myRebaseSpec.getIncompleteRepositories();
        try (AccessToken ignored = DvcsUtil.workingTreeChangeStarted(myProject, "Rebase")) {
            if (!saveDirtyRootsInitially(repositoriesToRebase)) {
                return;
            }

            GitRepository failed = null;
            for (GitRepository repository : repositoriesToRebase) {
                GitRebaseResumeMode customMode = null;
                if (repository == myRebaseSpec.getOngoingRebase()) {
                    customMode = myCustomMode == null ? GitRebaseResumeMode.CONTINUE : myCustomMode;
                }

                GitRebaseStatus rebaseStatus = rebaseSingleRoot(repository, customMode, getSuccessfulRepositories(statuses));
                repository.update(); // make the repo state info actual ASAP
                statuses.put(repository, rebaseStatus);
                if (shouldBeRefreshed(rebaseStatus)) {
                    toRefresh.add(repository);
                }
                if (rebaseStatus.getType() != GitRebaseStatus.Type.SUCCESS) {
                    failed = repository;
                    break;
                }
            }

            if (failed == null) {
                LOG.debug("Rebase completed successfully.");
                mySaver.load();
            }
            refresh(toRefresh);
            if (failed == null) {
                notifySuccess(getSuccessfulRepositories(statuses), getSkippedCommits(statuses));
            }

            saveUpdatedSpec(statuses);
        }
        catch (ProcessCanceledException pce) {
            throw pce;
        }
        catch (Throwable e) {
            myRepositoryManager.setOngoingRebaseSpec(null);
            ExceptionUtil.rethrowUnchecked(e);
        }
    }

    private void saveUpdatedSpec(@Nonnull Map<GitRepository, GitRebaseStatus> statuses) {
        if (myRebaseSpec.shouldBeSaved()) {
            GitRebaseSpec newRebaseInfo = myRebaseSpec.cloneWithNewStatuses(statuses);
            myRepositoryManager.setOngoingRebaseSpec(newRebaseInfo);
        }
        else {
            myRepositoryManager.setOngoingRebaseSpec(null);
        }
    }

    @Nonnull
    @RequiredUIAccess
    private GitRebaseStatus rebaseSingleRoot(
        @Nonnull GitRepository repository,
        @Nullable GitRebaseResumeMode customMode,
        @Nonnull Map<GitRepository, GitSuccessfulRebase> alreadyRebased
    ) {
        VirtualFile root = repository.getRoot();
        String repoName = getShortRepositoryName(repository);
        LOG.info("Rebasing root " + repoName + ", mode: " + notNull(customMode, "standard"));

        Collection<GitRebaseUtils.CommitInfo> skippedCommits = new ArrayList<>();
        MultiMap<GitRepository, GitRebaseUtils.CommitInfo> allSkippedCommits = getSkippedCommits(alreadyRebased);
        boolean retryWhenDirty = false;

        while (true) {
            GitRebaseProblemDetector rebaseDetector = new GitRebaseProblemDetector();
            GitUntrackedFilesOverwrittenByOperationDetector untrackedDetector = new GitUntrackedFilesOverwrittenByOperationDetector(root);
            GitRebaseLineListener progressListener = new GitRebaseLineListener();
            GitCommandResult result = callRebase(repository, customMode, rebaseDetector, untrackedDetector, progressListener);

            boolean somethingRebased = customMode != null || progressListener.getResult().current > 1;

            if (result.success()) {
                if (rebaseDetector.hasStoppedForEditing()) {
                    showStoppedForEditingMessage(repository);
                    return new GitRebaseStatus(GitRebaseStatus.Type.SUSPENDED, skippedCommits);
                }
                LOG.debug("Successfully rebased " + repoName);
                return GitSuccessfulRebase.parseFromOutput(result.getOutput(), skippedCommits);
            }
            else if (result.cancelled()) {
                LOG.info("Rebase was cancelled");
                throw new ProcessCanceledException();
            }
            else if (rebaseDetector.isDirtyTree() && customMode == null && !retryWhenDirty) {
                // if the initial dirty tree check doesn't find all local changes, we are still ready to stash-on-demand,
                // but only once per repository (if the error happens again, that means that the previous stash attempt failed for some reason),
                // and not in the case of --continue (where all local changes are expected to be committed) or --skip.
                LOG.debug("Dirty tree detected in " + repoName);
                String saveError = saveLocalChanges(singleton(repository.getRoot()));
                if (saveError == null) {
                    retryWhenDirty = true; // try same repository again
                }
                else {
                    LOG.warn("Couldn't " + mySaver.getOperationName() + " root " + repository.getRoot() + ": " + saveError);
                    showFatalError(
                        LocalizeValue.localizeTODO(saveError),
                        repository,
                        somethingRebased,
                        alreadyRebased.keySet(),
                        allSkippedCommits
                    );
                    GitRebaseStatus.Type type = somethingRebased ? GitRebaseStatus.Type.SUSPENDED : GitRebaseStatus.Type.ERROR;
                    return new GitRebaseStatus(type, skippedCommits);
                }
            }
            else if (untrackedDetector.wasMessageDetected()) {
                LOG.info("Untracked files detected in " + repoName);
                showUntrackedFilesError(
                    untrackedDetector.getRelativeFilePaths(),
                    repository,
                    somethingRebased,
                    alreadyRebased.keySet(),
                    allSkippedCommits
                );
                GitRebaseStatus.Type type = somethingRebased ? GitRebaseStatus.Type.SUSPENDED : GitRebaseStatus.Type.ERROR;
                return new GitRebaseStatus(type, skippedCommits);
            }
            else if (rebaseDetector.isNoChangeError()) {
                LOG.info("'No changes' situation detected in " + repoName);
                GitRebaseUtils.CommitInfo currentRebaseCommit = GitRebaseUtils.getCurrentRebaseCommit(myProject, root);
                if (currentRebaseCommit != null) {
                    skippedCommits.add(currentRebaseCommit);
                }
                customMode = GitRebaseResumeMode.SKIP;
            }
            else if (rebaseDetector.isMergeConflict()) {
                LOG.info("Merge conflict in " + repoName);
                ResolveConflictResult resolveResult = showConflictResolver(repository, false);
                if (resolveResult == ResolveConflictResult.ALL_RESOLVED) {
                    customMode = GitRebaseResumeMode.CONTINUE;
                }
                else if (resolveResult == ResolveConflictResult.NOTHING_TO_MERGE) {
                    // the output is the same for the cases:
                    // (1) "unresolved conflicts"
                    // (2) "manual editing of a file not followed by `git add`
                    // => we check if there are any unresolved conflicts, and if not, then it is the case #2 which we are not handling
                    LOG.info("Unmerged changes while rebasing root " + repoName + ": " + result.getErrorOutputAsJoinedString());
                    showFatalError(
                        result.getErrorOutputAsHtmlValue(),
                        repository,
                        somethingRebased,
                        alreadyRebased.keySet(),
                        allSkippedCommits
                    );
                    GitRebaseStatus.Type type = somethingRebased ? GitRebaseStatus.Type.SUSPENDED : GitRebaseStatus.Type.ERROR;
                    return new GitRebaseStatus(type, skippedCommits);
                }
                else {
                    notifyNotAllConflictsResolved(repository, allSkippedCommits);
                    return new GitRebaseStatus(GitRebaseStatus.Type.SUSPENDED, skippedCommits);
                }
            }
            else {
                LOG.info("Error rebasing root " + repoName + ": " + result.getErrorOutputAsJoinedString());
                showFatalError(
                    result.getErrorOutputAsHtmlValue(),
                    repository,
                    somethingRebased,
                    alreadyRebased.keySet(),
                    allSkippedCommits
                );
                GitRebaseStatus.Type type = somethingRebased ? GitRebaseStatus.Type.SUSPENDED : GitRebaseStatus.Type.ERROR;
                return new GitRebaseStatus(type, skippedCommits);
            }
        }
    }

    @Nonnull
    private GitCommandResult callRebase(
        @Nonnull GitRepository repository,
        @Nullable GitRebaseResumeMode mode,
        @Nonnull GitLineHandlerListener... listeners
    ) {
        if (mode == null) {
            GitRebaseParams params = assertNotNull(myRebaseSpec.getParams());
            return myGit.rebase(repository, params, listeners);
        }
        else if (mode == GitRebaseResumeMode.SKIP) {
            return myGit.rebaseSkip(repository, listeners);
        }
        else {
            LOG.assertTrue(mode == GitRebaseResumeMode.CONTINUE, "Unexpected rebase mode: " + mode);
            return myGit.rebaseContinue(repository, listeners);
        }
    }

    @Nonnull
    protected Collection<GitRepository> getDirtyRoots(@Nonnull Collection<GitRepository> repositories) {
        return findRootsWithLocalChanges(repositories);
    }

    private static boolean shouldBeRefreshed(@Nonnull GitRebaseStatus rebaseStatus) {
        return rebaseStatus.getType() != GitRebaseStatus.Type.SUCCESS || ((GitSuccessfulRebase) rebaseStatus).getSuccessType() != SuccessType.UP_TO_DATE;
    }

    private static void refresh(@Nonnull Collection<GitRepository> repositories) {
        GitUtil.updateRepositories(repositories);
        // TODO use --diff-stat, and refresh only what's needed
        VirtualFileUtil.markDirtyAndRefresh(false, true, false, VirtualFileUtil.toVirtualFileArray(getRootsFromRepositories(repositories)));
    }

    private boolean saveDirtyRootsInitially(@Nonnull List<GitRepository> repositories) {
        Collection<GitRepository> repositoriesToSave = filter(
            repositories,
            // no need to save anything when --continue/--skip is to be called
            repository -> !repository.equals(myRebaseSpec.getOngoingRebase())
        );
        if (repositoriesToSave.isEmpty()) {
            return true;
        }
        Collection<VirtualFile> rootsToSave = getRootsFromRepositories(getDirtyRoots(repositoriesToSave));
        String error = saveLocalChanges(rootsToSave);
        if (error != null) {
            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Rebase not Started"))
                .content(LocalizeValue.localizeTODO(error))
                .notify(myProject);
            return false;
        }
        return true;
    }

    @Nullable
    private String saveLocalChanges(@Nonnull Collection<VirtualFile> rootsToSave) {
        try {
            mySaver.saveLocalChanges(rootsToSave);
            return null;
        }
        catch (VcsException e) {
            LOG.warn(e);
            return "Couldn't " + mySaver.getSaverName() + " local uncommitted changes:<br/>" + e.getMessage();
        }
    }

    private Collection<GitRepository> findRootsWithLocalChanges(@Nonnull Collection<GitRepository> repositories) {
        return filter(repositories, repository -> myChangeListManager.haveChangesUnder(repository.getRoot()) != ThreeState.NO);
    }

    private void notifySuccess(
        @Nonnull Map<GitRepository, GitSuccessfulRebase> successful,
        final MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits
    ) {
        String rebasedBranch = getCommonCurrentBranchNameIfAllTheSame(myRebaseSpec.getAllRepositories());
        List<SuccessType> successTypes = map(successful.values(), GitSuccessfulRebase::getSuccessType);
        SuccessType commonType = getItemIfAllTheSame(successTypes, SuccessType.REBASED);
        GitRebaseParams params = myRebaseSpec.getParams();
        String baseBranch = params == null ? null : notNull(params.getNewBase(), params.getUpstream());
        if ("HEAD".equals(baseBranch)) {
            baseBranch = getItemIfAllTheSame(myRebaseSpec.getInitialBranchNames().values(), baseBranch);
        }
        String message = commonType.formatMessage(rebasedBranch, baseBranch, params != null && params.getBranch() != null);
        message += mentionSkippedCommits(skippedCommits);
        myNotificationService.newInfo(VcsNotifier.STANDARD_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Rebase Successful"))
            .content(LocalizeValue.localizeTODO(message))
            .optionalHyperlinkListener(new NotificationListener.Adapter() {
                @Override
                protected void hyperlinkActivated(@Nonnull Notification notification, @Nonnull HyperlinkEvent e) {
                    handlePossibleCommitLinks(e.getDescription(), skippedCommits);
                }
            })
            .notify(myProject);
    }

    @Nullable
    private static String getCommonCurrentBranchNameIfAllTheSame(@Nonnull Collection<GitRepository> repositories) {
        return getItemIfAllTheSame(map(repositories, Repository::getCurrentBranchName), null);
    }

    @Contract("_, !null -> !null")
    private static <T> T getItemIfAllTheSame(@Nonnull Collection<T> collection, @Nullable T defaultItem) {
        return new HashSet<>(collection).size() == 1 ? getFirstItem(collection) : defaultItem;
    }

    private void notifyNotAllConflictsResolved(
        @Nonnull GitRepository conflictingRepository,
        MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits
    ) {
        myNotificationService.newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Rebase Suspended"))
            .content(LocalizeValue.localizeTODO(
                "You have to <a href='resolve'>resolve</a> the conflicts and <a href='continue'>continue</a> rebase.<br/>" +
                    "If you want to start from the beginning, you can <a href='abort'>abort</a> rebase." +
                    GitRebaseUtils.mentionLocalChangesRemainingInStash(mySaver)
            ))
            .optionalHyperlinkListener(new RebaseNotificationListener(conflictingRepository, skippedCommits))
            .notify(myProject);
    }

    @Nonnull
    @RequiredUIAccess
    private ResolveConflictResult showConflictResolver(@Nonnull GitRepository conflicting, boolean calledFromNotification) {
        GitConflictResolver.Params params = new GitConflictResolver.Params().setReverse(true);
        RebaseConflictResolver conflictResolver = new RebaseConflictResolver(myProject, myGit, conflicting, params, calledFromNotification);
        boolean allResolved = conflictResolver.merge();
        if (conflictResolver.myWasNothingToMerge) {
            return ResolveConflictResult.NOTHING_TO_MERGE;
        }
        if (allResolved) {
            return ResolveConflictResult.ALL_RESOLVED;
        }
        return ResolveConflictResult.UNRESOLVED_REMAIN;
    }

    private void showStoppedForEditingMessage(@Nonnull GitRepository repository) {
        myNotificationService.newInfo(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Rebase Stopped for Editing"))
            .content(LocalizeValue.localizeTODO("Once you are satisfied with your changes you may <a href='continue'>continue</a>"))
            .optionalHyperlinkListener(new RebaseNotificationListener(
                repository,
                MultiMap.<GitRepository, GitRebaseUtils.CommitInfo>empty()
            ))
            .notify(myProject);
    }

    private void showFatalError(
        @Nonnull LocalizeValue error,
        @Nonnull GitRepository currentRepository,
        boolean somethingWasRebased,
        @Nonnull Collection<GitRepository> successful,
        @Nonnull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits
    ) {
        String repo = myRepositoryManager.moreThanOneRoot() ? getShortRepositoryName(currentRepository) + ": " : "";
        myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO(myRebaseSpec.getOngoingRebase() == null ? "Rebase Failed" : "Continue Rebase Failed"))
            .content(LocalizeValue.localizeTODO(
                repo + error + "<br/>" +
                    mentionRetryAndAbort(somethingWasRebased, successful) +
                    mentionSkippedCommits(skippedCommits) +
                    GitRebaseUtils.mentionLocalChangesRemainingInStash(mySaver)
            ))
            .optionalHyperlinkListener(new RebaseNotificationListener(currentRepository, skippedCommits))
            .notify(myProject);
    }

    private void showUntrackedFilesError(
        @Nonnull Set<String> untrackedPaths,
        @Nonnull GitRepository currentRepository,
        boolean somethingWasRebased,
        @Nonnull Collection<GitRepository> successful,
        MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits
    ) {
        String message = GitUntrackedFilesHelper.createUntrackedFilesOverwrittenDescription("rebase", true) +
            mentionRetryAndAbort(somethingWasRebased, successful) +
            mentionSkippedCommits(skippedCommits) +
            GitRebaseUtils.mentionLocalChangesRemainingInStash(mySaver);
        GitUntrackedFilesHelper.notifyUntrackedFilesOverwrittenBy(
            myProject,
            currentRepository.getRoot(),
            untrackedPaths,
            "rebase",
            message
        );
    }

    @Nonnull
    private static String mentionRetryAndAbort(boolean somethingWasRebased, @Nonnull Collection<GitRepository> successful) {
        return somethingWasRebased || !successful.isEmpty() ? "You can <a href='retry'>retry</a> or <a href='abort'>abort</a> rebase." : "<a href='retry'>Retry.</a>";
    }

    @Nonnull
    private static String mentionSkippedCommits(@Nonnull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
        if (skippedCommits.isEmpty()) {
            return "";
        }
        String message = "<br/>";
        if (skippedCommits.values().size() == 1) {
            message += "The following commit was skipped during rebase:<br/>";
        }
        else {
            message += "The following commits were skipped during rebase:<br/>";
        }
        message += StringUtil.join(skippedCommits.values(), commitInfo ->
        {
            String commitMessage = StringUtil.shortenPathWithEllipsis(commitInfo.subject, 72, true);
            String hash = commitInfo.revision.asString();
            String shortHash = DvcsUtil.getShortHash(commitInfo.revision.asString());
            return String.format("<a href='%s'>%s</a> %s", hash, shortHash, commitMessage);
        }, "<br/>");
        return message;
    }

    @Nonnull
    private static MultiMap<GitRepository, GitRebaseUtils.CommitInfo> getSkippedCommits(@Nonnull Map<GitRepository, ? extends GitRebaseStatus> statuses) {
        MultiMap<GitRepository, GitRebaseUtils.CommitInfo> map = MultiMap.create();
        for (GitRepository repository : statuses.keySet()) {
            map.put(repository, statuses.get(repository).getSkippedCommits());
        }
        return map;
    }

    @Nonnull
    private static Map<GitRepository, GitSuccessfulRebase> getSuccessfulRepositories(@Nonnull Map<GitRepository, GitRebaseStatus> statuses) {
        Map<GitRepository, GitSuccessfulRebase> map = new LinkedHashMap<>();
        for (GitRepository repository : statuses.keySet()) {
            GitRebaseStatus status = statuses.get(repository);
            if (status instanceof GitSuccessfulRebase successfulRebase) {
                map.put(repository, successfulRebase);
            }
        }
        return map;
    }

    private class RebaseConflictResolver extends GitConflictResolver {
        private final boolean myCalledFromNotification;
        private boolean myWasNothingToMerge;

        RebaseConflictResolver(
            @Nonnull Project project,
            @Nonnull Git git,
            @Nonnull GitRepository repository,
            @Nonnull Params params,
            boolean calledFromNotification
        ) {
            super(project, git, singleton(repository.getRoot()), params);
            myCalledFromNotification = calledFromNotification;
        }

        @Override
        protected void notifyUnresolvedRemain() {
            // will be handled in the common notification
        }

        @Override
        protected boolean proceedAfterAllMerged() throws VcsException {
            if (myCalledFromNotification) {
                retry(GitLocalize.actionRebaseContinueProgressTitle());
            }
            return true;
        }

        @Override
        protected boolean proceedIfNothingToMerge() throws VcsException {
            myWasNothingToMerge = true;
            return true;
        }
    }

    private enum ResolveConflictResult {
        ALL_RESOLVED,
        NOTHING_TO_MERGE,
        UNRESOLVED_REMAIN
    }

    private class RebaseNotificationListener extends NotificationListener.Adapter {
        @Nonnull
        private final GitRepository myCurrentRepository;
        @Nonnull
        private final MultiMap<GitRepository, GitRebaseUtils.CommitInfo> mySkippedCommits;

        RebaseNotificationListener(
            @Nonnull GitRepository currentRepository,
            @Nonnull MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits
        ) {
            myCurrentRepository = currentRepository;
            mySkippedCommits = skippedCommits;
        }

        @Override
        @RequiredUIAccess
        protected void hyperlinkActivated(@Nonnull Notification notification, @Nonnull HyperlinkEvent e) {
            String href = e.getDescription();
            if ("abort".equals(href)) {
                abort();
            }
            else if ("continue".equals(href)) {
                retry(GitLocalize.actionRebaseContinueProgressTitle());
            }
            else if ("retry".equals(href)) {
                retry(LocalizeValue.localizeTODO("Retry Rebase Process..."));
            }
            else if ("resolve".equals(href)) {
                showConflictResolver(myCurrentRepository, true);
            }
            else if ("stash".equals(href)) {
                mySaver.showSavedChanges();
            }
            else {
                handlePossibleCommitLinks(href, mySkippedCommits);
            }
        }
    }

    private void abort() {
        myProgressManager.run(new Task.Backgroundable(myProject, "Aborting Rebase Process...") {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                GitRebaseUtils.abort((Project) myProject, indicator);
            }
        });
    }

    private void retry(@Nonnull final LocalizeValue processTitle) {
        myProgressManager.run(new Task.Backgroundable(myProject, processTitle, true) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                GitRebaseUtils.continueRebase((Project) myProject);
            }
        });
    }

    private void handlePossibleCommitLinks(@Nonnull String href, MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits) {
        GitRepository repository = findRootBySkippedCommit(href, skippedCommits);
        if (repository != null) {
            GitUtil.showSubmittedFiles(myProject, href, repository.getRoot(), true, false);
        }
    }

    @Nullable
    private static GitRepository findRootBySkippedCommit(
        @Nonnull String hash,
        MultiMap<GitRepository, GitRebaseUtils.CommitInfo> skippedCommits
    ) {
        return find(
            skippedCommits.keySet(),
            repository -> exists(skippedCommits.get(repository), info -> info.revision.asString().equals(hash))
        );
    }
}
