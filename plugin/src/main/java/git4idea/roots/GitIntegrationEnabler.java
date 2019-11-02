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
package git4idea.roots;

import javax.annotation.Nonnull;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vcs.roots.VcsIntegrationEnabler;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;

public class GitIntegrationEnabler extends VcsIntegrationEnabler<GitVcs>
{

	private final
	@Nonnull
	Git myGit;

	private static final Logger LOG = Logger.getInstance(GitIntegrationEnabler.class);

	public GitIntegrationEnabler(@Nonnull GitVcs vcs, @Nonnull Git git)
	{
		super(vcs);
		myGit = git;
	}

	@Override
	protected boolean initOrNotifyError(@Nonnull final VirtualFile projectDir)
	{
		VcsNotifier vcsNotifier = VcsNotifier.getInstance(myProject);
		GitCommandResult result = myGit.init(myProject, projectDir);
		if(result.success())
		{
			refreshVcsDir(projectDir, GitUtil.DOT_GIT);
			vcsNotifier.notifySuccess("Created Git repository in " + projectDir.getPresentableUrl());
			return true;
		}
		else
		{
			if(myVcs.getExecutableValidator().checkExecutableAndNotifyIfNeeded())
			{
				vcsNotifier.notifyError("Couldn't git init " + projectDir.getPresentableUrl(), result.getErrorOutputAsHtmlString());
				LOG.info(result.getErrorOutputAsHtmlString());
			}
			return false;
		}
	}

}
