/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.branch;

import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.document.FileDocumentManager;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.MultiMap;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitMessageWithFilesDetector;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

import static consulo.util.lang.ObjectUtil.chooseNotNull;
import static consulo.util.lang.StringUtil.pluralize;

/**
 * Common class for Git operations with branches aware of multi-root configuration,
 * which means showing combined error information, proposing to rollback, etc.
 */
abstract class GitBranchOperation {
    protected static final Logger LOG = Logger.getInstance(GitBranchOperation.class);

    @Nonnull
    protected final Project myProject;
    @Nonnull
    protected final NotificationService myNotificationService;
    @Nonnull
    protected final Git myGit;
    @Nonnull
    protected final GitBranchUiHandler myUiHandler;
    @Nonnull
    private final Collection<GitRepository> myRepositories;
    @Nonnull
    protected final Map<GitRepository, String> myCurrentHeads;
    @Nonnull
    protected final Map<GitRepository, String> myInitialRevisions;
    @Nonnull
    private final GitVcsSettings mySettings;

    @Nonnull
    private final Collection<GitRepository> mySuccessfulRepositories;
    @Nonnull
    private final Collection<GitRepository> mySkippedRepositories;
    @Nonnull
    private final Collection<GitRepository> myRemainingRepositories;

    protected GitBranchOperation(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull GitBranchUiHandler uiHandler,
        @Nonnull Collection<GitRepository> repositories
    ) {
        myProject = project;
        myNotificationService = NotificationService.getInstance();
        myGit = git;
        myUiHandler = uiHandler;
        myRepositories = repositories;
        myCurrentHeads = ContainerUtil.map2Map(
            repositories,
            it -> Pair.create(it, chooseNotNull(it.getCurrentBranchName(), it.getCurrentRevision()))
        );
        myInitialRevisions = ContainerUtil.map2Map(repositories, it -> Pair.create(it, it.getCurrentRevision()));
        mySuccessfulRepositories = new ArrayList<>();
        mySkippedRepositories = new ArrayList<>();
        myRemainingRepositories = new ArrayList<>(myRepositories);
        mySettings = GitVcsSettings.getInstance(myProject);
    }

    protected abstract void execute();

    protected abstract void rollback();

    @Nonnull
    public abstract LocalizeValue getSuccessMessage();

    @Nonnull
    protected abstract LocalizeValue getRollbackProposal();

    /**
     * Returns a short downcased name of the operation.
     * It is used by some dialogs or notifications which are common to several operations.
     * Some operations (like checkout new branch) can be not mentioned in these dialogs, so their operation names would be not used.
     */
    @Nonnull
    protected abstract LocalizeValue getOperationName();

    /**
     * @return next repository that wasn't handled (e.g. checked out) yet.
     */
    @Nonnull
    protected GitRepository next() {
        return myRemainingRepositories.iterator().next();
    }

    /**
     * @return true if there are more repositories on which the operation wasn't executed yet.
     */
    protected boolean hasMoreRepositories() {
        return !myRemainingRepositories.isEmpty();
    }

    /**
     * Marks repositories as successful, i.e. they won't be handled again.
     */
    protected void markSuccessful(GitRepository... repositories) {
        for (GitRepository repository : repositories) {
            mySuccessfulRepositories.add(repository);
            myRemainingRepositories.remove(repository);
        }
    }

    /**
     * Marks repositories as successful, i.e. they won't be handled again.
     */
    protected void markSkip(GitRepository... repositories) {
        for (GitRepository repository : repositories) {
            mySkippedRepositories.add(repository);
            myRemainingRepositories.remove(repository);
        }
    }

    /**
     * @return true if the operation has already succeeded in at least one of repositories.
     */
    protected boolean wereSuccessful() {
        return !mySuccessfulRepositories.isEmpty();
    }

    protected boolean wereSkipped() {
        return !mySkippedRepositories.isEmpty();
    }

    @Nonnull
    protected Collection<GitRepository> getSuccessfulRepositories() {
        return mySuccessfulRepositories;
    }

    @Nonnull
    protected Collection<GitRepository> getSkippedRepositories() {
        return mySkippedRepositories;
    }

    @Nonnull
    protected String successfulRepositoriesJoined() {
        return GitUtil.joinToHtml(mySuccessfulRepositories);
    }

    @Nonnull
    protected Collection<GitRepository> getRepositories() {
        return myRepositories;
    }

    @Nonnull
    protected Collection<GitRepository> getRemainingRepositories() {
        return myRemainingRepositories;
    }

    @Nonnull
    protected List<GitRepository> getRemainingRepositoriesExceptGiven(@Nonnull GitRepository currentRepository) {
        List<GitRepository> repositories = new ArrayList<>(myRemainingRepositories);
        repositories.remove(currentRepository);
        return repositories;
    }

    protected void notifySuccess(@Nonnull LocalizeValue message) {
        myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
            .content(message)
            .notify(myProject);
    }

