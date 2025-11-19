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
package git4idea.branch;

import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.MessageDialogBuilder;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.Couple;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsNotifier;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitCompoundResult;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.GitMultiRootBranchConfig;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class GitDeleteRemoteBranchOperation extends GitBranchOperation {
    private final String myBranchName;

    public GitDeleteRemoteBranchOperation(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull GitBranchUiHandler handler,
        @Nonnull List<GitRepository> repositories,
        @Nonnull String name
    ) {
        super(project, git, handler, repositories);
        myBranchName = name;
    }

    @Override
    protected void execute() {
        final Collection<GitRepository> repositories = getRepositories();
        Collection<String> trackingBranches = findTrackingBranches(myBranchName, repositories);
        String currentBranch = GitBranchUtil.getCurrentBranchOrRev(repositories);
        boolean currentBranchTracksBranchToDelete = false;
        if (trackingBranches.contains(currentBranch)) {
            currentBranchTracksBranchToDelete = true;
            trackingBranches.remove(currentBranch);
        }

        AtomicReference<DeleteRemoteBranchDecision> decision = new AtomicReference<>();
        boolean finalCurrentBranchTracksBranchToDelete = currentBranchTracksBranchToDelete;
        UIUtil.invokeAndWaitIfNeeded((Runnable) () -> decision.set(confirmBranchDeletion(
            myBranchName,
            trackingBranches,
            finalCurrentBranchTracksBranchToDelete,
            repositories
        )));

        if (decision.get().delete()) {
            boolean deletedSuccessfully = doDeleteRemote(myBranchName, repositories);
            if (deletedSuccessfully) {
                final Collection<String> successfullyDeletedLocalBranches = new ArrayList<>(1);
                if (decision.get().deleteTracking()) {
                    for (final String branch : trackingBranches) {
                        getIndicator().setText("Deleting " + branch);
                        new GitDeleteBranchOperation(myProject, myGit, myUiHandler, repositories, branch) {
                            @Override
                            protected void notifySuccess(@Nonnull LocalizeValue message) {
                                // do nothing - will display a combo notification for all deleted branches below
                                successfullyDeletedLocalBranches.add(branch);
                            }
                        }.execute();
                    }
                }
                notifySuccessfulDeletion(myBranchName, successfullyDeletedLocalBranches);
            }
        }

    }

    @Override
    protected void rollback() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    public LocalizeValue getSuccessMessage() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    protected LocalizeValue getRollbackProposal() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    protected LocalizeValue getOperationName() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    private static Collection<String> findTrackingBranches(@Nonnull String remoteBranch, @Nonnull Collection<GitRepository> repositories) {
        return new GitMultiRootBranchConfig(repositories).getTrackingBranches(remoteBranch);
    }

    private boolean doDeleteRemote(@Nonnull String branchName, @Nonnull Collection<GitRepository> repositories) {
        Couple<String> pair = splitNameOfRemoteBranch(branchName);
        String remoteName = pair.getFirst();
        String branch = pair.getSecond();

        GitCompoundResult result = new GitCompoundResult(myProject);
        for (GitRepository repository : repositories) {
            GitCommandResult res;
            GitRemote remote = getRemoteByName(repository, remoteName);
            if (remote == null) {
                String error = "Couldn't find remote by name: " + remoteName;
                LOG.error(error);
                res = GitCommandResult.error(error);
            }
            else {
                res = pushDeletion(repository, remote, branch);
                if (!res.success() && isAlreadyDeletedError(res.getErrorOutputAsJoinedString())) {
                    res = myGit.remotePrune(repository, remote);
                }
            }
            result.append(repository, res);
            repository.update();
        }
        if (!result.totalSuccess()) {
            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Failed to delete remote branch " + branchName))
                .content(result.getErrorOutputWithReposIndication())
                .notify(myProject);
        }
        return result.totalSuccess();
    }

    private static boolean isAlreadyDeletedError(@Nonnull String errorOutput) {
        return errorOutput.contains("remote ref does not exist");
    }

    /**
     * Returns the remote and the "local" name of a remote branch.
     * Expects branch in format "origin/master", i.e. remote/branch
     */
    private static Couple<String> splitNameOfRemoteBranch(String branchName) {
        int firstSlash = branchName.indexOf('/');
        String remoteName = firstSlash > -1 ? branchName.substring(0, firstSlash) : branchName;
        String remoteBranchName = branchName.substring(firstSlash + 1);
        return Couple.of(remoteName, remoteBranchName);
    }

    @Nonnull
    private GitCommandResult pushDeletion(@Nonnull GitRepository repository, @Nonnull GitRemote remote, @Nonnull String branchName) {
        return myGit.push(repository, remote, ":" + branchName, false, false, false, null);
    }

    @Nullable
    private static GitRemote getRemoteByName(@Nonnull GitRepository repository, @Nonnull String remoteName) {
        for (GitRemote remote : repository.getRemotes()) {
            if (remote.getName().equals(remoteName)) {
                return remote;
            }
        }
        return null;
    }

    private void notifySuccessfulDeletion(@Nonnull String remoteBranchName, @Nonnull Collection<String> localBranches) {
        String message = "";
        if (!localBranches.isEmpty()) {
            message =
                "Also deleted local " + StringUtil.pluralize("branch", localBranches.size()) + ": " + StringUtil.join(localBranches, ", ");
        }
        myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
            .title(LocalizeValue.localizeTODO("Deleted remote branch " + remoteBranchName))
            .content(LocalizeValue.localizeTODO(message))
            .notify(myProject);
    }

    private DeleteRemoteBranchDecision confirmBranchDeletion(
        @Nonnull String branchName,
        @Nonnull Collection<String> trackingBranches,
        boolean currentBranchTracksBranchToDelete,
        @Nonnull Collection<GitRepository> repositories
    ) {
        String title = "Delete Remote Branch";
        String message = "Delete remote branch " + branchName;

        boolean delete;
        boolean deleteTracking;
        if (trackingBranches.isEmpty()) {
            delete = Messages.showYesNoDialog(myProject, message, title, "Delete", "Cancel", UIUtil.getQuestionIcon()) == Messages.YES;
            deleteTracking = false;
        }
        else {
            if (currentBranchTracksBranchToDelete) {
                message += "\n\nCurrent branch " + GitBranchUtil.getCurrentBranchOrRev(repositories) +
                    " tracks " + branchName + " but won't be deleted.";
            }
            final LocalizeValue checkboxMessage;
            if (trackingBranches.size() == 1) {
                checkboxMessage =
                    LocalizeValue.localizeTODO("Delete tracking local branch " + trackingBranches.iterator().next() + " as well");
            }
            else {
                checkboxMessage = LocalizeValue.localizeTODO("Delete tracking local branches " + StringUtil.join(trackingBranches, ", "));
            }

            final AtomicBoolean deleteChoice = new AtomicBoolean();
            delete = MessageDialogBuilder.yesNo(title, message)
                .project(myProject)
                .yesText("Delete")
                .noText("Cancel")
                .doNotAsk(new DialogWrapper.DoNotAskOption.Adapter() {
                    @Override
                    public void rememberChoice(boolean isSelected, int exitCode) {
                        deleteChoice.set(isSelected);
                    }

                    @Nonnull
                    @Override
                    public LocalizeValue getDoNotShowMessage() {
                        return checkboxMessage;
                    }
                })
                .show() == Messages.YES;
            deleteTracking = deleteChoice.get();
        }
        return new DeleteRemoteBranchDecision(delete, deleteTracking);
    }

    private static class DeleteRemoteBranchDecision {
        private final boolean delete;
        private final boolean deleteTracking;

        private DeleteRemoteBranchDecision(boolean delete, boolean deleteTracking) {
            this.delete = delete;
            this.deleteTracking = deleteTracking;
        }

        public boolean delete() {
            return delete;
        }

        public boolean deleteTracking() {
            return deleteTracking;
        }
    }
}