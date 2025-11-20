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

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.ide.ServiceManager;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationAction;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.MultiMap;
import consulo.util.dataholder.Key;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import git4idea.GitCommit;
import git4idea.commands.*;
import git4idea.config.GitVersionSpecialty;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static consulo.util.collection.ContainerUtil.exists;
import static consulo.util.lang.ObjectUtil.assertNotNull;
import static consulo.versionControlSystem.VcsNotifier.STANDARD_NOTIFICATION;
import static consulo.versionControlSystem.distributed.DvcsUtil.getShortRepositoryName;

/**
 * Deletes a branch.
 * If branch is not fully merged to the current branch, shows a dialog with the list of unmerged commits and with a list of branches
 * current branch are merged to, and makes force delete, if wanted.
 */
class GitDeleteBranchOperation extends GitBranchOperation {
    private static final Logger LOG = Logger.getInstance(GitDeleteBranchOperation.class);

    static final LocalizeValue RESTORE = LocalizeValue.localizeTODO("Restore");
    static final LocalizeValue VIEW_COMMITS = LocalizeValue.localizeTODO("View Commits");
    static final LocalizeValue DELETE_TRACKED_BRANCH = LocalizeValue.localizeTODO("Delete Tracked Branch");

    @Nonnull
    private final String myBranchName;
    @Nonnull
    private final MultiMap<String, GitRepository> myTrackedBranches;

    @Nonnull
    private final Map<GitRepository, UnmergedBranchInfo> myUnmergedToBranches;
    @Nonnull
    private final Map<GitRepository, String> myDeletedBranchTips;

    GitDeleteBranchOperation(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull GitBranchUiHandler uiHandler,
        @Nonnull Collection<GitRepository> repositories,
        @Nonnull String branchName
    ) {
        super(project, git, uiHandler, repositories);
        myBranchName = branchName;
        myTrackedBranches = groupByTrackedBranchName(branchName, repositories);
        myUnmergedToBranches = new HashMap<>();
        myDeletedBranchTips = ContainerUtil.map2Map(repositories, (GitRepository repo) -> {
            GitBranchesCollection branches = repo.getBranches();
            String branch = assertNotNull(branches.getHash(assertNotNull(branches.findLocalBranch(myBranchName)))).asString();
            return Pair.create(repo, branch);
        });
    }

    @Override
    public void execute() {
        boolean fatalErrorHappened = false;
        while (hasMoreRepositories() && !fatalErrorHappened) {
            GitRepository repository = next();

            GitSimpleEventDetector notFullyMergedDetector =
                new GitSimpleEventDetector(GitSimpleEventDetector.Event.BRANCH_NOT_FULLY_MERGED);
            GitBranchNotMergedToUpstreamDetector notMergedToUpstreamDetector = new GitBranchNotMergedToUpstreamDetector();
            GitCommandResult result =
                myGit.branchDelete(repository, myBranchName, false, notFullyMergedDetector, notMergedToUpstreamDetector);

            if (result.success()) {
                refresh(repository);
                markSuccessful(repository);
            }
            else if (notFullyMergedDetector.hasHappened()) {
                String baseBranch = notMergedToUpstreamDetector.getBaseBranch();
                if (baseBranch == null) { // GitBranchNotMergedToUpstreamDetector didn't happen
                    baseBranch = myCurrentHeads.get(repository);
                }
                myUnmergedToBranches.put(
                    repository,
                    new UnmergedBranchInfo(myDeletedBranchTips.get(repository), GitBranchUtil.stripRefsPrefix(baseBranch))
                );

                GitCommandResult forceDeleteResult = myGit.branchDelete(repository, myBranchName, true);
                if (forceDeleteResult.success()) {
                    refresh(repository);
                    markSuccessful(repository);
                }
                else {
                    fatalError(getErrorTitle(), forceDeleteResult.getErrorOutputAsHtmlValue());
                    fatalErrorHappened = true;
                }
            }
            else {
                fatalError(getErrorTitle(), result.getErrorOutputAsJoinedValue());
                fatalErrorHappened = true;
            }
        }

        if (!fatalErrorHappened) {
            notifySuccess();
        }
    }