    protected void notifySuccess() {
        notifySuccess(getSuccessMessage());
    }

    @RequiredUIAccess
    protected final void saveAllDocuments() {
        Application application = myProject.getApplication();
        application.invokeAndWait(() -> FileDocumentManager.getInstance().saveAllDocuments(), application.getDefaultModalityState());
    }

    /**
     * Show fatal error as a notification or as a dialog with rollback proposal.
     */
    protected void fatalError(@Nonnull LocalizeValue title, @Nonnull LocalizeValue message) {
        if (wereSuccessful()) {
            showFatalErrorDialogWithRollback(title, message);
        }
        else {
            showFatalNotification(title, message);
        }
    }

    protected void showFatalErrorDialogWithRollback(@Nonnull LocalizeValue title, @Nonnull LocalizeValue message) {
        boolean rollback = myUiHandler.notifyErrorWithRollbackProposal(title, message, getRollbackProposal());
        if (rollback) {
            rollback();
        }
    }

    protected void showFatalNotification(@Nonnull LocalizeValue title, @Nonnull LocalizeValue message) {
        notifyError(title, message);
    }

    protected void notifyError(@Nonnull LocalizeValue title, @Nonnull LocalizeValue message) {
        myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(title)
            .content(message)
            .notify(myProject);
    }

    @Nonnull
    protected ProgressIndicator getIndicator() {
        return myUiHandler.getProgressIndicator();
    }

    /**
     * Display the error saying that the operation can't be performed because there are unmerged files in a repository.
     * Such error prevents checking out and creating new branch.
     */
    protected void fatalUnmergedFilesError() {
        if (wereSuccessful()) {
            showUnmergedFilesDialogWithRollback();
        }
        else {
            showUnmergedFilesNotification();
        }
    }

    @Nonnull
    protected String repositories() {
        return pluralize("repository", getSuccessfulRepositories().size());
    }

    /**
     * Updates the recently visited branch in the settings.
     * This is to be performed after successful checkout operation.
     */
    protected void updateRecentBranch() {
        if (getRepositories().size() == 1) {
            GitRepository repository = myRepositories.iterator().next();
            String currentHead = myCurrentHeads.get(repository);
            if (currentHead != null) {
                mySettings.setRecentBranchOfRepository(repository.getRoot().getPath(), currentHead);
            }
            else {
                LOG.error("Current head is not known for " + repository.getRoot().getPath());
            }
        }
        else {
            String recentCommonBranch = getRecentCommonBranch();
            if (recentCommonBranch != null) {
                mySettings.setRecentCommonBranch(recentCommonBranch);
            }
        }
    }

    /**
     * Returns the hash of the revision which was current before the start of this GitBranchOperation.
     */
    @Nonnull
    protected String getInitialRevision(@Nonnull GitRepository repository) {
        return myInitialRevisions.get(repository);
    }

    @Nullable
    private String getRecentCommonBranch() {
        String recentCommonBranch = null;
        for (String branch : myCurrentHeads.values()) {
            if (recentCommonBranch == null) {
                recentCommonBranch = branch;
            }
            else if (!recentCommonBranch.equals(branch)) {
                return null;
            }
        }
        return recentCommonBranch;
    }

    private void showUnmergedFilesDialogWithRollback() {
        boolean ok = myUiHandler.showUnmergedFilesMessageWithRollback(getOperationName(), getRollbackProposal());
        if (ok) {
            rollback();
        }
    }

    private void showUnmergedFilesNotification() {
        myUiHandler.showUnmergedFilesNotification(getOperationName(), getRepositories());
    }

    /**
     * Asynchronously refreshes the VFS root directory of the given repository.
     */
    protected void refreshRoot(@Nonnull GitRepository repository) {
        // marking all files dirty, because sometimes FileWatcher is unable to process such a large set of changes that can happen during
        // checkout on a large repository: IDEA-89944
        VirtualFileUtil.markDirtyAndRefresh(false, true, false, repository.getRoot());
    }

    protected void fatalLocalChangesError(@Nonnull String reference) {
        LocalizeValue title = LocalizeValue.localizeTODO(String.format("Couldn't %s %s", getOperationName(), reference));
        if (wereSuccessful()) {
            showFatalErrorDialogWithRollback(title, LocalizeValue.empty());
        }
    }

    /**
     * Shows the error "The following untracked working tree files would be overwritten by checkout/merge".
     * If there were no repositories that succeeded the operation, shows a notification with a link to the list of these untracked files.
     * If some repositories succeeded, shows a dialog with the list of these files and a proposal to rollback the operation of those
     * repositories.
     */
    protected void fatalUntrackedFilesError(@Nonnull VirtualFile root, @Nonnull Collection<String> relativePaths) {
        if (wereSuccessful()) {
            showUntrackedFilesDialogWithRollback(root, relativePaths);
        }
        else {
            showUntrackedFilesNotification(root, relativePaths);
        }
    }

