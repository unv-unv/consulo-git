/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package git4idea.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import consulo.disposer.Disposable;
import consulo.git.config.GitVcsPanel;
import consulo.ui.Component;
import consulo.ui.annotation.RequiredUIAccess;
import git4idea.GitVcs;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class GitVcsConfigurable implements Configurable
{
	public static final String DISPLAY_NAME = GitVcs.NAME;

	private final Project myProject;
	private final GitVcsSettings mySettings;
	@Nonnull
	private final GitSharedSettings mySharedSettings;
	private GitVcsPanel panel;

	public GitVcsConfigurable(@Nonnull Project project, @Nonnull GitVcsSettings settings, @Nonnull GitSharedSettings sharedSettings)
	{
		myProject = project;
		mySettings = settings;
		mySharedSettings = sharedSettings;
	}

	@Nonnull
	@Override
	public String getDisplayName()
	{
		return DISPLAY_NAME;
	}

	@Nullable
	@Override
	public String getHelpTopic()
	{
		return "project.propVCSSupport.VCSs.Git";
	}

	@RequiredUIAccess
	@Nonnull
	@Override
	public Component createUIComponent(@Nonnull Disposable uiDisposable)
	{
		if(panel == null)
		{
			panel = new GitVcsPanel(myProject, uiDisposable);
			panel.load(mySettings, mySharedSettings);
		}
		return panel.getPanel();
	}

	@RequiredUIAccess
	@Override
	public boolean isModified()
	{
		return panel.isModified(mySettings, mySharedSettings);
	}

	@RequiredUIAccess
	@Override
	public void apply() throws ConfigurationException
	{
		panel.save(mySettings, mySharedSettings);
	}

	@RequiredUIAccess
	@Override
	public void reset()
	{
		panel.load(mySettings, mySharedSettings);
	}
}