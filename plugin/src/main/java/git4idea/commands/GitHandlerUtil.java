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

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.awt.*;
import java.util.Collection;

/**
 * Handler utilities that allow running handlers with progress indicators
 */
public class GitHandlerUtil {
    /**
     * The logger instance
     */
    private static final Logger LOG = Logger.getInstance(GitHandlerUtil.class);

    /**
     * a private constructor for utility class
     */
    private GitHandlerUtil() {
    }

    /**
     * Execute simple process synchronously with progress
     *
     * @param handler        a handler
     * @param operationTitle an operation title shown in progress dialog
     * @param operationName  an operation name shown in failure dialog
     * @return A stdout content or null if there was error (exit code != 0 or exception during start).
     */
    @Nullable
    public static String doSynchronously(
        final GitSimpleHandler handler,
        @Nonnull LocalizeValue operationTitle,
        @Nonnull LocalizeValue operationName
    ) {
        handler.addListener(new GitHandlerListenerBase(handler, operationName) {
            @Override
            protected String getErrorText() {
                String stderr = handler.getStderr();
                return stderr.isEmpty() ? handler.getStdout() : stderr;
            }
        });
        runHandlerSynchronously(handler, operationTitle, ProgressManager.getInstance(), true);
        return handler.isStarted() && handler.getExitCode() == 0 ? handler.getStdout() : null;
    }

    /**
     * Execute simple process synchronously with progress
     *
     * @param handler        a handler
     * @param operationTitle an operation title shown in progress dialog
     * @param operationName  an operation name shown in failure dialog
     * @return An exit code
     */
    public static int doSynchronously(
        GitLineHandler handler,
        @Nonnull LocalizeValue operationTitle,
        @Nonnull LocalizeValue operationName
    ) {
        return doSynchronously(handler, operationTitle, operationName, true);
    }

    /**
     * Execute simple process synchronously with progress
     *
     * @param handler        a handler
     * @param operationTitle an operation title shown in progress dialog
     * @param operationName  an operation name shown in failure dialog
     * @param showErrors     if true, the errors are shown when process is terminated
     * @return An exit code
     */
    public static int doSynchronously(
        GitLineHandler handler,
        @Nonnull LocalizeValue operationTitle,
        @Nonnull LocalizeValue operationName,
        boolean showErrors
    ) {
        return doSynchronously(handler, operationTitle, operationName, showErrors, true);
    }