    private void showUntrackedFilesNotification(@Nonnull VirtualFile root, @Nonnull Collection<String> relativePaths) {
        myUiHandler.showUntrackedFilesNotification(getOperationName(), root, relativePaths);
    }

    private void showUntrackedFilesDialogWithRollback(@Nonnull VirtualFile root, @Nonnull Collection<String> relativePaths) {
        boolean ok = myUiHandler.showUntrackedFilesDialogWithRollback(getOperationName(), getRollbackProposal(), root, relativePaths);
        if (ok) {
            rollback();
        }
    }

    /**
     * TODO this is non-optimal and even incorrect, since such diff shows the difference between committed changes
     * For each of the given repositories looks to the diff between current branch and the given branch and converts it to the list of
     * local changes.
     */
    @Nonnull
    Map<GitRepository, List<Change>> collectLocalChangesConflictingWithBranch(
        @Nonnull Collection<GitRepository> repositories,
        @Nonnull String currentBranch,
        @Nonnull String otherBranch
    ) {
        Map<GitRepository, List<Change>> changes = new HashMap<>();
        for (GitRepository repository : repositories) {
            try {
                Collection<String> diff = GitUtil.getPathsDiffBetweenRefs(myGit, repository, currentBranch, otherBranch);
                List<Change> changesInRepo = GitUtil.findLocalChangesForPaths(myProject, repository.getRoot(), diff, false);
                if (!changesInRepo.isEmpty()) {
                    changes.put(repository, changesInRepo);
                }
            }
            catch (VcsException e) {
                // ignoring the exception: this is not fatal if we won't collect such a diff from other repositories.
                // At worst, use will get double dialog proposing the smart checkout.
                LOG.warn(
                    String.format("Couldn't collect diff between %s and %s in %s", currentBranch, otherBranch, repository.getRoot()),
                    e
                );
            }
        }
        return changes;
    }

    /**
     * When checkout or merge operation on a repository fails with the error "local changes would be overwritten by...",
     * affected local files are captured by the {@link GitMessageWithFilesDetector detector}.
     * Then all remaining (non successful repositories) are searched if they are about to fail with the same problem.
     * All collected local changes which prevent the operation, together with these repositories, are returned.
     *
     * @param currentRepository         The first repository which failed the operation.
     * @param localChangesOverwrittenBy The detector of local changes would be overwritten by merge/checkout.
     * @param currentBranch             Current branch.
     * @param nextBranch                Branch to compare with (the branch to be checked out, or the branch to be merged).
     * @return Repositories that have failed or would fail with the "local changes" error, together with these local changes.
     */
    @Nonnull
    protected Pair<List<GitRepository>, List<Change>> getConflictingRepositoriesAndAffectedChanges(
        @Nonnull GitRepository currentRepository,
        @Nonnull GitMessageWithFilesDetector localChangesOverwrittenBy,
        String currentBranch,
        String nextBranch
    ) {
        // get changes overwritten by checkout from the error message captured from Git
        List<Change> affectedChanges = GitUtil.findLocalChangesForPaths(
            myProject,
            currentRepository.getRoot(),
            localChangesOverwrittenBy.getRelativeFilePaths(),
            true
        );
        // get all other conflicting changes
        // get changes in all other repositories (except those which already have succeeded) to avoid multiple dialogs proposing smart checkout
        Map<GitRepository, List<Change>> conflictingChangesInRepositories = collectLocalChangesConflictingWithBranch(
            getRemainingRepositoriesExceptGiven(currentRepository),
            currentBranch,
            nextBranch
        );

        Set<GitRepository> otherProblematicRepositories = conflictingChangesInRepositories.keySet();
        List<GitRepository> allConflictingRepositories = new ArrayList<>(otherProblematicRepositories);
        allConflictingRepositories.add(currentRepository);
        for (List<Change> changes : conflictingChangesInRepositories.values()) {
            affectedChanges.addAll(changes);
        }

        return Pair.create(allConflictingRepositories, affectedChanges);
    }

    @Nonnull
    protected static String stringifyBranchesByRepos(@Nonnull Map<GitRepository, String> heads) {
        MultiMap<String, VirtualFile> grouped = groupByBranches(heads);
        if (grouped.size() == 1) {
            return grouped.keySet().iterator().next();
        }
        return StringUtil.join(
            grouped.entrySet(),
            entry -> {
                String roots = StringUtil.join(entry.getValue(), VirtualFile::getName, ", ");
                return entry.getKey() + " (in " + roots + ")";
            },
            "<br/>"
        );
    }

    @Nonnull
    private static MultiMap<String, VirtualFile> groupByBranches(@Nonnull Map<GitRepository, String> heads) {
        MultiMap<String, VirtualFile> result = MultiMap.createLinked();
        List<GitRepository> sortedRepos = DvcsUtil.sortRepositories(heads.keySet());
        for (GitRepository repo : sortedRepos) {
            result.putValue(heads.get(repo), repo.getRoot());
        }
        return result;
    }
}
