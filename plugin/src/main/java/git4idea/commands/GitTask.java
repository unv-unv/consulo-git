/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import git4idea.GitVcs;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Task to run the given GitHandler with ability to cancel it.
 * <p/>
 * <b>Cancellation</b> is implemented with a {@link Timer} which checks whether the ProgressIndicator was cancelled and kills
 * the GitHandler in that case.
 * <p/>
 * A GitTask may be executed synchronously ({@link #executeModal()} or asynchronously ({@link #executeAsync(GitTask.ResultHandler)}.
 * Result of the execution is encapsulated in {@link GitTaskResult}.
 * <p/>
 * <p>
 * <b>GitTaskResultHandler</b> is called from AWT-thread. Use {@link #setExecuteResultInAwt(boolean) setExecuteResultInAwt(false)}
 * to execute the result {@link Application#executeOnPooledThread(Runnable) on the pooled thread}.
 * </p>
 *
 * @author Kirill Likhodedov
 */
public class GitTask {
    private static final Logger LOG = Logger.getInstance(GitTask.class);

    private final Project myProject;
    private final GitHandler myHandler;
    private final LocalizeValue myTitle;
    private GitProgressAnalyzer myProgressAnalyzer;
    private ProgressIndicator myProgressIndicator;

    public GitTask(Project project, GitHandler handler, LocalizeValue title) {
        myProject = project;
        myHandler = handler;
        myTitle = title;
    }

    /**
     * Executes this task synchronously, with a modal progress dialog.
     *
     * @return Result of the task execution.
     */
    @RequiredUIAccess
    public GitTaskResult executeModal() {
        return execute(true);
    }

    /**
     * Executes the task synchronously, with a modal progress dialog.
     *
     * @param resultHandler callback which will be called after task execution.
     */
    @RequiredUIAccess
    public void executeModal(GitTaskResultHandler resultHandler) {
        execute(true, true, resultHandler);
    }

    /**
     * Executes this task asynchronously, in background. Calls the resultHandler when finished.
     *
     * @param resultHandler callback called after the task has finished or was cancelled by user or automatically.
     */
    @RequiredUIAccess
    public void executeAsync(GitTaskResultHandler resultHandler) {
        execute(false, false, resultHandler);
    }

    @RequiredUIAccess
    public void executeInBackground(boolean sync, GitTaskResultHandler resultHandler) {
        execute(sync, false, resultHandler);
    }

    // this is always sync
    @Nonnull
    @RequiredUIAccess
    public GitTaskResult execute(boolean modal) {
        final AtomicReference<GitTaskResult> result = new AtomicReference<>(GitTaskResult.INITIAL);
        execute(true, modal, new GitTaskResultHandlerAdapter() {
            @Override
            protected void run(GitTaskResult res) {
                result.set(res);
            }
        });
        return result.get();
    }

    /**
     * The most general execution method.
     *
     * @param sync          Set to <code>true</code> to make the calling thread wait for the task execution.
     * @param modal         If <code>true</code>, the task will be modal with a modal progress dialog. If false, the task will be executed in
     *                      background. <code>modal</code> implies <code>sync</code>, i.e. if modal then sync doesn't matter: you'll wait anyway.
     * @param resultHandler Handle the result.
     * @see #execute(boolean)
     */
    @RequiredUIAccess
    public void execute(boolean sync, boolean modal, final GitTaskResultHandler resultHandler) {
        final Object LOCK = new Object();
        final AtomicBoolean completed = new AtomicBoolean();

        if (modal) {
            ModalTask task = new ModalTask(myProject, myHandler, myTitle) {
                @Override
                @RequiredUIAccess
                public void onSuccess() {
                    commonOnSuccess(LOCK, resultHandler);
                    completed.set(true);
                }

                @Override
                @RequiredUIAccess
                public void onCancel() {
                    commonOnCancel(LOCK, resultHandler);
                    completed.set(true);
                }
            };
            Application application = Application.get();
            application.invokeAndWait(() -> ProgressManager.getInstance().run(task), application.getDefaultModalityState());
        }
        else {
            BackgroundableTask task = new BackgroundableTask(myProject, myHandler, myTitle) {
                @Override
                @RequiredUIAccess
                public void onSuccess() {
                    commonOnSuccess(LOCK, resultHandler);
                    completed.set(true);
                }

                @Override
                @RequiredUIAccess
                public void onCancel() {
                    commonOnCancel(LOCK, resultHandler);
                    completed.set(true);
                }
            };
            if (myProgressIndicator == null) {
                GitVcs.runInBackground(task);
            }
            else {
                task.runAlone();
            }
        }

        if (sync) {
            while (!completed.get()) {
                try {
                    synchronized (LOCK) {
                        LOCK.wait(50);
                    }
                }
                catch (InterruptedException e) {
                    LOG.info(e);
                }
            }
        }
    }

    private void commonOnSuccess(Object LOCK, GitTaskResultHandler resultHandler) {
        GitTaskResult res = !myHandler.errors().isEmpty() ? GitTaskResult.GIT_ERROR : GitTaskResult.OK;
        resultHandler.run(res);
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    private void commonOnCancel(Object LOCK, GitTaskResultHandler resultHandler) {
        resultHandler.run(GitTaskResult.CANCELLED);
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }

    private void addListeners(final TaskExecution task, final ProgressIndicator indicator) {
        if (indicator != null) {
            indicator.setIndeterminate(myProgressAnalyzer == null);
        }
        // When receives an error line, adds a VcsException to the GitHandler.
        GitLineHandlerListener listener = new GitLineHandlerListener() {
            @Override
            public void processTerminated(int exitCode) {
                if (exitCode != 0 && !myHandler.isIgnoredErrorCode(exitCode)) {
                    if (myHandler.errors().isEmpty()) {
                        myHandler.addError(new VcsException(myHandler.getLastOutput()));
                    }
                }
            }

            @Override
            public void startFailed(Throwable exception) {
                myHandler.addError(new VcsException("Git start failed: " + exception.getMessage(), exception));
            }

            @Override
            public void onLineAvailable(String line, Key outputType) {
                if (GitHandlerUtil.isErrorLine(line.trim())) {
                    myHandler.addError(new VcsException(line));
                }
                else if (!StringUtil.isEmptyOrSpaces(line)) {
                    myHandler.addLastOutput(line);
                }
                if (indicator != null) {
                    indicator.setText2(line);
                }
                if (myProgressAnalyzer != null && indicator != null) {
                    double fraction = myProgressAnalyzer.analyzeProgress(line);
                    if (fraction >= 0) {
                        indicator.setFraction(fraction);
                    }
                }
            }
        };

        if (myHandler instanceof GitLineHandler lineHandler) {
            lineHandler.addLineListener(listener);
        }
        else {
            myHandler.addListener(listener);
        }

        // disposes the timer
        myHandler.addListener(new GitHandlerListener() {
            @Override
            public void processTerminated(int exitCode) {
                task.dispose();
            }

            @Override
            public void startFailed(Throwable exception) {
                task.dispose();
            }
        });
    }

    public void setProgressAnalyzer(GitProgressAnalyzer progressAnalyzer) {
        myProgressAnalyzer = progressAnalyzer;
    }

    public void setProgressIndicator(ProgressIndicator progressIndicator) {
        myProgressIndicator = progressIndicator;
    }

    /**
     * We're using this interface here to work with Task, because standard {@link Task#run(ProgressIndicator)}
     * is busy with timers.
     */
    private interface TaskExecution {
        void execute(ProgressIndicator indicator);

        void dispose();
    }

    // To add to {@link com.intellij.openapi.progress.BackgroundTaskQueue} a task must be {@link Task.Backgroundable},
    // so we can't have a single class representing a task: we have BackgroundableTask and ModalTask.
    // To minimize code duplication we use GitTaskDelegate.

    private abstract class BackgroundableTask extends Task.Backgroundable implements TaskExecution {
        private GitTaskDelegate myDelegate;

        public BackgroundableTask(@Nullable Project project, @Nonnull GitHandler handler, @Nonnull LocalizeValue processTitle) {
            super(project, processTitle, true);
            myDelegate = new GitTaskDelegate(project, handler, this);
        }

        @Override
        public final void run(@Nonnull ProgressIndicator indicator) {
            myDelegate.run(indicator);
        }

        @RequiredUIAccess
        public final void runAlone() {
            Application application = Application.get();
            if (application.isDispatchThread()) {
                application.executeOnPooledThread((Runnable) this::justRun);
            }
            else {
                justRun();
            }
        }

        @RequiredUIAccess
        private void justRun() {
            LocalizeValue oldTitle = myProgressIndicator.getTextValue();
            myProgressIndicator.setTextValue(myTitle);
            myDelegate.run(myProgressIndicator);
            myProgressIndicator.setTextValue(oldTitle);
            if (myProgressIndicator.isCanceled()) {
                onCancel();
            }
            else {
                onSuccess();
            }
        }

        @Override
        public void execute(ProgressIndicator indicator) {
            addListeners(this, indicator);
            GitHandlerUtil.runInCurrentThread(myHandler, indicator, false, myTitle);
        }

        @Override
        public void dispose() {
            Disposer.dispose(myDelegate);
        }
    }

    private abstract class ModalTask extends Task.Modal implements TaskExecution {
        private GitTaskDelegate myDelegate;

        public ModalTask(@Nullable Project project, @Nonnull GitHandler handler, @Nonnull LocalizeValue processTitle) {
            super(project, processTitle, true);
            myDelegate = new GitTaskDelegate(project, handler, this);
        }

        @Override
        public final void run(@Nonnull ProgressIndicator indicator) {
            myDelegate.run(indicator);
        }

        @Override
        public void execute(ProgressIndicator indicator) {
            addListeners(this, indicator);
            GitHandlerUtil.runInCurrentThread(myHandler, indicator, false, myTitle);
        }

        @Override
        public void dispose() {
            Disposer.dispose(myDelegate);
        }
    }

    /**
     * Does the work which is common for BackgroundableTask and ModalTask.
     * Actually - starts a timer which checks if current progress indicator is cancelled.
     * If yes, kills the GitHandler.
     */
    private static class GitTaskDelegate implements Disposable {
        private GitHandler myHandler;
        private ProgressIndicator myIndicator;
        private TaskExecution myTask;
        private Timer myTimer;
        private Project myProject;

        public GitTaskDelegate(Project project, GitHandler handler, TaskExecution task) {
            myProject = project;
            myHandler = handler;
            myTask = task;
            Disposer.register(myProject, this);
        }

        public void run(ProgressIndicator indicator) {
            myIndicator = indicator;
            myTimer = new Timer();
            myTimer.schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        if (myIndicator != null && myIndicator.isCanceled()) {
                            try {
                                if (myHandler != null) {
                                    myHandler.destroyProcess();
                                }
                            }
                            finally {
                                Disposer.dispose(GitTaskDelegate.this);
                            }
                        }
                    }
                },
                0,
                200
            );
            myTask.execute(indicator);
        }

        @Override
        public void dispose() {
            if (myTimer != null) {
                myTimer.cancel();
            }
        }
    }
}