    @Override
    protected void notifySuccess() {
        boolean unmergedCommits = !myUnmergedToBranches.isEmpty();
        String message = "<b>Deleted Branch:</b> " + myBranchName;
        if (unmergedCommits) {
            message += "<br/>Unmerged commits were discarded";
        }
        Notification.Builder builder = myNotificationService.newInfo(STANDARD_NOTIFICATION)
            .content(LocalizeValue.localizeTODO(message))
            .addAction(new NotificationAction(RESTORE) {
                @Override
                @RequiredUIAccess
                public void actionPerformed(@Nonnull AnActionEvent e, @Nonnull Notification notification) {
                    restoreInBackground(notification);
                }
            });
        if (unmergedCommits) {
            builder.addAction(new NotificationAction(VIEW_COMMITS) {
                @Override
                @RequiredUIAccess
                public void actionPerformed(@Nonnull AnActionEvent e, @Nonnull Notification notification) {
                    viewUnmergedCommitsInBackground(notification);
                }
            });
        }
        if (!myTrackedBranches.isEmpty() && hasOnlyTrackingBranch(myTrackedBranches, myBranchName)) {
            builder.addAction(new NotificationAction(DELETE_TRACKED_BRANCH) {
                @Override
                @RequiredUIAccess
                public void actionPerformed(@Nonnull AnActionEvent e, @Nonnull Notification notification) {
                    deleteTrackedBranchInBackground();
                }
            });
        }
        builder.notify(myProject);
    }