    /**
     * Execute simple process synchronously with progress
     *
     * @param handler              a handler
     * @param operationTitle       an operation title shown in progress dialog
     * @param operationName        an operation name shown in failure dialog
     * @param showErrors           if true, the errors are shown when process is terminated
     * @param setIndeterminateFlag a flag indicating that progress should be configured as indeterminate
     * @return An exit code
     */
    public static int doSynchronously(
        final GitLineHandler handler,
        @Nonnull final LocalizeValue operationTitle,
        @Nonnull final LocalizeValue operationName,
        final boolean showErrors,
        final boolean setIndeterminateFlag
    ) {
        ProgressManager manager = ProgressManager.getInstance();
        manager.run(new Task.Modal(handler.project(), operationTitle, false) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                handler.addLineListener(new GitLineHandlerListenerProgress(indicator, handler, operationName, showErrors));
                runInCurrentThread(handler, indicator, setIndeterminateFlag, operationTitle);
            }
        });
        return handler.isStarted() ? handler.getExitCode() : -1;
    }

    /**
     * Run handler synchronously. The method assumes that all listeners are set up.
     *
     * @param handler              a handler to run
     * @param operationTitle       operation title
     * @param manager              a progress manager
     * @param setIndeterminateFlag if true handler is configured as indeterminate
     */
    private static void runHandlerSynchronously(
        GitHandler handler,
        @Nonnull LocalizeValue operationTitle,
        ProgressManager manager,
        boolean setIndeterminateFlag
    ) {
        manager.runProcessWithProgressSynchronously(
            () -> runInCurrentThread(handler, manager.getProgressIndicator(), setIndeterminateFlag, operationTitle),
            operationTitle.get(),
            false,
            handler.project()
        );
    }

    /**
     * Run handler in the current thread
     *
     * @param handler              a handler to run
     * @param indicator            a progress manager
     * @param setIndeterminateFlag if true handler is configured as indeterminate
     * @param operationName
     */
    public static void runInCurrentThread(
        GitHandler handler,
        ProgressIndicator indicator,
        boolean setIndeterminateFlag,
        @Nonnull LocalizeValue operationName
    ) {
        runInCurrentThread(
            handler,
            () -> {
                if (indicator != null) {
                    indicator.setTextValue(
                        operationName.isEmpty()
                            ? GitLocalize.gitRunning(handler.printableCommandLine())
                            : operationName
                    );
                    indicator.setText2Value(LocalizeValue.empty());
                    if (setIndeterminateFlag) {
                        indicator.setIndeterminate(true);
                    }
                }
            }
        );
    }

    /**
     * Run handler in the current thread
     *
     * @param handler         a handler to run
     * @param postStartAction an action that is executed
     */
    public static void runInCurrentThread(GitHandler handler, @Nullable Runnable postStartAction) {
        handler.runInCurrentThread(postStartAction);
    }

    /**
     * Run synchronously using progress indicator, but collect exceptions instead of showing error dialog
     *
     * @param handler a handler to use
     * @return the collection of exception collected during operation
     */
    public static Collection<VcsException> doSynchronouslyWithExceptions(GitLineHandler handler) {
        ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
        return doSynchronouslyWithExceptions(handler, progressIndicator, LocalizeValue.empty());
    }

    /**
     * Run synchronously using progress indicator, but collect exception instead of showing error dialog
     *
     * @param handler           a handler to use
     * @param progressIndicator a progress indicator
     * @param operationName
     * @return the collection of exception collected during operation
     */
    public static Collection<VcsException> doSynchronouslyWithExceptions(
        GitLineHandler handler,
        ProgressIndicator progressIndicator,
        @Nonnull LocalizeValue operationName
    ) {
        handler.addLineListener(new GitLineHandlerListenerProgress(progressIndicator, handler, operationName, false));
        runInCurrentThread(handler, progressIndicator, false, operationName);
        return handler.errors();
    }

    public static String formatOperationName(String operation, @Nonnull VirtualFile root) {
        return operation + " '" + root.getName() + "'...";
    }

    /**
     * A base class for handler listener that implements error handling logic
     */
    private abstract static class GitHandlerListenerBase implements GitHandlerListener {
        /**
         * a handler
         */
        protected final GitHandler myHandler;
        /**
         * a operation name for the handler
         */
        protected final LocalizeValue myOperationName;
        /**
         * if true, the errors are shown when process is terminated
         */
        protected boolean myShowErrors;

        /**
         * A constructor
         *
         * @param handler       a handler instance
         * @param operationName an operation name
         */
        public GitHandlerListenerBase(GitHandler handler, @Nonnull LocalizeValue operationName) {
            this(handler, operationName, true);
        }

        /**
         * A constructor
         *
         * @param handler       a handler instance
         * @param operationName an operation name
         * @param showErrors    if true, the errors are shown when process is terminated
         */
        public GitHandlerListenerBase(GitHandler handler, @Nonnull LocalizeValue operationName, boolean showErrors) {
            myHandler = handler;
            myOperationName = operationName;
            myShowErrors = showErrors;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void processTerminated(int exitCode) {
            if (exitCode != 0 && !myHandler.isIgnoredErrorCode(exitCode)) {
                ensureError(exitCode);
                if (myShowErrors) {
                    EventQueue.invokeLater(() -> GitUIUtil.showOperationErrors(myHandler.project(), myHandler.errors(), myOperationName));
                }
            }
        }

        /**
         * Ensure that at least one error is available in case if the process exited with non-zero exit code
         *
         * @param exitCode the exit code of the process
         */
        protected void ensureError(int exitCode) {
            if (myHandler.errors().isEmpty()) {
                String text = getErrorText();
                if (StringUtil.isEmpty(text) && myHandler.errors().isEmpty()) {
                    //noinspection ThrowableInstanceNeverThrown
                    myHandler.addError(new VcsException(GitLocalize.gitErrorExit(exitCode)));
                }
                else {
                    //noinspection ThrowableInstanceNeverThrown
                    myHandler.addError(new VcsException(text));
                }
            }
        }

        /**
         * @return error text for the handler, if null or empty string a default message is used.
         */
        protected abstract String getErrorText();

        /**
         * {@inheritDoc}
         */
        @Override
        public void startFailed(Throwable exception) {
            //noinspection ThrowableInstanceNeverThrown
            myHandler.addError(new VcsException("Git start failed: " + exception.getMessage(), exception));
            if (myShowErrors) {
                EventQueue.invokeLater(() -> GitUIUtil.showOperationError(
                    myHandler.project(),
                    myOperationName,
                    LocalizeValue.of(exception.getMessage())
                ));
            }
        }
    }

    /**
     * A base class for line handler listeners
     */
    private abstract static class GitLineHandlerListenerBase extends GitHandlerListenerBase implements GitLineHandlerListener {
        /**
         * A constructor
         *
         * @param handler       a handler instance
         * @param operationName an operation name
         * @param showErrors    if true, the errors are shown when process is terminated
         */
        public GitLineHandlerListenerBase(GitHandler handler, @Nonnull LocalizeValue operationName, boolean showErrors) {
            super(handler, operationName, showErrors);
        }
    }

    /**
     * A base class for line handler listeners
     */
    public static class GitLineHandlerListenerProgress extends GitLineHandlerListenerBase {
        /**
         * a progress manager to use
         */
        private final ProgressIndicator myProgressIndicator;

        /**
         * A constructor
         *
         * @param manager       the project manager
         * @param handler       a handler instance
         * @param operationName an operation name
         * @param showErrors    if true, the errors are shown when process is terminated
         */
        public GitLineHandlerListenerProgress(
            ProgressIndicator manager,
            GitHandler handler,
            @Nonnull LocalizeValue operationName,
            boolean showErrors
        ) {
            super(handler, operationName, showErrors);
            myProgressIndicator = manager;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected String getErrorText() {
            // all lines are already calculated as errors
            return "";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onLineAvailable(String line, Key outputType) {
            if (isErrorLine(line.trim())) {
                //noinspection ThrowableInstanceNeverThrown
                myHandler.addError(new VcsException(line));
            }
            if (myProgressIndicator != null) {
                myProgressIndicator.setText2(line);
            }
        }
    }

    /**
     * Check if the line is an error line
     *
     * @param text a line to check
     * @return true if the error line
     */
    protected static boolean isErrorLine(String text) {
        for (String prefix : GitImpl.ERROR_INDICATORS) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
