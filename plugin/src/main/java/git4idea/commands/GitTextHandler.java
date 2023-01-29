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

import consulo.process.ExecutionException;
import consulo.process.ProcessHandler;
import consulo.process.ProcessHandlerBuilder;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.event.ProcessEvent;
import consulo.process.event.ProcessListener;
import consulo.process.local.BaseProcessHandler;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.virtualFileSystem.VirtualFile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;

/**
 * The handler for git commands with text outputs
 */
public abstract class GitTextHandler extends GitHandler {
  // note that access is safe because it accessed in unsynchronized block only after process is started, and it does not change after that
  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
  private ProcessHandler myHandler;
  private volatile boolean myIsDestroyed;
  private final Object myProcessStateLock = new Object();

  protected GitTextHandler(@Nonnull Project project, @Nonnull File directory, @Nonnull GitCommand command) {
    super(project, directory, command);
  }

  protected GitTextHandler(final Project project, final VirtualFile vcsRoot, final GitCommand command) {
    super(project, vcsRoot, command);
  }

  @Nullable
  @Override
  protected Process startProcess() throws ExecutionException {
    synchronized (myProcessStateLock) {
      if (myIsDestroyed) {
        return null;
      }
      final ProcessHandler processHandler = createProcess(myCommandLine);
      myHandler = processHandler;
      return ((BaseProcessHandler<Process>)myHandler).getProcess();
    }
  }

  @Override
  protected void startHandlingStreams() {
    if (myHandler == null) {
      return;
    }
    myHandler.addProcessListener(new ProcessListener() {
      @Override
      public void startNotified(final ProcessEvent event) {
        // do nothing
      }

      @Override
      public void processTerminated(final ProcessEvent event) {
        final int exitCode = event.getExitCode();
        try {
          setExitCode(exitCode);
          cleanupEnv();
          GitTextHandler.this.processTerminated(exitCode);
        }
        finally {
          listeners().processTerminated(exitCode);
        }
      }

      @Override
      public void processWillTerminate(final ProcessEvent event, final boolean willBeDestroyed) {
        // do nothing
      }

      @Override
      public void onTextAvailable(final ProcessEvent event, final Key outputType) {
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
  public void destroyProcess() {
    synchronized (myProcessStateLock) {
      myIsDestroyed = true;
      if (myHandler != null) {
        myHandler.destroyProcess();
      }
    }
  }

  @Override
  protected void waitForProcess() {
    if (myHandler != null) {
      myHandler.waitFor();
    }
  }

  public ProcessHandler createProcess(@Nonnull GeneralCommandLine commandLine) throws ExecutionException {
    commandLine.setCharset(getCharset());
    return ProcessHandlerBuilder.create(commandLine).killable().blockingReader().build();
  }
}
