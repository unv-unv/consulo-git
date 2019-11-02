/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package git4idea.commands;

import static java.util.Collections.singleton;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.inject.Singleton;

import javax.annotation.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Function;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcsUtil.VcsFileUtil;
import git4idea.GitVcs;
import git4idea.branch.GitRebaseParams;
import git4idea.config.GitVersionSpecialty;
import git4idea.rebase.GitInteractiveRebaseEditorHandler;
import git4idea.rebase.GitRebaseEditorService;
import git4idea.rebase.GitRebaseResumeMode;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.reset.GitResetMode;

/**
 * Easy-to-use wrapper of common native Git commands.
 * Most of them return result as {@link GitCommandResult}.
 *
 * @author Kirill Likhodedov
 */
@Singleton
@SuppressWarnings("StringToUpperCaseOrToLowerCaseWithoutLocale")
public class GitImpl implements Git
{

	private static final Logger LOG = Logger.getInstance(Git.class);

	public GitImpl()
	{
	}

	/**
	 * Calls 'git init' on the specified directory.
	 */
	@Nonnull
	@Override
	public GitCommandResult init(@Nonnull Project project, @Nonnull VirtualFile root, @Nonnull GitLineHandlerListener... listeners)
	{
		GitLineHandler h = new GitLineHandler(project, root, GitCommand.INIT);
		for(GitLineHandlerListener listener : listeners)
		{
			h.addLineListener(listener);
		}
		h.setSilent(false);
		h.setStdoutSuppressed(false);
		return run(h);
	}

	/**
	 * <p>Queries Git for the unversioned files in the given paths. </p>
	 * <p>Ignored files are left ignored, i. e. no information is returned about them (thus this method may also be used as a
	 * ignored files checker.</p>
	 *
	 * @param files files that are to be checked for the unversioned files among them.
	 *              <b>Pass <code>null</code> to query the whole repository.</b>
	 * @return Unversioned not ignored files from the given scope.
	 */
	@Override
	@Nonnull
	public Set<VirtualFile> untrackedFiles(@Nonnull Project project, @Nonnull VirtualFile root, @Nullable Collection<VirtualFile> files) throws VcsException
	{
		final Set<VirtualFile> untrackedFiles = new HashSet<>();

		if(files == null)
		{
			untrackedFiles.addAll(untrackedFilesNoChunk(project, root, null));
		}
		else
		{
			for(List<String> relativePaths : VcsFileUtil.chunkFiles(root, files))
			{
				untrackedFiles.addAll(untrackedFilesNoChunk(project, root, relativePaths));
			}
		}

		return untrackedFiles;
	}

	// relativePaths are guaranteed to fit into command line length limitations.
	@Override
	@Nonnull
	public Collection<VirtualFile> untrackedFilesNoChunk(@Nonnull Project project, @Nonnull VirtualFile root, @Nullable List<String> relativePaths) throws VcsException
	{
		final Set<VirtualFile> untrackedFiles = new HashSet<>();
		GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.LS_FILES);
		h.setSilent(true);
		h.addParameters("--exclude-standard", "--others", "-z");
		h.endOptions();
		if(relativePaths != null)
		{
			h.addParameters(relativePaths);
		}

		final String output = h.run();
		if(StringUtil.isEmptyOrSpaces(output))
		{
			return untrackedFiles;
		}

		for(String relPath : output.split("\u0000"))
		{
			VirtualFile f = root.findFileByRelativePath(relPath);
			if(f == null)
			{
				// files was created on disk, but VirtualFile hasn't yet been created,
				// when the GitChangeProvider has already been requested about changes.
				LOG.info(String.format("VirtualFile for path [%s] is null", relPath));
			}
			else
			{
				untrackedFiles.add(f);
			}
		}