    private static boolean hasOnlyTrackingBranch(@Nonnull MultiMap<String, GitRepository> trackedBranches, @Nonnull String localBranch) {
        for (String remoteBranch : trackedBranches.keySet()) {
            for (GitRepository repository : trackedBranches.get(remoteBranch)) {
                if (exists(
                    repository.getBranchTrackInfos(),
                    info -> !info.localBranch().getName().equals(localBranch)
                        && info.remoteBranch().getName().equals(remoteBranch)
                )) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void refresh(@Nonnull GitRepository... repositories) {
        for (GitRepository repository : repositories) {
            repository.update();
        }
    }

    @Override
    protected void rollback() {
        GitCompoundResult result = doRollback();
        if (!result.totalSuccess()) {
            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Error during rollback of branch deletion"))
                .content(result.getErrorOutputWithReposIndication())
                .notify(myProject);
        }
    }

    @Nonnull
    private GitCompoundResult doRollback() {
        GitCompoundResult result = new GitCompoundResult(myProject);
        for (GitRepository repository : getSuccessfulRepositories()) {
            GitCommandResult res = myGit.branchCreate(repository, myBranchName, myDeletedBranchTips.get(repository));
            result.append(repository, res);

            for (String trackedBranch : myTrackedBranches.keySet()) {
                if (myTrackedBranches.get(trackedBranch).contains(repository)) {
                    GitCommandResult setTrackResult = setUpTracking(repository, myBranchName, trackedBranch);
                    if (!setTrackResult.success()) {
                        LOG.warn(
                            "Couldn't set " + myBranchName + " to track " + trackedBranch + " in " + repository.getRoot().getName() +
                                ": " + setTrackResult.getErrorOutputAsJoinedString()
                        );
                    }
                }
            }

            refresh(repository);
        }
        return result;
    }

    @Nonnull
    private GitCommandResult setUpTracking(@Nonnull GitRepository repository, @Nonnull String branchName, @Nonnull String trackedBranch) {
        GitLineHandler handler = new GitLineHandler(myProject, repository.getRoot(), GitCommand.BRANCH);
        if (GitVersionSpecialty.KNOWS_SET_UPSTREAM_TO.existsIn(repository.getVcs().getVersion())) {
            handler.addParameters("--set-upstream-to", trackedBranch, branchName);
        }
        else {
            handler.addParameters("--set-upstream", branchName, trackedBranch);
        }
        return myGit.runCommand(handler);
    }

    @Nonnull
    private LocalizeValue getErrorTitle() {
        return LocalizeValue.localizeTODO(String.format("Branch %s wasn't deleted", myBranchName));
    }

    @Nonnull
    @Override
    public LocalizeValue getSuccessMessage() {
        return LocalizeValue.localizeTODO(String.format("Deleted branch %s", formatBranchName(myBranchName)));
    }

    @Nonnull
    @Override
    protected LocalizeValue getRollbackProposal() {
        return LocalizeValue.localizeTODO(
            "However branch deletion has succeeded for the following " + repositories() + ":<br/>" +
            successfulRepositoriesJoined() +
            "<br/>You may rollback (recreate " + myBranchName + " in these roots) not to let branches diverge."
        );
    }

    @Nonnull
    @Override
    protected LocalizeValue getOperationName() {
        return LocalizeValue.localizeTODO("branch deletion");
    }

    @Nonnull
    private static String formatBranchName(@Nonnull String name) {
        return "<b><code>" + name + "</code></b>";
    }

    /**
     * Shows a dialog "the branch is not fully merged" with the list of unmerged commits.
     * User may still want to force delete the branch.
     * In multi-repository setup collects unmerged commits for all given repositories.
     *
     * @return true if the branch should be restored.
     */
    private boolean showNotFullyMergedDialog(@Nonnull Map<GitRepository, UnmergedBranchInfo> unmergedBranches) {
        Map<GitRepository, List<GitCommit>> history = new HashMap<>();
        // we don't confuse user with the absence of repositories which branch was deleted w/o force,
        // we display no commits for them
        for (GitRepository repository : getRepositories()) {
            if (unmergedBranches.containsKey(repository)) {
                UnmergedBranchInfo unmergedInfo = unmergedBranches.get(repository);
                history.put(
                    repository,
                    getUnmergedCommits(repository, unmergedInfo.myTipOfDeletedUnmergedBranch, unmergedInfo.myBaseBranch)
                );
            }
            else {
                history.put(repository, Collections.<GitCommit>emptyList());
            }
        }
        Map<GitRepository, String> baseBranches = ContainerUtil.map2Map(
            unmergedBranches.keySet(),
            it -> Pair.create(it, unmergedBranches.get(it).myBaseBranch)
        );
        return myUiHandler.showBranchIsNotFullyMergedDialog(myProject, history, baseBranches, myBranchName);
    }

    @Nonnull
    private static List<GitCommit> getUnmergedCommits(
        @Nonnull GitRepository repository,
        @Nonnull String branchName,
        @Nonnull String baseBranch
    ) {
        String range = baseBranch + ".." + branchName;
        try {
            return GitHistoryUtils.history(repository.getProject(), repository.getRoot(), range);
        }
        catch (VcsException e) {
            LOG.warn("Couldn't get `git log " + range + "` in " + getShortRepositoryName(repository), e);
        }
        return Collections.emptyList();
    }

    @Nonnull
    private static MultiMap<String, GitRepository> groupByTrackedBranchName(
        @Nonnull String branchName,
        @Nonnull Collection<GitRepository> repositories
    ) {
        MultiMap<String, GitRepository> trackedBranchNames = MultiMap.createLinked();
        for (GitRepository repository : repositories) {
            GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfo(repository, branchName);
            if (trackInfo != null) {
                trackedBranchNames.putValue(trackInfo.remoteBranch().getNameForLocalOperations(), repository);
            }
        }
        return trackedBranchNames;
    }

    // warning: not deleting branch 'feature' that is not yet merged to
    //          'refs/remotes/origin/feature', even though it is merged to HEAD.
    // error: The branch 'feature' is not fully merged.
    // If you are sure you want to delete it, run 'git branch -D feature'.
    private static class GitBranchNotMergedToUpstreamDetector implements GitLineHandlerListener {

        private static final Pattern PATTERN = Pattern.compile(".*'(.*)', even though it is merged to.*");
        @Nullable
        private String myBaseBranch;

        @Override
        public void onLineAvailable(String line, Key outputType) {
            Matcher matcher = PATTERN.matcher(line);
            if (matcher.matches()) {
                myBaseBranch = matcher.group(1);
            }
        }

        @Override
        public void processTerminated(int exitCode) {
        }

        @Override
        public void startFailed(Throwable exception) {
        }

        @Nullable
        public String getBaseBranch() {
            return myBaseBranch;
        }
    }

    static class UnmergedBranchInfo {
        @Nonnull
        private final String myTipOfDeletedUnmergedBranch;
        @Nonnull
        private final String myBaseBranch;

        public UnmergedBranchInfo(@Nonnull String tipOfDeletedUnmergedBranch, @Nonnull String baseBranch) {
            myTipOfDeletedUnmergedBranch = tipOfDeletedUnmergedBranch;
            myBaseBranch = baseBranch;
        }
    }

    private void deleteTrackedBranchInBackground() {
        new Task.Backgroundable(myProject, LocalizeValue.localizeTODO("Deleting Remote Branch " + myBranchName + "...")) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                GitBrancher brancher = ServiceManager.getService(getProject(), GitBrancher.class);
                for (String remoteBranch : myTrackedBranches.keySet()) {
                    brancher.deleteRemoteBranch(remoteBranch, new ArrayList<>(myTrackedBranches.get(remoteBranch)));
                }
            }
        }.queue();
    }

    private void restoreInBackground(@Nonnull Notification notification) {
        new Task.Backgroundable(myProject, LocalizeValue.localizeTODO("Restoring Branch " + myBranchName + "...")) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                rollbackBranchDeletion(notification);
            }
        }.queue();
    }

    private void rollbackBranchDeletion(@Nonnull Notification notification) {
        GitCompoundResult result = doRollback();
        if (result.totalSuccess()) {
            notification.expire();
        }
        else {
            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Couldn't Restore " + formatBranchName(myBranchName)))
                .content(result.getErrorOutputWithReposIndication())
                .notify(myProject);
        }
    }

    private void viewUnmergedCommitsInBackground(@Nonnull Notification notification) {
        new Task.Backgroundable(myProject, LocalizeValue.localizeTODO("Collecting Unmerged Commits...")) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                boolean restore = showNotFullyMergedDialog(myUnmergedToBranches);
                if (restore) {
                    rollbackBranchDeletion(notification);
                }
            }
        }.queue();
    }
}
