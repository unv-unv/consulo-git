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
package git4idea.update;

import static git4idea.GitUtil.getRepositoriesFromRoots;
import static git4idea.GitUtil.getRepositoryManager;
import static git4idea.GitUtil.gitRoots;
import static git4idea.GitUtil.isUnderGit;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import javax.annotation.Nonnull;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.update.SequentialUpdatesContext;
import com.intellij.openapi.vcs.update.UpdateEnvironment;
import com.intellij.openapi.vcs.update.UpdateSession;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepositoryManager;

import javax.annotation.Nullable;

public class GitUpdateEnvironment implements UpdateEnvironment
{
	private final Project myProject;
	private final GitVcsSettings mySettings;

	public GitUpdateEnvironment(@Nonnull Project project, @Nonnull GitVcsSettings settings)
	{
		myProject = project;
		mySettings = settings;
	}

	public void fillGroups(UpdatedFiles updatedFiles)
	{
		//unused, there are no custom categories yet
	}

	@Nonnull
	public UpdateSession updateDirectories(@Nonnull FilePath[] filePaths,
			UpdatedFiles updatedFiles,
			ProgressIndicator progressIndicator,
			@Nonnull Ref<SequentialUpdatesContext> sequentialUpdatesContextRef) throws ProcessCanceledException
	{
		Set<VirtualFile> roots = gitRoots(Arrays.asList(filePaths));
		GitRepositoryManager repositoryManager = getRepositoryManager(myProject);
		final GitUpdateProcess gitUpdateProcess = new GitUpdateProcess(myProject, progressIndicator, getRepositoriesFromRoots(repositoryManager, roots), updatedFiles, true, true);
		boolean result = gitUpdateProcess.update(mySettings.getUpdateType()).isSuccess();
		return new GitUpdateSession(result);
	}


	public boolean validateOptions(Collection<FilePath> filePaths)
	{
		for(FilePath p : filePaths)
		{
			if(!isUnderGit(p))
			{
				return false;
			}
		}
		return true;
	}

	@Nullable
	public Configurable createConfigurable(Collection<FilePath> files)
	{
		return new GitUpdateConfigurable(mySettings);
	}
}
