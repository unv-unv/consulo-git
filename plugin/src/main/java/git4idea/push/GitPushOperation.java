/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.push;

import consulo.application.Application;
import consulo.application.progress.EmptyProgressIndicator;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.localHistory.Label;
import consulo.localHistory.LocalHistory;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.ref.SimpleReference;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.push.PushSpec;
import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.versionControlSystem.update.UpdatedFiles;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.GitRevisionNumber;
import git4idea.branch.GitBranchUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandlerListener;
import git4idea.commands.GitStandardProgressAnalyzer;
import git4idea.config.GitVcsSettings;
import git4idea.config.UpdateMethod;
import git4idea.history.GitHistoryUtils;
import git4idea.merge.MergeChangeCollector;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.update.GitRebaseOverMergeProblem;
import git4idea.update.GitUpdateProcess;
import git4idea.update.GitUpdateResult;
import git4idea.update.GitUpdater;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;
import java.util.stream.Collectors;

import static consulo.util.collection.ContainerUtil.filter;
import static git4idea.push.GitPushNativeResult.Type.FORCED_UPDATE;
import static git4idea.push.GitPushNativeResult.Type.NEW_REF;
import static git4idea.push.GitPushRepoResult.Type.NOT_PUSHED;
import static git4idea.push.GitPushRepoResult.Type.REJECTED_NO_FF;

/**
 * Executes git push operation:
 * <ul>
 * <li>Calls push for the given repositories with given parameters;</li>
 * <li>Collects results;</li>
 * <li>If push is rejected, proposes to update via merge or rebase;</li>
 * <li>Shows a notification about push result</li>
 * </ul>
 */
public class GitPushOperation {
    private static final Logger LOG = Logger.getInstance(GitPushOperation.class);
    private static final int MAX_PUSH_ATTEMPTS = 10;

    private final Project myProject;
    @Nonnull
    private final GitPushSupport myPushSupport;
    private final Map<GitRepository, PushSpec<GitPushSource, GitPushTarget>> myPushSpecs;
    @Nullable
    private final GitPushTagMode myTagMode;
    private final boolean myForce;
    private final boolean mySkipHook;
    private final Git myGit;
    private final ProgressIndicator myProgressIndicator;
    private final GitVcsSettings mySettings;
    private final GitRepositoryManager myRepositoryManager;

    public GitPushOperation(
        @Nonnull Project project,
        @Nonnull GitPushSupport pushSupport,
        @Nonnull Map<GitRepository, PushSpec<GitPushSource, GitPushTarget>> pushSpecs,
        @Nullable GitPushTagMode tagMode,
        boolean force,
        boolean skipHook
    ) {
        myProject = project;
        myPushSupport = pushSupport;
        myPushSpecs = pushSpecs;
        myTagMode = tagMode;
        myForce = force;
        mySkipHook = skipHook;
        myGit = Git.getInstance();
        myProgressIndicator = ObjectUtil.notNull(ProgressManager.getInstance().getProgressIndicator(), new EmptyProgressIndicator());
        mySettings = GitVcsSettings.getInstance(myProject);
        myRepositoryManager = GitRepositoryManager.getInstance(myProject);

        Map<GitRepository, GitRevisionNumber> currentHeads = new HashMap<>();
        for (GitRepository repository : pushSpecs.keySet()) {
            repository.update();
            String head = repository.getCurrentRevision();
            if (head == null) {
                LOG.error("This repository has no commits");
            }
            else {
                currentHeads.put(repository, new GitRevisionNumber(head));
            }
        }
    }

