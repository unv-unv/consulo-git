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
package git4idea;

import javax.annotation.Nonnull;
import javax.inject.Singleton;

import com.intellij.dvcs.DvcsPlatformFacadeImpl;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepositoryManager;

/**
 * @author Kirill Likhodedov
 */
@Singleton
class GitPlatformFacadeImpl extends DvcsPlatformFacadeImpl implements GitPlatformFacade
{

	@Nonnull
	@Override
	public AbstractVcs getVcs(@Nonnull Project project)
	{
		return ProjectLevelVcsManager.getInstance(project).findVcsByName(GitVcs.NAME);
	}

	@Nonnull
	@Override
	public GitRepositoryManager getRepositoryManager(@Nonnull Project project)
	{
		return ServiceManager.getService(project, GitRepositoryManager.class);
	}

	@Nonnull
	@Override
	public GitVcsSettings getSettings(@Nonnull Project project)
	{
		return GitVcsSettings.getInstance(project);
	}
}
