/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.xml.XmlStringUtil;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangesBrowserFactory;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.GitCommit;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.merge.GitConflictResolver;
import git4idea.repo.GitRepository;
import git4idea.util.GitSimplePathsBrowser;
import git4idea.util.GitUntrackedFilesHelper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class GitBranchUiHandlerImpl implements GitBranchUiHandler {
    @Nonnull
    private final Project myProject;
    @Nonnull
    protected final NotificationService myNotificationService;
    @Nonnull
    private final Git myGit;
    @Nonnull
    private final ProgressIndicator myProgressIndicator;

    public GitBranchUiHandlerImpl(@Nonnull Project project, @Nonnull Git git, @Nonnull ProgressIndicator indicator) {
        myProject = project;
        myNotificationService = NotificationService.getInstance();
        myGit = git;
        myProgressIndicator = indicator;
    }

    @Override
    public boolean notifyErrorWithRollbackProposal(
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue message,
        @Nonnull LocalizeValue rollbackProposal
    ) {
        AtomicBoolean ok = new AtomicBoolean();
        UIUtil.invokeAndWaitIfNeeded((Runnable) () -> {
            StringBuilder description = new StringBuilder();
            if (message.isNotEmpty()) {
                description.append(message).append("<br/>");
            }
            description.append(rollbackProposal);
            ok.set(Messages.YES == DialogManager.showOkCancelDialog(
                myProject,
                XmlStringUtil.wrapInHtml(description),
                title.get(),
                "Rollback",
                "Don't rollback",
                UIUtil.getErrorIcon()
            ));
        });
        return ok.get();
    }

    @Override
    public void showUnmergedFilesNotification(@Nonnull LocalizeValue operationName, @Nonnull Collection<GitRepository> repositories) {
        myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(unmergedFilesErrorTitle(operationName))
            .content(unmergedFilesErrorNotificationDescription(operationName))
            .hyperlinkListener((notification, event) -> {
                if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getDescription().equals("resolve")) {
                    GitConflictResolver.Params params = new GitConflictResolver.Params()
                        .setMergeDescription(LocalizeValue.localizeTODO(String.format(
                            "The following files have unresolved conflicts. You need to resolve them before %s.",
                            operationName
                        )))
                        .setErrorNotificationTitle(LocalizeValue.localizeTODO("Unresolved files remain."));
                    new GitConflictResolver(myProject, myGit, GitUtil.getRootsFromRepositories(repositories), params).merge();
                }
            })
            .notify(myProject);
    }

    @Override
    public boolean showUnmergedFilesMessageWithRollback(@Nonnull LocalizeValue operationName, @Nonnull LocalizeValue rollbackProposal) {
        AtomicBoolean ok = new AtomicBoolean();
        UIUtil.invokeAndWaitIfNeeded((Runnable) () -> {
            String description = String.format(
                "<html>You have to resolve all merge conflicts before %s.<br/>%s</html>",
                operationName,
                rollbackProposal
            );
            // suppressing: this message looks ugly if capitalized by words
            //noinspection DialogTitleCapitalization
            ok.set(Messages.YES == DialogManager.showOkCancelDialog(
                myProject,
                description,
                unmergedFilesErrorTitle(operationName).get(),
                "Rollback",
                "Don't rollback",
                UIUtil.getErrorIcon()
            ));
        });
        return ok.get();
    }

    @Override
    public void showUntrackedFilesNotification(
        @Nonnull LocalizeValue operationName,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> relativePaths
    ) {
        GitUntrackedFilesHelper.notifyUntrackedFilesOverwrittenBy(myProject, root, relativePaths, operationName, LocalizeValue.empty());
    }

    @Override
    @RequiredUIAccess
    public boolean showUntrackedFilesDialogWithRollback(
        @Nonnull LocalizeValue operationName,
        @Nonnull LocalizeValue rollbackProposal,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> relativePaths
    ) {
        return GitUntrackedFilesHelper.showUntrackedFilesDialogWithRollback(
            myProject,
            operationName,
            rollbackProposal,
            root,
            relativePaths
        );
    }

    @Nonnull
    @Override
    public ProgressIndicator getProgressIndicator() {
        return myProgressIndicator;
    }

    @Override
    public int showSmartOperationDialog(
        @Nonnull Project project,
        @Nonnull List<Change> changes,
        @Nonnull Collection<String> paths,
        @Nonnull String operation,
        @Nullable String forceButtonTitle
    ) {
        JComponent fileBrowser;
        if (!changes.isEmpty()) {
            ChangesBrowserFactory browserFactory = project.getApplication().getInstance(ChangesBrowserFactory.class);
            fileBrowser = browserFactory.createChangeBrowserWithRollback(project, changes).getComponent();
        }
        else {
            fileBrowser = new GitSimplePathsBrowser(project, paths);
        }
        return GitSmartOperationDialog.showAndGetAnswer(myProject, fileBrowser, operation, forceButtonTitle);
    }

    @Override
    @RequiredUIAccess
    public boolean showBranchIsNotFullyMergedDialog(
        @Nonnull Project project,
        @Nonnull Map<GitRepository, List<GitCommit>> history,
        @Nonnull Map<GitRepository, String> baseBranches,
        @Nonnull String removedBranch
    ) {
        AtomicBoolean restore = new AtomicBoolean();
        Application application = myProject.getApplication();
        application.invokeAndWait(
            () -> restore.set(GitBranchIsNotFullyMergedDialog.showAndGetAnswer(
                myProject,
                history,
                baseBranches,
                removedBranch
            )),
            application.getDefaultModalityState()
        );
        return restore.get();
    }

    @Nonnull
    private static LocalizeValue unmergedFilesErrorTitle(@Nonnull LocalizeValue operationName) {
        return LocalizeValue.localizeTODO("Can't " + operationName + " because of unmerged files");
    }

    @Nonnull
    private static LocalizeValue unmergedFilesErrorNotificationDescription(@Nonnull LocalizeValue operationName) {
        return LocalizeValue.localizeTODO(
            "You have to <a href='resolve'>resolve</a> all merge conflicts before " + operationName + ".<br/>" +
                "After resolving conflicts you also probably would want to commit your files to the current branch."
        );
    }
}
