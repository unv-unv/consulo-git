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
package git4idea.actions;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionParentRef;
import consulo.annotation.component.ActionRef;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.fileChooser.FileChooserDescriptor;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.IdeaFileChooser;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ProjectManager;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.VcsDirtyScopeManager;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import jakarta.annotation.Nonnull;

/**
 * Initialize git repository action
 */
@ActionImpl(id = "Git.Init", parents = @ActionParentRef(@ActionRef(id = "Vcs.Import")))
public class GitInit extends DumbAwareAction {
    public GitInit() {
        super(GitLocalize.actionInitText());
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            project = ProjectManager.getInstance().getDefaultProject();
        }
        FileChooserDescriptor fcd = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withShowFileSystemRoots(true)
            .withTitleValue(GitLocalize.initDestinationDirectoryTitle())
            .withDescriptionValue(GitLocalize.initDestinationDirectoryDescription())
            .withHideIgnored(false);
        VirtualFile baseDir = e.getData(VirtualFile.KEY);
        if (baseDir == null) {
            baseDir = project.getBaseDir();
        }
        doInit(project, fcd, baseDir, baseDir);
    }

    @RequiredUIAccess
    private static void doInit(final Project project, FileChooserDescriptor fcd, VirtualFile baseDir, VirtualFile finalBaseDir) {
        IdeaFileChooser.chooseFile(
            fcd,
            project,
            baseDir,
            root -> {
                //noinspection RequiredXAction
                if (GitUtil.isUnderGit(root) && Messages.showYesNoDialog(
                    project,
                    GitLocalize.initWarningAlreadyUnderGit(StringUtil.escapeXml(root.getPresentableUrl())).get(),
                    GitLocalize.initWarningTitle().get(),
                    UIUtil.getWarningIcon()
                ) != Messages.YES) {
                    return;
                }

                GitCommandResult result = ServiceManager.getService(Git.class).init(project, root);
                if (!result.success()) {
                    GitVcs vcs = GitVcs.getInstance(project);
                    if (vcs != null && vcs.getExecutableValidator().checkExecutableAndNotifyIfNeeded()) {
                        NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                            .title(LocalizeValue.localizeTODO("Git init failed"))
                            .content(result.getErrorOutputAsHtmlValue())
                            .notify(project);
                    }
                    return;
                }

                if (project.isDefault()) {
                    return;
                }
                final String path = root.equals(finalBaseDir) ? "" : root.getPath();
                GitVcs.runInBackground(new Task.Backgroundable(project, GitLocalize.commonRefreshing()) {
                    @Override
                    public void run(@Nonnull ProgressIndicator indicator) {
                        refreshAndConfigureVcsMappings(project, root, path);
                    }
                });
            }
        );
    }

    public static void refreshAndConfigureVcsMappings(Project project, VirtualFile root, String path) {
        VirtualFileUtil.markDirtyAndRefresh(false, true, false, root);
        ProjectLevelVcsManager manager = ProjectLevelVcsManager.getInstance(project);
        manager.setDirectoryMappings(VcsUtil.addMapping(manager.getDirectoryMappings(), path, GitVcs.NAME));
        VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(root);
    }
}