    @Nonnull
    @RequiredUIAccess
    public GitPushResult execute() {
        PushUpdateSettings updateSettings = readPushUpdateSettings();
        Label beforePushLabel = null;
        Label afterPushLabel = null;
        Map<GitRepository, String> preUpdatePositions = updateRootInfoAndRememberPositions();
        Boolean rebaseOverMergeProblemDetected = null;

        Map<GitRepository, GitPushRepoResult> results = new HashMap<>();
        Map<GitRepository, GitUpdateResult> updatedRoots = new HashMap<>();

        try {
            Collection<GitRepository> remainingRoots = myPushSpecs.keySet();
            for (int pushAttempt = 0; pushAttempt < MAX_PUSH_ATTEMPTS && !remainingRoots.isEmpty();
                 pushAttempt++, remainingRoots = getRejectedAndNotPushed(results)) {
                Map<GitRepository, GitPushRepoResult> resultMap = push(myRepositoryManager.sortByDependency(remainingRoots));
                results.putAll(resultMap);

                GroupedPushResult result = GroupedPushResult.group(resultMap);

                // stop if error happens, or if push is rejected for a custom reason (not because a pull is needed)
                if (!result.errors.isEmpty() || !result.customRejected.isEmpty()) {
                    break;
                }

                // propose to update if rejected
                if (!result.rejected.isEmpty()) {
                    boolean shouldUpdate = true;
                    if (myForce || pushingToNotTrackedBranch(result.rejected)) {
                        shouldUpdate = false;
                    }
                    else if (pushAttempt == 0 && !mySettings.autoUpdateIfPushRejected()) {
                        // the dialog will be shown => check for rebase-over-merge problem in advance to avoid showing several dialogs in a row
                        rebaseOverMergeProblemDetected =
                            !findRootsWithMergeCommits(getRootsToUpdate(updateSettings, result.rejected.keySet())).isEmpty();

                        updateSettings = showDialogAndGetExitCode(result.rejected.keySet(), updateSettings, rebaseOverMergeProblemDetected);
                        if (updateSettings != null) {
                            savePushUpdateSettings(updateSettings, rebaseOverMergeProblemDetected);
                        }
                        else {
                            shouldUpdate = false;
                        }
                    }

                    if (!shouldUpdate) {
                        break;
                    }

                    if (beforePushLabel == null) { // put the label only before the very first update
                        beforePushLabel = LocalHistory.getInstance().putSystemLabel(myProject, "Before push");
                    }
                    Collection<GitRepository> rootsToUpdate = getRootsToUpdate(updateSettings, result.rejected.keySet());
                    GitUpdateResult updateResult =
                        update(rootsToUpdate, updateSettings.updateMethod(), rebaseOverMergeProblemDetected == null);
                    for (GitRepository repository : rootsToUpdate) {
                        updatedRoots.put(repository, updateResult); // TODO update result in GitUpdateProcess is a single for several roots
                    }
                    if (!updateResult.isSuccess() || updateResult == GitUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS || updateResult == GitUpdateResult.INCOMPLETE) {
                        break;
                    }
                }
            }
        }
        finally {
            if (beforePushLabel != null) {
                afterPushLabel = LocalHistory.getInstance().putSystemLabel(myProject, "After push");
            }
            for (GitRepository repository : myPushSpecs.keySet()) {
                repository.update();
            }
        }
        return prepareCombinedResult(results, updatedRoots, preUpdatePositions, beforePushLabel, afterPushLabel);
    }

    @Nonnull
    private Collection<GitRepository> getRootsToUpdate(
        @Nonnull PushUpdateSettings updateSettings,
        @Nonnull Set<GitRepository> rejectedRepositories
    ) {
        return updateSettings.shouldUpdateAllRoots() ? myRepositoryManager.getRepositories() : rejectedRepositories;
    }

    @Nonnull
    private Collection<VirtualFile> findRootsWithMergeCommits(@Nonnull Collection<GitRepository> rootsToSearch) {
        return ContainerUtil.mapNotNull(rootsToSearch, repo ->
        {
            PushSpec<GitPushSource, GitPushTarget> pushSpec = myPushSpecs.get(repo);
            if (pushSpec == null) { // repository is not selected to be pushed, but can be rebased
                GitPushSource source = myPushSupport.getSource(repo);
                GitPushTarget target = myPushSupport.getDefaultTarget(repo);
                if (target == null) {
                    return null;
                }
                pushSpec = new PushSpec<>(source, target);
            }
            String baseRef = pushSpec.getTarget().remoteBranch().getFullName();
            String currentRef = pushSpec.getSource().getBranch().getFullName();
            return GitRebaseOverMergeProblem.hasProblem(myProject, repo.getRoot(), baseRef, currentRef) ? repo.getRoot() : null;
        });
    }

