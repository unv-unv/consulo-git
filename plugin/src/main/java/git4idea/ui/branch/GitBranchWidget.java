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
package git4idea.ui.branch;

import com.intellij.dvcs.ui.DvcsStatusWidget;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.ObjectUtil;
import consulo.disposer.Disposer;
import git4idea.GitUtil;
import git4idea.branch.GitBranchUtil;
import git4idea.config.GitVcsSettings;
import git4idea.i18n.GitBundle;
import git4idea.repo.GitRepository;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Status bar widget which displays the current branch for the file currently open in the editor.
 */
public class GitBranchWidget extends DvcsStatusWidget<GitRepository>
{
	private static final String ID = "git";

	public static class Factory implements StatusBarWidgetFactory
	{
		@Override
		@Nonnull
		public String getId()
		{
			return ID;
		}

		@Override
		@Nonnull
		public String getDisplayName()
		{
			return GitBundle.message("git.status.bar.widget.name");
		}

		@Override
		public boolean isAvailable(@Nonnull Project project)
		{
			//return !GitRepositoryManager.getInstance(project).getRepositories().isEmpty();
			return true;
		}

		@Override
		@Nonnull
		public StatusBarWidget createWidget(@Nonnull Project project)
		{
			return new GitBranchWidget(project);
		}

		@Override
		public boolean isEnabledByDefault()
		{
			return true;
		}

		@Override
		public void disposeWidget(@Nonnull StatusBarWidget widget)
		{
			Disposer.dispose(widget);
		}

		@Override
		public boolean canBeEnabledOn(@Nonnull StatusBar statusBar)
		{
			return true;
		}
	}

	private final GitVcsSettings mySettings;

	public GitBranchWidget(@Nonnull Project project)
	{
		super(project, "Git");
		mySettings = GitVcsSettings.getInstance(project);
		project.getMessageBus().connect().subscribe(GitRepository.GIT_REPO_CHANGE, repository -> updateLater());
	}

	@Override
	public StatusBarWidget copy()
	{
		return new GitBranchWidget(ObjectUtil.assertNotNull(getProject()));
	}

	@Nullable
	@Override
	protected GitRepository guessCurrentRepository(@Nonnull Project project)
	{
		return GitBranchUtil.getCurrentRepository(project);
	}

	@Nonnull
	@Override
	protected String getFullBranchName(@Nonnull GitRepository repository)
	{
		return GitBranchUtil.getDisplayableBranchText(repository);
	}

	@Override
	protected boolean isMultiRoot(@Nonnull Project project)
	{
		return !GitUtil.justOneGitRepository(project);
	}

	@Nonnull
	@Override
	protected ListPopup getPopup(@Nonnull Project project, @Nonnull GitRepository repository)
	{
		return GitBranchPopup.getInstance(project, repository).asListPopup();
	}

	@Override
	protected void rememberRecentRoot(@Nonnull String path)
	{
		mySettings.setRecentRoot(path);
	}

	@Nonnull
	@Override
	public String ID()
	{
		return ID;
	}
}
