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
package git4idea.util;

import static com.intellij.openapi.util.text.StringUtil.join;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import consulo.logging.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Clock;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsNotifier;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.text.DateFormatUtil;
import git4idea.commands.Git;
import git4idea.config.GitVcsSettings;
import git4idea.merge.GitConflictResolver;
import git4idea.stash.GitChangesSaver;

/**
 * Executes a Git operation on a number of repositories surrounding it by stash-unstash procedure.
 * I.e. stashes changes, executes the operation and then unstashes it.
 */
public class GitPreservingProcess
{

	private static final Logger LOG = Logger.getInstance(GitPreservingProcess.class);

	@Nonnull
	private final Project myProject;
	@Nonnull
	private final Git myGit;
	@Nonnull
	private final Collection<VirtualFile> myRootsToSave;
	@Nonnull
	private final String myOperationTitle;
	@Nonnull
	private final String myDestinationName;
	@Nonnull
	private final ProgressIndicator myProgressIndicator;
	@Nonnull
	private final Runnable myOperation;
	@Nonnull
	private final String myStashMessage;
	@Nonnull
	private final GitChangesSaver mySaver;

	@Nonnull
	private final AtomicBoolean myLoaded = new AtomicBoolean();

	public GitPreservingProcess(@Nonnull Project project,
			@Nonnull Git git,
			@Nonnull Collection<VirtualFile> rootsToSave,
			@Nonnull String operationTitle,
			@Nonnull String destinationName,
			@Nonnull GitVcsSettings.UpdateChangesPolicy saveMethod,
			@Nonnull ProgressIndicator indicator,
			@Nonnull Runnable operation)
	{
		myProject = project;
		myGit = git;
		myRootsToSave = rootsToSave;
		myOperationTitle = operationTitle;
		myDestinationName = destinationName;
		myProgressIndicator = indicator;
		myOperation = operation;
		myStashMessage = String.format("Uncommitted changes before %s at %s", StringUtil.capitalize(myOperationTitle), DateFormatUtil.formatDateTime(Clock.getTime()));
		mySaver = configureSaver(saveMethod);
	}

	public void execute()
	{
		execute(null);
	}

	public void execute(@Nullable final Computable<Boolean> autoLoadDecision)
	{
		Runnable operation = new Runnable()
		{
			@Override
			public void run()
			{
				LOG.debug("starting");
				boolean savedSuccessfully = save();
				LOG.debug("save result: " + savedSuccessfully);
				if(savedSuccessfully)
				{
					try
					{
						LOG.debug("running operation");
						myOperation.run();
						LOG.debug("operation completed.");
					}
					finally
					{
						if(autoLoadDecision == null || autoLoadDecision.compute())
						{
							LOG.debug("loading");
							load();
						}
						else
						{
							mySaver.notifyLocalChangesAreNotRestored();
						}
					}
				}
				LOG.debug("finished.");
			}
		};

		new GitFreezingProcess(myProject, myOperationTitle, operation).execute();
	}

	/**
	 * Configures the saver: i.e. notifications and texts for the GitConflictResolver used inside.
	 */
	@Nonnull
	private GitChangesSaver configureSaver(@Nonnull GitVcsSettings.UpdateChangesPolicy saveMethod)
	{
		GitChangesSaver saver = GitChangesSaver.getSaver(myProject, myGit, myProgressIndicator, myStashMessage, saveMethod);
		MergeDialogCustomizer mergeDialogCustomizer = new MergeDialogCustomizer()
		{
			@Override
			public String getMultipleFileMergeDescription(@Nonnull Collection<VirtualFile> files)
			{
				return String.format("<html>Uncommitted changes that were saved before %s have conflicts with files from <code>%s</code></html>", myOperationTitle, myDestinationName);
			}

			@Override
			public String getLeftPanelTitle(@Nonnull VirtualFile file)
			{
				return "Uncommitted changes from stash";
			}

			@Override
			public String getRightPanelTitle(@Nonnull VirtualFile file, VcsRevisionNumber revisionNumber)
			{
				return String.format("<html>Changes from <b><code>%s</code></b></html>", myDestinationName);
			}
		};

		GitConflictResolver.Params params = new GitConflictResolver.Params().
				setReverse(true).
				setMergeDialogCustomizer(mergeDialogCustomizer).
				setErrorNotificationTitle("Local changes were not restored");

		saver.setConflictResolverParams(params);
		return saver;
	}

	/**
	 * Saves local changes. In case of error shows a notification and returns false.
	 */
	private boolean save()
	{
		try
		{
			mySaver.saveLocalChanges(myRootsToSave);
			return true;
		}
		catch(VcsException e)
		{
			LOG.info("Couldn't save local changes", e);
			VcsNotifier.getInstance(myProject).notifyError("Couldn't save uncommitted changes.", String.format("Tried to save uncommitted changes in stash before %s, " +
					"but failed with an error.<br/>%s", myOperationTitle, join(e.getMessages())));
			return false;
		}
	}

	public void load()
	{
		if(myLoaded.compareAndSet(false, true))
		{
			mySaver.load();
		}
		else
		{
			LOG.warn("The changes were already loaded", new Throwable());
		}
	}
}
