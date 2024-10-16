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
package git4idea.commands;

import consulo.git.localize.GitLocalize;
import consulo.process.ProcessOutputTypes;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;

import java.io.File;

/**
 * Simple Git handler that accumulates stdout and stderr and has nothing on stdin.
 * The handler executes commands synchronously with cancellable progress indicator.
 * <p/>
 * The class also includes a number of static utility methods that represent some
 * simple commands.
 */
public class GitSimpleHandler extends GitTextHandler {
    public static final String DURING_EXECUTING_ERROR_MESSAGE = "during executing";

    /**
     * Stderr output
     */
    private final StringBuilder myStderr = new StringBuilder();
    /**
     * Reminder of the last stderr line
     */
    private final StringBuilder myStderrLine = new StringBuilder();
    /**
     * Stdout output
     */
    private final StringBuilder myStdout = new StringBuilder();
    /**
     * Reminder of the last stdout line
     */
    private final StringBuilder myStdoutLine = new StringBuilder();

    /**
     * A constructor
     *
     * @param project   a project
     * @param directory a process directory
     * @param command   a command to execute
     */
    @SuppressWarnings({"WeakerAccess"})
    public GitSimpleHandler(@Nonnull Project project, @Nonnull File directory, @Nonnull GitCommand command) {
        super(project, directory, command);
    }

    /**
     * A constructor
     *
     * @param project   a project
     * @param directory a process directory
     * @param command   a command to execute
     */
    @SuppressWarnings({"WeakerAccess"})
    public GitSimpleHandler(@Nonnull final Project project, @Nonnull final VirtualFile directory, @Nonnull final GitCommand command) {
        super(project, directory, command);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void processTerminated(final int exitCode) {
        if (myVcs == null) {
            return;
        }
        String stdout = myStdoutLine.toString();
        String stderr = myStderrLine.toString();
        if (!isStdoutSuppressed() && !StringUtil.isEmptyOrSpaces(stdout)) {
            myVcs.showMessages(stdout);
            LOG.info(stdout.trim());
            myStdoutLine.setLength(0);
        }
        else if (!isStderrSuppressed() && !StringUtil.isEmptyOrSpaces(stderr)) {
            myVcs.showErrorMessages(stderr);
            LOG.info(stderr.trim());
            myStderrLine.setLength(0);
        }
        else {
            LOG.debug(stderr.trim());
            OUTPUT_LOG.debug(stdout.trim());
        }
    }

    /**
     * For silent handlers, print out everything
     */
    public void unsilence() {
        if (myVcs == null) {
            return;
        }
        myVcs.showCommandLine(printableCommandLine());
        if (myStderr.length() != 0) {
            myVcs.showErrorMessages(myStderr.toString());
        }
        if (myStdout.length() != 0) {
            myVcs.showMessages(myStdout.toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onTextAvailable(final String text, final Key outputType) {
        final StringBuilder entire;
        final StringBuilder lineRest;
        final boolean suppressed;
        if (ProcessOutputTypes.STDOUT == outputType) {
            entire = myStdout;
            lineRest = myStdoutLine;
            suppressed = isStdoutSuppressed();
        }
        else if (ProcessOutputTypes.STDERR == outputType) {
            entire = myStderr;
            lineRest = myStderrLine;
            suppressed = isStderrSuppressed();
        }
        else {
            return;
        }
        entire.append(text);
        if (myVcs == null || (suppressed && !LOG.isDebugEnabled())) {
            return;
        }
        int last = lineRest.length() > 0 ? lineRest.charAt(lineRest.length() - 1) : -1;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (last == '\n' || last == '\r') {
                int savedPos;
                if ((ch == '\n' || ch == '\r') && ch != last) {
                    savedPos = i - 1;
                }
                else {
                    savedPos = i;
                }
                if (last != '\r' || savedPos != i) {
                    String line;
                    if (lineRest.length() == 0) {
                        line = lineRest.append(text.substring(start, savedPos)).toString();
                        lineRest.setLength(0);
                    }
                    else {
                        line = text.substring(start, savedPos);
                    }
                    if (!StringUtil.isEmptyOrSpaces(line)) {
                        if (!suppressed) {
                            LOG.info(line.trim());
                            if (ProcessOutputTypes.STDOUT == outputType) {
                                myVcs.showMessages(line);
                            }
                            else if (ProcessOutputTypes.STDERR == outputType) {
                                myVcs.showErrorMessages(line);
                            }
                        }
                        else {
                            LOG.debug(line.trim());
                        }
                    }
                }
                start = savedPos;
            }
            last = ch;
        }
        if (start != text.length()) {
            lineRest.append(text.substring(start));
        }
    }

    /**
     * @return stderr contents
     */
    public String getStderr() {
        return myStderr.toString();
    }

    /**
     * @return stdout contents
     */
    public String getStdout() {
        return myStdout.toString();
    }

    /**
     * Execute without UI. If UI interactions are required (for example SSH popups or progress dialog), use {@link GitHandlerUtil} methods.
     *
     * @return a value if process was successful
     * @throws VcsException exception if process failed to start.
     */
    public String run() throws VcsException {
        if (isRemote()) {
            throw new IllegalStateException("Commands that require remote access could not be run using this method");
        }
        final VcsException[] ex = new VcsException[1];
        final String[] result = new String[1];
        addListener(new GitHandlerListener() {
            @Override
            public void processTerminated(final int exitCode) {
                try {
                    if (exitCode == 0 || isIgnoredErrorCode(exitCode)) {
                        result[0] = getStdout();
                    }
                    else {
                        String msg = getStderr();
                        if (msg.isEmpty()) {
                            msg = getStdout();
                        }
                        if (msg.isEmpty()) {
                            msg = GitLocalize.gitErrorExit(exitCode).get();
                        }
                        ex[0] = new VcsException(msg);
                    }
                }
                catch (Throwable t) {
                    ex[0] = new VcsException(t.toString(), t);
                }
            }

            @Override
            public void startFailed(final Throwable exception) {
                ex[0] = new VcsException(
                    "Process failed to start (" + myCommandLine.getCommandLineString() + "): " + exception.toString(),
                    exception
                );
            }
        });
        runInCurrentThread(null);
        if (ex[0] != null) {
            throw new VcsException(ex[0].getMessage() + " " + DURING_EXECUTING_ERROR_MESSAGE + " " + printableCommandLine(), ex[0]);
        }
        if (result[0] == null) {
            throw new VcsException("The git command returned null: " + printableCommandLine());
        }
        return result[0];
    }
}
