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
import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.ide.ServiceManager;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Messages;
import consulo.util.lang.ref.SimpleReference;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.DialogManager;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRepository;
import git4idea.reset.GitResetMode;
import git4idea.stash.GitChangesSaver;
import git4idea.util.GitFreezingProcess;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static consulo.application.CommonBundle.getCancelButtonText;
import static consulo.ui.ex.awt.Messages.getQuestionIcon;
import static consulo.versionControlSystem.distributed.DvcsUtil.getShortRepositoryName;
import static git4idea.GitUtil.getRootsFromRepositories;
import static git4idea.rebase.GitRebaseUtils.mentionLocalChangesRemainingInStash;

class GitAbortRebaseProcess {
    private static final Logger LOG = Logger.getInstance(GitAbortRebaseProcess.class);

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final Git myGit;
    @Nonnull
    protected final NotificationService myNotificationService;

    @Nullable
    private final GitRepository myRepositoryToAbort;
    @Nonnull
    private final Map<GitRepository, String> myRepositoriesToRollback;
    @Nonnull
    private final Map<GitRepository, String> myInitialCurrentBranches;
    @Nonnull
    private final ProgressIndicator myIndicator;
    @Nullable
    private final GitChangesSaver mySaver;

    GitAbortRebaseProcess(
        @Nonnull Project project,
        @Nullable GitRepository repositoryToAbort,
        @Nonnull Map<GitRepository, String> repositoriesToRollback,
        @Nonnull Map<GitRepository, String> initialCurrentBranches,
        @Nonnull ProgressIndicator progressIndicator,
        @Nullable GitChangesSaver changesSaver
    ) {
        myProject = project;
        myNotificationService = NotificationService.getInstance();
        myRepositoryToAbort = repositoryToAbort;
        myRepositoriesToRollback = repositoriesToRollback;
        myInitialCurrentBranches = initialCurrentBranches;
        myIndicator = progressIndicator;
        mySaver = changesSaver;

        myGit = ServiceManager.getService(Git.class);
    }

    @RequiredUIAccess
    void abortWithConfirmation() {
        LOG.info("Abort rebase. " + (myRepositoryToAbort == null ? "Nothing to abort" : getShortRepositoryName(myRepositoryToAbort)) +
            ". Roots to rollback: " + DvcsUtil.joinShortNames(myRepositoriesToRollback.keySet()));
        SimpleReference<AbortChoice> ref = SimpleReference.create();
        Application application = myProject.getApplication();
        application.invokeAndWait(() -> ref.set(confirmAbort()), application.getDefaultModalityState());

        LOG.info("User choice: " + ref.get());
        if (ref.get() == AbortChoice.ROLLBACK_AND_ABORT) {
            doAbort(true);
        }
        else if (ref.get() == AbortChoice.ABORT) {
            doAbort(false);
        }
    }

    @Nonnull
    private AbortChoice confirmAbort() {
        String title = "Abort Rebase";
        if (myRepositoryToAbort != null) {
            if (myRepositoriesToRollback.isEmpty()) {
                String message = "Are you sure you want to abort rebase" + GitUtil.mention(myRepositoryToAbort) + "?";
                int choice = DialogManager.showOkCancelDialog(myProject, message, title, "Abort", getCancelButtonText(), getQuestionIcon());
                if (choice == Messages.OK) {
                    return AbortChoice.ABORT;
                }
            }
            else {
                String message = "Do you want just to abort rebase" + GitUtil.mention(myRepositoryToAbort) + ",\n" +
                    "or also rollback the successful rebase" + GitUtil.mention(myRepositoriesToRollback.keySet()) + "?";
                int choice = DialogManager.showYesNoCancelDialog(
                    myProject,
                    message,
                    title,
                    "Abort & Rollback",
                    "Abort",
                    getCancelButtonText(),
                    getQuestionIcon()
                );
                if (choice == Messages.YES) {
                    return AbortChoice.ROLLBACK_AND_ABORT;
                }
                else if (choice == Messages.NO) {
                    return AbortChoice.ABORT;
                }
            }
        }
        else {
            if (myRepositoriesToRollback.isEmpty()) {
                LOG.error(new Throwable());
            }
            else {
                String description =
                    "Do you want to rollback the successful rebase" + GitUtil.mention(myRepositoriesToRollback.keySet()) + "?";
                int choice =
                    DialogManager.showOkCancelDialog(myProject, description, title, "Rollback", getCancelButtonText(), getQuestionIcon());
                if (choice == Messages.YES) {
                    return AbortChoice.ROLLBACK_AND_ABORT;
                }
            }
        }
        return AbortChoice.CANCEL;
    }