    private static boolean pushingToNotTrackedBranch(@Nonnull Map<GitRepository, GitPushRepoResult> rejected) {
        return ContainerUtil.exists(rejected.entrySet(), entry ->
        {
            GitRepository repository = entry.getKey();
            GitLocalBranch currentBranch = repository.getCurrentBranch();
            assert currentBranch != null;
            GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(repository, currentBranch);
            return trackInfo == null || !trackInfo.remoteBranch().getFullName().equals(entry.getValue().getTargetBranch());
        });
    }

    @Nonnull
    private static List<GitRepository> getRejectedAndNotPushed(@Nonnull Map<GitRepository, GitPushRepoResult> results) {
        return filter(
            results.keySet(),
            repository -> results.get(repository).getType() == REJECTED_NO_FF || results.get(repository).getType() == NOT_PUSHED
        );
    }

    @Nonnull
    private Map<GitRepository, String> updateRootInfoAndRememberPositions() {
        Set<GitRepository> repositories = myPushSpecs.keySet();
        repositories.forEach(GitRepository::update);

        return repositories.stream().collect(Collectors.toMap(it -> it, Repository::getCurrentRevision));
    }

    @Nonnull
    private GitPushResult prepareCombinedResult(
        @Nonnull Map<GitRepository, GitPushRepoResult> allRoots,
        @Nonnull Map<GitRepository, GitUpdateResult> updatedRoots,
        @Nonnull Map<GitRepository, String> preUpdatePositions,
        @Nullable Label beforeUpdateLabel,
        @Nullable Label afterUpdateLabel
    ) {
        Map<GitRepository, GitPushRepoResult> results = new HashMap<>();
        UpdatedFiles updatedFiles = UpdatedFiles.create();
        for (Map.Entry<GitRepository, GitPushRepoResult> entry : allRoots.entrySet()) {
            GitRepository repository = entry.getKey();
            GitPushRepoResult simpleResult = entry.getValue();
            GitUpdateResult updateResult = updatedRoots.get(repository);
            if (updateResult == null) {
                results.put(repository, simpleResult);
            }
            else {
                collectUpdatedFiles(updatedFiles, repository, preUpdatePositions.get(repository));
                results.put(repository, GitPushRepoResult.addUpdateResult(simpleResult, updateResult));
            }
        }
        return new GitPushResult(results, updatedFiles, beforeUpdateLabel, afterUpdateLabel);
    }

    @Nonnull
    private Map<GitRepository, GitPushRepoResult> push(@Nonnull List<GitRepository> repositories) {
        Map<GitRepository, GitPushRepoResult> results = new LinkedHashMap<>();
        for (GitRepository repository : repositories) {
            PushSpec<GitPushSource, GitPushTarget> spec = myPushSpecs.get(repository);
            ResultWithOutput resultWithOutput = doPush(repository, spec);
            LOG.debug("Pushed to " + DvcsUtil.getShortRepositoryName(repository) + ": " + resultWithOutput);

            GitLocalBranch source = spec.getSource().getBranch();
            GitPushTarget target = spec.getTarget();
            GitPushRepoResult repoResult;
            if (resultWithOutput.isError()) {
                repoResult = GitPushRepoResult.error(source, target.remoteBranch(), resultWithOutput.getErrorAsString());
            }
            else {
                List<GitPushNativeResult> nativeResults = resultWithOutput.parsedResults;
                GitPushNativeResult branchResult = getBranchResult(nativeResults);
                if (branchResult == null) {
                    LOG.error("No result for branch among: [" + nativeResults + "]\n" + "Full result: " + resultWithOutput);
                    continue;
                }
                List<GitPushNativeResult> tagResults = filter(
                    nativeResults,
                    result -> !result.equals(branchResult) && (result.getType() == NEW_REF || result.getType() == FORCED_UPDATE)
                );
                int commits = collectNumberOfPushedCommits(repository.getRoot(), branchResult);
                repoResult = GitPushRepoResult.convertFromNative(branchResult, tagResults, commits, source, target.remoteBranch());
            }

            LOG.debug("Converted result: " + repoResult);
            results.put(repository, repoResult);
        }

        // fill other not-processed repositories as not-pushed
        for (GitRepository repository : repositories) {
            if (!results.containsKey(repository)) {
                PushSpec<GitPushSource, GitPushTarget> spec = myPushSpecs.get(repository);
                results.put(repository, GitPushRepoResult.notPushed(spec.getSource().getBranch(), spec.getTarget().remoteBranch()));
            }
        }
        return results;
    }

