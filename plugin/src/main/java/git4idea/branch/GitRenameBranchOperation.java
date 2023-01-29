/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

import consulo.project.Project;
import consulo.versionControlSystem.VcsNotifier;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitCompoundResult;
import git4idea.repo.GitRepository;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;

public class GitRenameBranchOperation extends GitBranchOperation
{
	@Nonnull
	private final VcsNotifier myNotifier;
	@Nonnull
	private final String myCurrentName;
	@Nonnull
	private final String myNewName;

	public GitRenameBranchOperation(@Nonnull Project project,
			@Nonnull Git git,
			@Nonnull GitBranchUiHandler uiHandler,
			@Nonnull String currentName,
			@Nonnull String newName,
			@Nonnull List<GitRepository> repositories)
	{
		super(project, git, uiHandler, repositories);
		myCurrentName = currentName;
		myNewName = newName;
		myNotifier = VcsNotifier.getInstance(myProject);
	}

	@Override
	protected void execute()
	{
		while(hasMoreRepositories())
		{
			GitRepository repository = next();
			GitCommandResult result = myGit.renameBranch(repository, myCurrentName, myNewName);
			if(result.success())
			{
				refresh(repository);
				markSuccessful(repository);
			}
			else
			{
				fatalError("Couldn't rename " + myCurrentName + " to " + myNewName, result.getErrorOutputAsJoinedString());
				return;
			}
		}
		notifySuccess();
	}

	@Override
	protected void rollback()
	{
		GitCompoundResult result = new GitCompoundResult(myProject);
		Collection<GitRepository> repositories = getSuccessfulRepositories();
		for(GitRepository repository : repositories)
		{
			result.append(repository, myGit.renameBranch(repository, myNewName, myCurrentName));
			refresh(repository);
		}
		if(result.totalSuccess())
		{
			myNotifier.notifySuccess("Rollback Successful", "Renamed back to " + myCurrentName);
		}
		else
		{
			myNotifier.notifyError("Rollback Failed", result.getErrorOutputWithReposIndication());
		}
	}

	@Nonnull
	@Override
	public String getSuccessMessage()
	{
		return String.format("Branch <b><code>%s</code></b> was renamed to <b><code>%s</code></b>", myCurrentName, myNewName);
	}

	@Nonnull
	@Override
	protected String getRollbackProposal()
	{
		return "However rename has succeeded for the following " + repositories() + ":<br/>" +
				successfulRepositoriesJoined() +
				"<br/>You may rollback (rename branch back to " + myCurrentName + ") not to let branches diverge.";
	}

	@Nonnull
	@Override
	protected String getOperationName()
	{
		return "rename";
	}

	private static void refresh(@Nonnull GitRepository repository)
	{
		repository.update();
	}
}