    enum AbortChoice {
        ABORT,
        ROLLBACK_AND_ABORT,
        CANCEL
    }

    private void doAbort(boolean rollback) {
        new GitFreezingProcess(
            myProject,
            "rebase",
            () -> {
                AccessToken token = DvcsUtil.workingTreeChangeStarted(myProject, "Rebase");
                List<GitRepository> repositoriesToRefresh = new ArrayList<>();
                try {
                    if (myRepositoryToAbort != null) {
                        myIndicator.setText2("git rebase --abort" + GitUtil.mention(myRepositoryToAbort));
                        GitCommandResult result = myGit.rebaseAbort(myRepositoryToAbort);
                        repositoriesToRefresh.add(myRepositoryToAbort);
                        if (!result.success()) {
                            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                                .title(LocalizeValue.localizeTODO("Rebase Abort Failed"))
                                .content(LocalizeValue.localizeTODO(
                                    result.getErrorOutputAsHtmlValue() + mentionLocalChangesRemainingInStash(mySaver)
                                ))
                                .notify(myProject);
                            return;
                        }
                    }

                    if (rollback) {
                        for (GitRepository repo : myRepositoriesToRollback.keySet()) {
                            myIndicator.setText2("git reset --keep" + GitUtil.mention(repo));
                            GitCommandResult res = myGit.reset(repo, GitResetMode.KEEP, myRepositoriesToRollback.get(repo));
                            repositoriesToRefresh.add(repo);

                            if (res.success()) {
                                String initialBranchPosition = myInitialCurrentBranches.get(repo);
                                if (initialBranchPosition != null && !initialBranchPosition.equals(repo.getCurrentBranchName())) {
                                    myIndicator.setText2("git checkout " + initialBranchPosition + GitUtil.mention(repo));
                                    res = myGit.checkout(repo, initialBranchPosition, null, true, false);
                                }
                            }

                            if (!res.success()) {
                                String description = myRepositoryToAbort != null
                                    ? "Rebase abort was successful" + GitUtil.mention(myRepositoryToAbort) + ", but rollback failed"
                                    : "Rollback failed";
                                description += GitUtil.mention(repo) + ":" + res.getErrorOutputAsHtmlValue() +
                                    mentionLocalChangesRemainingInStash(mySaver);
                                myNotificationService.newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                                    .title(LocalizeValue.localizeTODO("Rebase Rollback Failed"))
                                    .content(LocalizeValue.localizeTODO(description))
                                    .notify(myProject);
                                return;
                            }
                        }
                    }

                    if (mySaver != null) {
                        mySaver.load();
                    }
                    myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                        .content(LocalizeValue.localizeTODO("Rebase abort succeeded"))
                        .notify(myProject);
                }
                finally {
                    refresh(repositoriesToRefresh);
                    token.finish();
                }
            }
        ).execute();
    }

    private static void refresh(@Nonnull List<GitRepository> toRefresh) {
        for (GitRepository repository : toRefresh) {
            repository.update();
        }
        VirtualFileUtil.markDirtyAndRefresh(false, true, false, VirtualFileUtil.toVirtualFileArray(getRootsFromRepositories(toRefresh)));
    }
}
