/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

import java.io.File;
import java.nio.charset.Charset;

import javax.annotation.Nonnull;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.io.BaseOutputReader;

import javax.annotation.Nullable;

/**
 * The handler for git commands with text outputs
 */
public abstract class GitTextHandler extends GitHandler
{
	// note that access is safe because it accessed in unsynchronized block only after process is started, and it does not change after that
	@SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
	private OSProcessHandler myHandler;
	private volatile boolean myIsDestroyed;
	private final Object myProcessStateLock = new Object();

	protected GitTextHandler(@Nonnull Project project, @Nonnull File directory, @Nonnull GitCommand command)
	{
		super(project, directory, command);
	}

	protected GitTextHandler(final Project project, final VirtualFile vcsRoot, final GitCommand command)
	{
		super(project, vcsRoot, command);
	}

	@Nullable
	@Override
	protected Process startProcess() throws ExecutionException
	{
		synchronized(myProcessStateLock)
		{
			if(myIsDestroyed)
			{
				return null;
			}
			final OSProcessHandler processHandler = createProcess(myCommandLine);
			myHandler = processHandler;
			return myHandler.getProcess();
		}
	}

	@Override
	protected void startHandlingStreams()
	{
		if(myHandler == null)
		{
			return;
		}
		myHandler.addProcessListener(new ProcessListener()
		{
			@Override
			public void startNotified(final ProcessEvent event)
			{
				// do nothing
			}

			@Override
			public void processTerminated(final ProcessEvent event)
			{
				final int exitCode = event.getExitCode();
				try
				{
					setExitCode(exitCode);
					cleanupEnv();
					GitTextHandler.this.processTerminated(exitCode);
				}
				finally
				{
					listeners().processTerminated(exitCode);
				}
			}

			@Override
			public void processWillTerminate(final ProcessEvent event, final boolean willBeDestroyed)
			{
				// do nothing
			}

			@Override
			public void onTextAvailable(final ProcessEvent event, final Key outputType)
			{
				GitTextHandler.this.onTextAvailable(event.getText(), outputType);
			}
		});
		myHandler.startNotify();
	}

	/**
	 * Notification for handler to handle process exit event
	 *
	 * @param exitCode a exit code.
	 */
	protected abstract void processTerminated(int exitCode);

	/**
	 * This method is invoked when some text is available
	 *
	 * @param text       an available text
	 * @param outputType output type
	 */
	protected abstract void onTextAvailable(final String text, final Key outputType);

	@Override
	public void destroyProcess()
	{
		synchronized(myProcessStateLock)
		{
			myIsDestroyed = true;
			if(myHandler != null)
			{
				myHandler.destroyProcess();
			}
		}
	}

	@Override
	protected void waitForProcess()
	{
		if(myHandler != null)
		{
			myHandler.waitFor();
		}
	}

	public OSProcessHandler createProcess(@Nonnull GeneralCommandLine commandLine) throws ExecutionException
	{
		commandLine.setCharset(getCharset());
		return new MyOSProcessHandler(commandLine);
	}

	private static class MyOSProcessHandler extends KillableProcessHandler
	{
		public MyOSProcessHandler(GeneralCommandLine commandLine) throws ExecutionException
		{
			super(commandLine, true);
		}

		@Nonnull
		@Override
		public Charset getCharset()
		{
			return myCharset;
		}

		@Nonnull
		@Override
		protected BaseOutputReader.Options readerOptions()
		{
			return Registry.is("git.blocking.read", true) ? BaseOutputReader.Options.BLOCKING : BaseOutputReader.Options.NON_BLOCKING;
		}
	}
}