    @Nullable
    private static GitPushNativeResult getBranchResult(@Nonnull List<GitPushNativeResult> results) {
        return ContainerUtil.find(results, result -> result.getSourceRef().startsWith("refs/heads/"));
    }

    private int collectNumberOfPushedCommits(@Nonnull VirtualFile root, @Nonnull GitPushNativeResult result) {
        if (result.getType() != GitPushNativeResult.Type.SUCCESS) {
            return -1;
        }
        String range = result.getRange();
        if (range == null) {
            LOG.error("Range of pushed commits not reported in " + result);
            return -1;
        }
        try {
            return GitHistoryUtils.history(myProject, root, range).size();
        }
        catch (VcsException e) {
            LOG.error("Couldn't collect commits from range " + range);
            return -1;
        }
    }

    private void collectUpdatedFiles(
        @Nonnull UpdatedFiles updatedFiles,
        @Nonnull GitRepository repository,
        @Nonnull String preUpdatePosition
    ) {
        MergeChangeCollector collector =
            new MergeChangeCollector(myProject, repository.getRoot(), new GitRevisionNumber(preUpdatePosition));
        List<VcsException> exceptions = new ArrayList<>();
        collector.collect(updatedFiles, exceptions);
        for (VcsException exception : exceptions) {
            LOG.info(exception);
        }
    }

    @Nonnull
    private ResultWithOutput doPush(@Nonnull GitRepository repository, @Nonnull PushSpec<GitPushSource, GitPushTarget> pushSpec) {
        GitPushTarget target = pushSpec.getTarget();
        GitLocalBranch sourceBranch = pushSpec.getSource().getBranch();
        GitRemoteBranch targetBranch = target.remoteBranch();

        GitLineHandlerListener progressListener = GitStandardProgressAnalyzer.createListener(myProgressIndicator);
        boolean setUpstream = pushSpec.getTarget().isNewBranchCreated() && !branchTrackingInfoIsSet(repository, sourceBranch);
        String tagMode = myTagMode == null ? null : myTagMode.getArgument();

        String spec = sourceBranch.getFullName() + ":" + targetBranch.getNameForRemoteOperations();
        GitCommandResult res =
            myGit.push(repository, targetBranch.getRemote(), spec, myForce, setUpstream, mySkipHook, tagMode, progressListener);
        return new ResultWithOutput(res);
    }

    private static boolean branchTrackingInfoIsSet(@Nonnull GitRepository repository, @Nonnull GitLocalBranch source) {
        return ContainerUtil.exists(repository.getBranchTrackInfos(), info -> info.localBranch().equals(source));
    }