		return untrackedFiles;
	}

	@Override
	@Nonnull
	public GitCommandResult clone(@Nonnull final Project project,
			@Nonnull final File parentDirectory,
			@Nonnull final String url,
			@Nullable final String puttyKey,
			@Nonnull final String clonedDirectoryName,
			@Nonnull final GitLineHandlerListener... listeners)
	{
		return run(() ->
		{
			GitLineHandler handler = new GitLineHandler(project, parentDirectory, GitCommand.CLONE);
			handler.setStdoutSuppressed(false);
			handler.setUrl(url);
			handler.addParameters("--progress");
			handler.addParameters(url);
			handler.setPuttyKey(puttyKey);
			handler.endOptions();
			handler.addParameters(clonedDirectoryName);
			addListeners(handler, listeners);
			return handler;
		});
	}

	@Nonnull
	@Override
	public GitCommandResult config(@Nonnull GitRepository repository, String... params)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.CONFIG);
		h.addParameters(params);
		return run(h);
	}

	@Nonnull
	@Override
	public GitCommandResult diff(@Nonnull GitRepository repository, @Nonnull List<String> parameters, @Nonnull String range)
	{
		final GitLineHandler diff = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.DIFF);
		diff.addParameters(parameters);
		diff.addParameters(range);
		diff.setStdoutSuppressed(true);
		diff.setStderrSuppressed(true);
		diff.setSilent(true);
		return run(diff);
	}

	@Nonnull
	@Override
	public GitCommandResult checkAttr(@Nonnull final GitRepository repository, @Nonnull final Collection<String> attributes, @Nonnull Collection<VirtualFile> files)
	{
		List<List<String>> listOfPaths = VcsFileUtil.chunkFiles(repository.getRoot(), files);
		return runAll(ContainerUtil.map(listOfPaths, (Function<List<String>, Computable<GitCommandResult>>) relativePaths -> () ->
		{
			final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.CHECK_ATTR);
			h.addParameters(new ArrayList<>(attributes));
			h.endOptions();
			h.addParameters(relativePaths);
			return run(h);
		}));
	}

	@Nonnull
	@Override
	public GitCommandResult stashSave(@Nonnull GitRepository repository, @Nonnull String message)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.STASH);
		h.addParameters("save");
		h.addParameters(message);
		return run(h);
	}

	@Nonnull
	@Override
	public GitCommandResult stashPop(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners)
	{
		final GitLineHandler handler = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.STASH);
		handler.addParameters("pop");
		addListeners(handler, listeners);
		return run(handler);
	}

	@Override
	@Nonnull
	public GitCommandResult merge(@Nonnull GitRepository repository, @Nonnull String branchToMerge, @Nullable List<String> additionalParams, @Nonnull GitLineHandlerListener... listeners)
	{
		final GitLineHandler mergeHandler = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.MERGE);
		mergeHandler.setSilent(false);
		mergeHandler.addParameters(branchToMerge);
		if(additionalParams != null)
		{
			mergeHandler.addParameters(additionalParams);
		}
		for(GitLineHandlerListener listener : listeners)
		{
			mergeHandler.addLineListener(listener);
		}
		return run(mergeHandler);
	}


	/**
	 * {@code git checkout &lt;reference&gt;} <br/>
	 * {@code git checkout -b &lt;newBranch&gt; &lt;reference&gt;}
	 */
	@Nonnull
	@Override
	public GitCommandResult checkout(@Nonnull GitRepository repository,
			@Nonnull String reference,
			@Nullable String newBranch,
			boolean force,
			boolean detach,
			@Nonnull GitLineHandlerListener... listeners)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.CHECKOUT);
		h.setSilent(false);
		h.setStdoutSuppressed(false);
		if(force)
		{
			h.addParameters("--force");
		}
		if(newBranch == null)
		{ // simply checkout
			h.addParameters(detach ? reference + "^0" : reference); // we could use `--detach` here, but it is supported only since 1.7.5.
		}
		else
		{ // checkout reference as new branch
			h.addParameters("-b", newBranch, reference);
		}
		h.endOptions();
		for(GitLineHandlerListener listener : listeners)
		{
			h.addLineListener(listener);
		}
		return run(h);
	}

	/**
	 * {@code git checkout -b &lt;branchName&gt;}
	 */
	@Nonnull
	@Override
	public GitCommandResult checkoutNewBranch(@Nonnull GitRepository repository, @Nonnull String branchName, @Nullable GitLineHandlerListener listener)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.CHECKOUT.readLockingCommand());
		h.setSilent(false);
		h.setStdoutSuppressed(false);
		h.addParameters("-b");
		h.addParameters(branchName);
		if(listener != null)
		{
			h.addLineListener(listener);
		}
		return run(h);
	}

	@Nonnull
	@Override
	public GitCommandResult createNewTag(@Nonnull GitRepository repository, @Nonnull String tagName, @Nullable GitLineHandlerListener listener, @Nonnull String reference)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.TAG);
		h.setSilent(false);
		h.addParameters(tagName);
		if(!reference.isEmpty())
		{
			h.addParameters(reference);
		}
		if(listener != null)
		{
			h.addLineListener(listener);
		}
		return run(h);
	}

	/**
	 * {@code git branch -d <reference>} or {@code git branch -D <reference>}
	 */
	@Nonnull
	@Override
	public GitCommandResult branchDelete(@Nonnull GitRepository repository, @Nonnull String branchName, boolean force, @Nonnull GitLineHandlerListener... listeners)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.BRANCH);
		h.setSilent(false);
		h.setStdoutSuppressed(false);
		h.addParameters(force ? "-D" : "-d");
		h.addParameters(branchName);
		for(GitLineHandlerListener listener : listeners)
		{
			h.addLineListener(listener);
		}
		return run(h);
	}

	/**
	 * Get branches containing the commit.
	 * {@code git branch --contains <commit>}
	 */
	@Override
	@Nonnull
	public GitCommandResult branchContains(@Nonnull GitRepository repository, @Nonnull String commit)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.BRANCH);
		h.addParameters("--contains", commit);
		return run(h);
	}

	@Override
	@Nonnull
	public GitCommandResult branchCreate(@Nonnull GitRepository repository, @Nonnull String branchName, @Nonnull String startPoint)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.BRANCH);
		h.setStdoutSuppressed(false);
		h.addParameters(branchName);
		h.addParameters(startPoint);
		return run(h);
	}

	@Nonnull
	@Override
	public GitCommandResult renameBranch(@Nonnull GitRepository repository, @Nonnull String currentName, @Nonnull String newName, @Nonnull GitLineHandlerListener... listeners)
	{
		GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.BRANCH);
		h.setStdoutSuppressed(false);
		h.addParameters("-m", currentName, newName);
		return run(h);
	}

	@Override
	@Nonnull
	public GitCommandResult reset(@Nonnull GitRepository repository, @Nonnull GitResetMode mode, @Nonnull String target, @Nonnull GitLineHandlerListener... listeners)
	{
		return reset(repository, mode.getArgument(), target, listeners);
	}

	@Override
	@Nonnull
	public GitCommandResult resetMerge(@Nonnull GitRepository repository, @Nullable String revision)
	{
		return reset(repository, "--merge", revision);
	}

	@Nonnull
	private static GitCommandResult reset(@Nonnull GitRepository repository, @Nonnull String argument, @Nullable String target, @Nonnull GitLineHandlerListener... listeners)
	{
		final GitLineHandler handler = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.RESET);
		handler.addParameters(argument);
		if(target != null)
		{
			handler.addParameters(target);
		}
		addListeners(handler, listeners);
		return run(handler);
	}

	/**
	 * Returns the last (tip) commit on the given branch.<br/>
	 * {@code git rev-list -1 <branchName>}
	 */
	@Nonnull
	@Override
	public GitCommandResult tip(@Nonnull GitRepository repository, @Nonnull String branchName)
	{
		final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.REV_LIST);
		h.addParameters("-1");
		h.addParameters(branchName);
		return run(h);
	}

	@Override
	@Nonnull
	public GitCommandResult push(@Nonnull GitRepository repository,
			@Nonnull String remote,
			@Nullable String url,
			@Nullable String puttyKey,
			@Nonnull String spec,
			boolean updateTracking,
			@Nonnull GitLineHandlerListener... listeners)
	{
		return doPush(repository, remote, puttyKey, singleton(url), spec, false, updateTracking, false, null, listeners);
	}

	@Override
	@Nonnull
	public GitCommandResult push(@Nonnull GitRepository repository,
			@Nonnull GitRemote remote,
			@Nonnull String spec,
			boolean force,
			boolean updateTracking,
			boolean skipHook,
			@Nullable String tagMode,
			GitLineHandlerListener... listeners)
	{
		return doPush(repository, remote.getName(), remote.getPuttyKeyFile(), remote.getPushUrls(), spec, force, updateTracking, skipHook, tagMode, listeners);
	}

	@Nonnull
	private GitCommandResult doPush(@Nonnull final GitRepository repository,
			@Nonnull final String remoteName,
			@Nullable String puttyKey,
			@Nonnull final Collection<String> remoteUrls,
			@Nonnull final String spec,
			final boolean force,
			final boolean updateTracking,
			boolean skipHook,
			@Nullable final String tagMode,
			@Nonnull final GitLineHandlerListener... listeners)
	{
		return runCommand(() ->
		{
			final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.PUSH);
			h.setUrls(remoteUrls);
			h.setPuttyKey(puttyKey);
			h.setSilent(false);
			h.setStdoutSuppressed(false);
			addListeners(h, listeners);
			h.addProgressParameter();
			h.addParameters("--porcelain");
			h.addParameters(remoteName);
			h.addParameters(spec);
			if(updateTracking)
			{
				h.addParameters("--set-upstream");
			}
			if(force)
			{
				h.addParameters("--force");
			}
			if(tagMode != null)
			{
				h.addParameters(tagMode);
			}
			if(skipHook)
			{
				h.addParameters("--no-verify");
			}
			return h;
		});
	}

	@Nonnull
	@Override
	public GitCommandResult show(@Nonnull GitRepository repository, @Nonnull String... params)
	{
		final GitLineHandler handler = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.SHOW);
		handler.addParameters(params);
		return run(handler);
	}

	@Override
	@Nonnull
	public GitCommandResult cherryPick(@Nonnull GitRepository repository, @Nonnull String hash, boolean autoCommit, @Nonnull GitLineHandlerListener... listeners)
	{
		final GitLineHandler handler = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.CHERRY_PICK);
		handler.addParameters("-x");
		if(!autoCommit)
		{
			handler.addParameters("-n");
		}
		handler.addParameters(hash);
		addListeners(handler, listeners);
		handler.setSilent(false);
		handler.setStdoutSuppressed(false);
		return run(handler);
	}

	@Nonnull
	@Override
	public GitCommandResult getUnmergedFiles(@Nonnull GitRepository repository)
	{
		GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.LS_FILES);
		h.addParameters("--unmerged");
		h.setSilent(true);
		return run(h);
	}

	/**
	 * Fetch remote branch
	 * {@code git fetch <remote> <params>}
	 */
	@Override
	@Nonnull
	public GitCommandResult fetch(@Nonnull final GitRepository repository, @Nonnull final GitRemote remote, @Nonnull final List<GitLineHandlerListener> listeners, final String... params)
	{
		return runCommand(() ->
		{
			final GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.FETCH);
			h.setSilent(false);
			h.setStdoutSuppressed(false);
			h.setUrls(remote.getUrls());
			h.setPuttyKey(remote.getPuttyKeyFile());
			h.addParameters(remote.getName());
			h.addParameters(params);
			h.addProgressParameter();
			GitVcs vcs = GitVcs.getInstance(repository.getProject());
			if(vcs != null && GitVersionSpecialty.SUPPORTS_FETCH_PRUNE.existsIn(vcs.getVersion()))
			{
				h.addParameters("--prune");
			}
			addListeners(h, listeners);
			return h;
		});
	}

	@Nonnull
	@Override
	public GitCommandResult addRemote(@Nonnull GitRepository repository, @Nonnull String name, @Nonnull String url)
	{
		GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.REMOTE);
		h.addParameters("add", name, url);
		return run(h);
	}

	@Nonnull
	@Override
	public GitCommandResult lsRemote(@Nonnull final Project project, @Nonnull final File workingDir, @Nonnull final String url)
	{
		return doLsRemote(project, workingDir, url, singleton(url));
	}

	@Nonnull
	@Override
	public GitCommandResult lsRemote(@Nonnull Project project, @Nonnull VirtualFile workingDir, @Nonnull GitRemote remote, String... additionalParameters)
	{
		return doLsRemote(project, VfsUtilCore.virtualToIoFile(workingDir), remote.getName(), remote.getUrls(), additionalParameters);
	}

	@Nonnull
	@Override
	public GitCommandResult remotePrune(@Nonnull final GitRepository repository, @Nonnull final GitRemote remote)
	{
		return run(() ->
		{
			GitLineHandler h = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.REMOTE.writeLockingCommand());
			h.setStdoutSuppressed(false);
			h.addParameters("prune");
			h.addParameters(remote.getName());
			h.setUrls(remote.getUrls());
			return h;
		});
	}

	@Nonnull
	@Override
	public GitCommandResult rebase(@Nonnull GitRepository repository, @Nonnull GitRebaseParams parameters, @Nonnull GitLineHandlerListener... listeners)
	{
		Project project = repository.getProject();
		VirtualFile root = repository.getRoot();
		GitLineHandler handler = new GitLineHandler(project, root, GitCommand.REBASE);
		handler.addParameters(parameters.asCommandLineArguments());
		addListeners(handler, listeners);
		return parameters.isInteractive() ? runWithEditor(project, root, handler, true) : run(handler);
	}

	@Nonnull
	@Override
	public GitCommandResult rebaseAbort(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners)
	{
		GitLineHandler handler = new GitLineHandler(repository.getProject(), repository.getRoot(), GitCommand.REBASE);
		handler.addParameters("--abort");
		addListeners(handler, listeners);
		return run(handler);
	}

	@Nonnull
	@Override
	public GitCommandResult rebaseContinue(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners)
	{
		return rebaseResume(repository, GitRebaseResumeMode.CONTINUE, listeners);
	}

	@Nonnull
	@Override
	public GitCommandResult rebaseSkip(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners)
	{
		return rebaseResume(repository, GitRebaseResumeMode.SKIP, listeners);
	}

	@Nonnull
	private GitCommandResult rebaseResume(@Nonnull GitRepository repository, @Nonnull GitRebaseResumeMode rebaseMode, @Nonnull GitLineHandlerListener[] listeners)
	{
		Project project = repository.getProject();
		VirtualFile root = repository.getRoot();
		GitLineHandler handler = new GitLineHandler(project, root, GitCommand.REBASE);
		handler.addParameters(rebaseMode.asCommandLineArgument());
		addListeners(handler, listeners);
		return runWithEditor(project, root, handler, false);
	}

	@Nonnull
	private GitCommandResult runWithEditor(@Nonnull Project project, @Nonnull VirtualFile root, @Nonnull GitLineHandler handler, boolean commitListAware)
	{
		GitInteractiveRebaseEditorHandler editor = configureEditor(project, root, handler, commitListAware);
		try
		{
			GitCommandResult result = run(handler);
			return editor.wasEditorCancelled() ? toCancelledResult(result) : result;
		}
		finally
		{
			editor.close();
		}
	}

	@Nonnull
	private static GitCommandResult toCancelledResult(@Nonnull GitCommandResult result)
	{
		int exitCode = result.getExitCode() == 0 ? 1 : result.getExitCode();
		return new GitCommandResult(false, exitCode, result.getErrorOutput(), result.getOutput(), result.getException())
		{
			@Override
			public boolean cancelled()
			{
				return true;
			}
		};
	}

	@VisibleForTesting
	@Nonnull
	protected GitInteractiveRebaseEditorHandler configureEditor(@Nonnull Project project, @Nonnull VirtualFile root, @Nonnull GitLineHandler handler, boolean commitListAware)
	{
		GitRebaseEditorService service = GitRebaseEditorService.getInstance();
		GitInteractiveRebaseEditorHandler editor = new GitInteractiveRebaseEditorHandler(service, project, root, handler);
		if(!commitListAware)
		{
			editor.setRebaseEditorShown();
		}
		service.configureHandler(handler, editor.getHandlerNo());
		return editor;
	}

	@Nonnull
	private static GitCommandResult doLsRemote(@Nonnull final Project project,
			@Nonnull final File workingDir,
			@Nonnull final String remoteId,
			@Nonnull final Collection<String> authenticationUrls,
			final String... additionalParameters)
	{
		return run(() ->
		{
			GitLineHandler h = new GitLineHandler(project, workingDir, GitCommand.LS_REMOTE);
			h.addParameters(additionalParameters);
			h.addParameters(remoteId);
			h.setUrls(authenticationUrls);
			return h;
		});
	}

	private static void addListeners(@Nonnull GitLineHandler handler, @Nonnull GitLineHandlerListener... listeners)
	{
		addListeners(handler, Arrays.asList(listeners));
	}

	private static void addListeners(@Nonnull GitLineHandler handler, @Nonnull List<GitLineHandlerListener> listeners)
	{
		for(GitLineHandlerListener listener : listeners)
		{
			handler.addLineListener(listener);
		}
	}

	@Nonnull
	private static GitCommandResult run(@Nonnull Computable<GitLineHandler> handlerConstructor)
	{
		final List<String> errorOutput = new ArrayList<>();
		final List<String> output = new ArrayList<>();
		final AtomicInteger exitCode = new AtomicInteger();
		final AtomicBoolean startFailed = new AtomicBoolean();
		final AtomicReference<Throwable> exception = new AtomicReference<>();

		int authAttempt = 0;
		boolean authFailed;
		boolean success;
		do
		{
			errorOutput.clear();
			output.clear();
			exitCode.set(0);
			startFailed.set(false);
			exception.set(null);

			GitLineHandler handler = handlerConstructor.compute();
			handler.addLineListener(new GitLineHandlerListener()
			{
				@Override
				public void onLineAvailable(String line, Key outputType)
				{
					if(looksLikeError(line))
					{
						errorOutput.add(line);
					}
					else
					{
						output.add(line);
					}
				}

				@Override
				public void processTerminated(int code)
				{
					exitCode.set(code);
				}

				@Override
				public void startFailed(Throwable t)
				{
					startFailed.set(true);
					errorOutput.add("Failed to start Git process");
					exception.set(t);
				}
			});

			handler.runInCurrentThread(null);
			authFailed = handler.hasHttpAuthFailed();
			success = !startFailed.get() && (handler.isIgnoredErrorCode(exitCode.get()) || exitCode.get() == 0);
		}
		while(authFailed && authAttempt++ < 2);
		return new GitCommandResult(success, exitCode.get(), errorOutput, output, null);
	}

	/**
	 * Runs the given {@link GitLineHandler} in the current thread and returns the {@link GitCommandResult}.
	 */
	@Nonnull
	private static GitCommandResult run(@Nonnull GitLineHandler handler)
	{
		return run(new Computable.PredefinedValueComputable<>(handler));
	}

	@Override
	@Nonnull
	public GitCommandResult runCommand(@Nonnull Computable<GitLineHandler> handlerConstructor)
	{
		return run(handlerConstructor);
	}

	@Nonnull
	@Override
	public GitCommandResult runCommand(@Nonnull final GitLineHandler handler)
	{
		return runCommand(() -> handler);
	}

	@Nonnull
	private static GitCommandResult runAll(@Nonnull List<Computable<GitCommandResult>> commands)
	{
		if(commands.isEmpty())
		{
			LOG.error("List of commands should not be empty", new Exception());
			return GitCommandResult.error("Internal error");
		}
		GitCommandResult compoundResult = null;
		for(Computable<GitCommandResult> command : commands)
		{
			compoundResult = GitCommandResult.merge(compoundResult, command.compute());
		}
		return ObjectUtils.assertNotNull(compoundResult);
	}

	private static boolean looksLikeError(@Nonnull final String text)
	{
		return ContainerUtil.exists(ERROR_INDICATORS, indicator -> StringUtil.startsWithIgnoreCase(text.trim(), indicator));
	}

	// could be upper-cased, so should check case-insensitively
	public static final String[] ERROR_INDICATORS = {
			"error:",
			"remote: error",
			"fatal:",
			"Cannot",
			"Could not",
			"Interactive rebase already started",
			"refusing to pull",
			"cannot rebase:",
			"conflict",
			"unable"
	};
}
