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
import consulo.application.ApplicationManager;
import consulo.application.progress.ProgressIndicator;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.StringUtil;
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
    private final Git myGit;
    @Nonnull
    private final ProgressIndicator myProgressIndicator;

    public GitBranchUiHandlerImpl(@Nonnull Project project, @Nonnull Git git, @Nonnull ProgressIndicator indicator) {
        myProject = project;
        myGit = git;
        myProgressIndicator = indicator;
    }

    @Override
    public boolean notifyErrorWithRollbackProposal(@Nonnull final String title,
                                                   @Nonnull final String message,
                                                   @Nonnull final String rollbackProposal) {
        final AtomicBoolean ok = new AtomicBoolean();
        UIUtil.invokeAndWaitIfNeeded(new Runnable() {
            @Override
            public void run() {
                StringBuilder description = new StringBuilder();
                if (!StringUtil.isEmptyOrSpaces(message)) {
                    description.append(message).append("<br/>");
                }
                description.append(rollbackProposal);
                ok.set(Messages.YES == DialogManager.showOkCancelDialog(myProject,
                    XmlStringUtil.wrapInHtml(description),
                    title,
                    "Rollback",
                    "Don't rollback",
                    Messages.getErrorIcon()));
            }
        });
        return ok.get();
    }

    @Override
    public void showUnmergedFilesNotification(@Nonnull final String operationName, @Nonnull final Collection<GitRepository> repositories) {
        String title = unmergedFilesErrorTitle(operationName);
        String description = unmergedFilesErrorNotificationDescription(operationName);
        VcsNotifier.getInstance(myProject).notifyError(title, description, new NotificationListener() {
            @Override
            public void hyperlinkUpdate(@Nonnull Notification notification, @Nonnull HyperlinkEvent event) {
                if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getDescription().equals("resolve")) {
                    GitConflictResolver.Params params = new GitConflictResolver.Params().
                        setMergeDescription(String.format(
                            "The following files have unresolved conflicts. You need to resolve them before %s.",
                            operationName)).
                        setErrorNotificationTitle("Unresolved files remain.");
                    new GitConflictResolver(myProject, myGit, GitUtil.getRootsFromRepositories(repositories), params).merge();
                }
            }
        });
    }

    @Override
    public boolean showUnmergedFilesMessageWithRollback(@Nonnull final String operationName, @Nonnull final String rollbackProposal) {
        final AtomicBoolean ok = new AtomicBoolean();
        UIUtil.invokeAndWaitIfNeeded(new Runnable() {
            @Override
            public void run() {
                String description =
                    String.format("<html>You have to resolve all merge conflicts before %s.<br/>%s</html>", operationName, rollbackProposal);
                // suppressing: this message looks ugly if capitalized by words
                //noinspection DialogTitleCapitalization
                ok.set(Messages.YES == DialogManager.showOkCancelDialog(myProject,
                    description,
                    unmergedFilesErrorTitle(operationName),
                    "Rollback",
                    "Don't rollback",
                    Messages.getErrorIcon()));
            }
        });
        return ok.get();
    }

    @Override
    public void showUntrackedFilesNotification(@Nonnull String operationName,
                                               @Nonnull VirtualFile root,
                                               @Nonnull Collection<String> relativePaths) {
        GitUntrackedFilesHelper.notifyUntrackedFilesOverwrittenBy(myProject, root, relativePaths, operationName, null);
    }

    @Override
    public boolean showUntrackedFilesDialogWithRollback(@Nonnull String operationName,
                                                        @Nonnull final String rollbackProposal,
                                                        @Nonnull VirtualFile root,
                                                        @Nonnull final Collection<String> relativePaths) {
        return GitUntrackedFilesHelper.showUntrackedFilesDialogWithRollback(myProject, operationName, rollbackProposal, root, relativePaths);
    }

    @Nonnull
    @Override
    public ProgressIndicator getProgressIndicator() {
        return myProgressIndicator;
    }

    @Override
    public int showSmartOperationDialog(@Nonnull Project project,
                                        @Nonnull List<Change> changes,
                                        @Nonnull Collection<String> paths,
                                        @Nonnull String operation,
                                        @Nullable String forceButtonTitle) {
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
    public boolean showBranchIsNotFullyMergedDialog(@Nonnull Project project,
                                                    @Nonnull Map<GitRepository, List<GitCommit>> history,
                                                    @Nonnull Map<GitRepository, String> baseBranches,
                                                    @Nonnull String removedBranch) {
        AtomicBoolean restore = new AtomicBoolean();
        ApplicationManager.getApplication()
            .invokeAndWait(() -> restore.set(GitBranchIsNotFullyMergedDialog.showAndGetAnswer(myProject,
                    history,
                    baseBranches,
                    removedBranch)),
                Application.get().getDefaultModalityState());
        return restore.get();
    }

    @Nonnull
    private static String unmergedFilesErrorTitle(@Nonnull String operationName) {
        return "Can't " + operationName + " because of unmerged files";
    }

    @Nonnull
    private static String unmergedFilesErrorNotificationDescription(String operationName) {
        return "You have to <a href='resolve'>resolve</a> all merge conflicts before " + operationName + ".<br/>" +
            "After resolving conflicts you also probably would want to commit your files to the current branch.";
    }
}
