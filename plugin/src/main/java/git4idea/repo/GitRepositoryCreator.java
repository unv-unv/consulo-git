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
package git4idea.repo;

import javax.annotation.Nonnull;

import com.intellij.dvcs.repo.Repository;
import com.intellij.dvcs.repo.VcsRepositoryCreator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;

import javax.annotation.Nullable;

public class GitRepositoryCreator extends VcsRepositoryCreator
{
	@Nonnull
	private final Project myProject;

	public GitRepositoryCreator(@Nonnull Project project)
	{
		myProject = project;
	}

	@Override
	@Nullable
	public Repository createRepositoryIfValid(@Nonnull VirtualFile root)
	{
		VirtualFile gitDir = GitUtil.findGitDir(root);
		return gitDir == null ? null : GitRepositoryImpl.getInstance(root, gitDir, myProject, true);
	}

	@Nonnull
	@Override
	public VcsKey getVcsKey()
	{
		return GitVcs.getKey();
	}
}
