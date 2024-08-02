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

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.fileChooser.FileChooserDescriptor;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.IdeaFileChooser;
import consulo.ide.ServiceManager;
import consulo.language.editor.CommonDataKeys;
import consulo.project.Project;
import consulo.project.ProjectManager;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.awt.Messages;
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
import git4idea.i18n.GitBundle;

import jakarta.annotation.Nonnull;
import java.util.function.Consumer;

/**
 * Initialize git repository action
 */
public class GitInit extends DumbAwareAction
{
	@Override
	public void actionPerformed(final AnActionEvent e)
	{
		Project project = e.getData(CommonDataKeys.PROJECT);
		if(project == null)
		{
			project = ProjectManager.getInstance().getDefaultProject();
		}
		FileChooserDescriptor fcd = FileChooserDescriptorFactory.createSingleFolderDescriptor();
		fcd.setShowFileSystemRoots(true);
		fcd.setTitle(GitBundle.message("init.destination.directory.title"));
		fcd.setDescription(GitBundle.message("init.destination.directory.description"));
		fcd.setHideIgnored(false);
		VirtualFile baseDir = e.getData(CommonDataKeys.VIRTUAL_FILE);
		if(baseDir == null)
		{
			baseDir = project.getBaseDir();
		}
		doInit(project, fcd, baseDir, baseDir);
	}

	private static void doInit(final Project project, FileChooserDescriptor fcd, VirtualFile baseDir, final VirtualFile finalBaseDir)
	{
		IdeaFileChooser.chooseFile(fcd, project, baseDir, (Consumer<VirtualFile>) root ->
		{
			if(GitUtil.isUnderGit(root) && Messages.showYesNoDialog(project, GitBundle.message("init.warning.already.under.git", StringUtil.escapeXml(root.getPresentableUrl())),
					GitBundle.message("init.warning.title"), Messages.getWarningIcon()) != Messages.YES)
			{
				return;
			}

			GitCommandResult result = ServiceManager.getService(Git.class).init(project, root);
			if(!result.success())
			{
				GitVcs vcs = GitVcs.getInstance(project);
				if(vcs != null && vcs.getExecutableValidator().checkExecutableAndNotifyIfNeeded())
				{
					VcsNotifier.getInstance(project).notifyError("Git init failed", result.getErrorOutputAsHtmlString());
				}
				return;
			}

			if(project.isDefault())
			{
				return;
			}
			final String path = root.equals(finalBaseDir) ? "" : root.getPath();
			GitVcs.runInBackground(new Task.Backgroundable(project, GitBundle.message("common.refreshing"))
			{
				@Override
				public void run(@Nonnull ProgressIndicator indicator)
				{
					refreshAndConfigureVcsMappings(project, root, path);
				}
			});
		});
	}

	public static void refreshAndConfigureVcsMappings(final Project project, final VirtualFile root, final String path)
	{
		VirtualFileUtil.markDirtyAndRefresh(false, true, false, root);
		ProjectLevelVcsManager manager = ProjectLevelVcsManager.getInstance(project);
		manager.setDirectoryMappings(VcsUtil.addMapping(manager.getDirectoryMappings(), path, GitVcs.NAME));
		VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(root);
	}
}