    private void savePushUpdateSettings(@Nonnull PushUpdateSettings settings, boolean rebaseOverMergeDetected) {
        UpdateMethod updateMethod = settings.updateMethod();
        mySettings.setUpdateAllRootsIfPushRejected(settings.shouldUpdateAllRoots());
        if (!rebaseOverMergeDetected // don't overwrite explicit "rebase" with temporary "merge" caused by merge commits
            && mySettings.getUpdateType() != updateMethod
            // don't overwrite "branch default" setting
            && mySettings.getUpdateType() != UpdateMethod.BRANCH_DEFAULT) {

            mySettings.setUpdateType(updateMethod);
        }
    }

    @Nonnull
    private PushUpdateSettings readPushUpdateSettings() {
        boolean updateAllRoots = mySettings.shouldUpdateAllRootsIfPushRejected();
        UpdateMethod updateMethod = mySettings.getUpdateType();
        if (updateMethod == UpdateMethod.BRANCH_DEFAULT) {
            // deliberate limitation: we have only 2 buttons => choose method from the 1st repo if different
            updateMethod = GitUpdater.resolveUpdateMethod(myPushSpecs.keySet().iterator().next());
        }
        return new PushUpdateSettings(updateAllRoots, updateMethod);
    }

    @Nullable
    @RequiredUIAccess
    private PushUpdateSettings showDialogAndGetExitCode(
        @Nonnull Set<GitRepository> repositories,
        @Nonnull PushUpdateSettings initialSettings,
        boolean rebaseOverMergeProblemDetected
    ) {
        SimpleReference<PushUpdateSettings> updateSettings = SimpleReference.create();
        Application.get().invokeAndWait(() -> {
            GitRejectedPushUpdateDialog dialog = new GitRejectedPushUpdateDialog(
                myProject,
                repositories,
                initialSettings,
                rebaseOverMergeProblemDetected
            );
            DialogManager.show(dialog);
            int exitCode = dialog.getExitCode();
            if (exitCode != DialogWrapper.CANCEL_EXIT_CODE) {
                mySettings.setAutoUpdateIfPushRejected(dialog.shouldAutoUpdateInFuture());
                updateSettings.set(new PushUpdateSettings(
                    dialog.shouldUpdateAll(),
                    convertUpdateMethodFromDialogExitCode(
                        exitCode)
                ));
            }
        });
        return updateSettings.get();
    }

    @Nonnull
    private static UpdateMethod convertUpdateMethodFromDialogExitCode(int exitCode) {
        return switch (exitCode) {
            case GitRejectedPushUpdateDialog.MERGE_EXIT_CODE -> UpdateMethod.MERGE;
            case GitRejectedPushUpdateDialog.REBASE_EXIT_CODE -> UpdateMethod.REBASE;
            default -> throw new IllegalStateException("Unexpected exit code: " + exitCode);
        };
    }

    @Nonnull
    @RequiredUIAccess
    protected GitUpdateResult update(
        @Nonnull Collection<GitRepository> rootsToUpdate,
        @Nonnull UpdateMethod updateMethod,
        boolean checkForRebaseOverMergeProblem
    ) {
        GitUpdateResult updateResult = new GitUpdateProcess(
            myProject,
            myProgressIndicator,
            new HashSet<>(rootsToUpdate),
            UpdatedFiles.create(),
            checkForRebaseOverMergeProblem,
            false
        ).update(updateMethod);
        for (GitRepository repository : rootsToUpdate) {
            repository.getRoot().refresh(true, true);
            repository.update();
        }
        return updateResult;
    }

    private static class ResultWithOutput {
        @Nonnull
        private final List<GitPushNativeResult> parsedResults;
        @Nonnull
        private final GitCommandResult resultOutput;

        ResultWithOutput(@Nonnull GitCommandResult resultOutput) {
            this.resultOutput = resultOutput;
            this.parsedResults = GitPushNativeResultParser.parse(resultOutput.getOutput());
        }

        boolean isError() {
            return parsedResults.isEmpty();
        }

        @Nonnull
        String getErrorAsString() {
            return resultOutput.getErrorOutputAsJoinedString();
        }

        @Override
        public String toString() {
            return "Parsed results: " + parsedResults + "\nCommand output:" + resultOutput;
        }
    }
}
