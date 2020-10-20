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
package git4idea.branch;

import java.util.List;

import javax.annotation.Nonnull;
import jakarta.inject.Singleton;

import javax.annotation.Nullable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.repo.GitRepository;

/**
 * @author Kirill Likhodedov
 */
@Singleton
class GitBrancherImpl implements GitBrancher
{


	@Nonnull
	private final Project myProject;
	@Nonnull
	private final Git myGit;

	GitBrancherImpl(@Nonnull Project project, @Nonnull Git git)
	{
		myProject = project;
		myGit = git;
	}

	@Override
	public void checkoutNewBranch(@Nonnull final String name, @Nonnull final List<GitRepository> repositories)
	{
		new CommonBackgroundTask(myProject, "Checking out new branch " + name, null)
		{
			@Override
			public void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).checkoutNewBranch(name, repositories);
			}
		}.runInBackground();
	}

	private GitBranchWorker newWorker(ProgressIndicator indicator)
	{
		return new GitBranchWorker(myProject, myGit, new GitBranchUiHandlerImpl(myProject, myGit, indicator));
	}

	@Override
	public void createNewTag(@Nonnull final String name, @Nonnull final String reference, @Nonnull final List<GitRepository> repositories, @Nullable Runnable callInAwtLater)
	{
		new CommonBackgroundTask(myProject, "Checking out new branch " + name, callInAwtLater)
		{
			@Override
			public void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).createNewTag(name, reference, repositories);
			}
		}.runInBackground();
	}

	@Override
	public void checkout(@Nonnull final String reference, final boolean detach, @Nonnull final List<GitRepository> repositories, @Nullable Runnable callInAwtLater)
	{
		new CommonBackgroundTask(myProject, "Checking out " + reference, callInAwtLater)
		{
			@Override
			public void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).checkout(reference, detach, repositories);
			}
		}.runInBackground();
	}

	@Override
	public void checkoutNewBranchStartingFrom(@Nonnull final String newBranchName, @Nonnull final String startPoint, @Nonnull final List<GitRepository> repositories,
											  @Nullable Runnable callInAwtLater)
	{
		new CommonBackgroundTask(myProject, String.format("Checking out %s from %s", newBranchName, startPoint), callInAwtLater)
		{
			@Override
			public void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).checkoutNewBranchStartingFrom(newBranchName, startPoint, repositories);
			}
		}.runInBackground();
	}

	@Override
	public void deleteBranch(@Nonnull final String branchName, @Nonnull final List<GitRepository> repositories)
	{
		new CommonBackgroundTask(myProject, "Deleting " + branchName, null)
		{
			@Override
			public void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).deleteBranch(branchName, repositories);
			}
		}.runInBackground();
	}

	@Override
	public void deleteRemoteBranch(@Nonnull final String branchName, @Nonnull final List<GitRepository> repositories)
	{
		new CommonBackgroundTask(myProject, "Deleting " + branchName, null)
		{
			@Override
			public void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).deleteRemoteBranch(branchName, repositories);
			}
		}.runInBackground();
	}

	@Override
	public void compare(@Nonnull final String branchName, @Nonnull final List<GitRepository> repositories, @Nonnull final GitRepository selectedRepository)
	{
		new CommonBackgroundTask(myProject, "Comparing with " + branchName, null)
		{
			@Override
			public void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).compare(branchName, repositories, selectedRepository);
			}
		}.runInBackground();

	}

	@Override
	public void merge(@Nonnull final String branchName, @Nonnull final DeleteOnMergeOption deleteOnMerge, @Nonnull final List<GitRepository> repositories)
	{
		new CommonBackgroundTask(myProject, "Merging " + branchName, null)
		{
			@Override
			public void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).merge(branchName, deleteOnMerge, repositories);
			}
		}.runInBackground();
	}

	@Override
	public void rebase(@Nonnull final List<GitRepository> repositories, @Nonnull final String branchName)
	{
		new CommonBackgroundTask(myProject, "Rebasing onto " + branchName, null)
		{
			@Override
			void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).rebase(repositories, branchName);
			}
		}.runInBackground();
	}

	@Override
	public void rebaseOnCurrent(@Nonnull final List<GitRepository> repositories, @Nonnull final String branchName)
	{
		new CommonBackgroundTask(myProject, "Rebasing " + branchName + "...", null)
		{
			@Override
			void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).rebaseOnCurrent(repositories, branchName);
			}
		}.runInBackground();
	}

	@Override
	public void renameBranch(@Nonnull final String currentName, @Nonnull final String newName, @Nonnull final List<GitRepository> repositories)
	{
		new CommonBackgroundTask(myProject, "Renaming " + currentName + " to " + newName + "...", null)
		{
			@Override
			void execute(@Nonnull ProgressIndicator indicator)
			{
				newWorker(indicator).renameBranch(currentName, newName, repositories);
			}
		}.runInBackground();
	}

	/**
	 * Executes common operations before/after executing the actual branch operation.
	 */
	private static abstract class CommonBackgroundTask extends Task.Backgroundable
	{

		@Nullable
		private final Runnable myCallInAwtAfterExecution;

		private CommonBackgroundTask(@Nullable final Project project, @Nonnull final String title, @Nullable Runnable callInAwtAfterExecution)
		{
			super(project, title);
			myCallInAwtAfterExecution = callInAwtAfterExecution;
		}

		@Override
		public final void run(@Nonnull ProgressIndicator indicator)
		{
			execute(indicator);
			if(myCallInAwtAfterExecution != null)
			{
				Application application = ApplicationManager.getApplication();
				if(application.isUnitTestMode())
				{
					myCallInAwtAfterExecution.run();
				}
				else
				{
					application.invokeLater(myCallInAwtAfterExecution, application.getDefaultModalityState());
				}
			}
		}

		abstract void execute(@Nonnull ProgressIndicator indicator);

		void runInBackground()
		{
			GitVcs.runInBackground(this);
		}

	}

}
