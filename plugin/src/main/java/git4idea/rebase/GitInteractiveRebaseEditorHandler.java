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
package git4idea.rebase;

import consulo.logging.Logger;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.ref.SimpleReference;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.commands.GitHandler;
import jakarta.annotation.Nonnull;

import java.io.Closeable;
import java.util.List;

/**
 * The handler for rebase editor request. The handler shows {@link GitRebaseEditor}
 * dialog with the specified file. If user accepts the changes, it saves file and returns 0,
 * otherwise it just returns error code.
 */
public class GitInteractiveRebaseEditorHandler implements Closeable, GitRebaseEditorHandler {
    /**
     * The logger
     */
    private final static Logger LOG = Logger.getInstance(GitInteractiveRebaseEditorHandler.class);
    /**
     * The service object that has created this handler
     */
    private final GitRebaseEditorService myService;
    /**
     * The context project
     */
    @Nonnull
    private final Project myProject;
    /**
     * The git repository root
     */
    private final VirtualFile myRoot;
    /**
     * The handler that specified this editor
     */
    private final GitHandler myHandler;
    /**
     * The handler number
     */
    private final int myHandlerNo;
    /**
     * If true, the handler has been closed
     */
    private boolean myIsClosed;
    /**
     * Set to true after rebase editor was shown
     */
    protected boolean myRebaseEditorShown = false;

    private boolean myNoopSituation;

    private boolean myEditorCancelled;

    /**
     * The constructor from fields that is expected to be
     * accessed only from {@link GitRebaseEditorService}.
     *
     * @param service the service object that has created this handler
     * @param project the context project
     * @param root    the git repository root
     * @param handler the handler for process that needs this editor
     */
    public GitInteractiveRebaseEditorHandler(
        @Nonnull GitRebaseEditorService service,
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull GitHandler handler
    ) {
        myService = service;
        myProject = project;
        myRoot = root;
        myHandler = handler;
        myHandlerNo = service.registerHandler(this);
    }

    /**
     * @return the handler for the process that started this editor
     */
    @Override
    public GitHandler getHandler() {
        return myHandler;
    }

    /**
     * Edit commits request
     *
     * @param path the path to editing
     * @return the exit code to be returned from editor
     */
    @Override
    @RequiredUIAccess
    public int editCommits(String path) {
        ensureOpen();
        SimpleReference<Boolean> isSuccess = new SimpleReference<>();
        myProject.getApplication().invokeAndWait(() -> {
            try {
                myEditorCancelled = false;
                myNoopSituation = false;
                if (myRebaseEditorShown) {
                    GitRebaseUnstructuredEditor editor = new GitRebaseUnstructuredEditor(myProject, myRoot, path);
                    DialogManager.show(editor);
                    if (editor.isOK()) {
                        editor.save();
                    }
                    else {
                        myEditorCancelled = true;
                    }
                    isSuccess.set(true);
                    return;
                }
                else {
                    setRebaseEditorShown();
                    GitInteractiveRebaseFile rebaseFile = new GitInteractiveRebaseFile(myProject, myRoot, path);
                    try {
                        List<GitRebaseEntry> entries = rebaseFile.load();
                        GitRebaseEditor editor = new GitRebaseEditor(myProject, myRoot, entries);
                        DialogManager.show(editor);
                        if (editor.isOK()) {
                            rebaseFile.save(editor.getEntries());
                            isSuccess.set(true);
                            return;
                        }
                        else {
                            rebaseFile.cancel();
                            myEditorCancelled = true;
                        }
                    }
                    catch (GitInteractiveRebaseFile.NoopException e) {
                        LOG.info("Noop situation while rebasing " + myRoot);
                        String message =
                            "There are no commits to rebase because the current branch is directly below the base branch, " +
                                "or they point to the same commit (the 'noop' situation).\n" +
                                "Do you want to continue (this will reset the current branch to the base branch)?";
                        int rebase = DialogManager.showOkCancelDialog(
                            myProject,
                            message,
                            "Git Rebase",
                            CommonLocalize.buttonOk().get(),
                            CommonLocalize.buttonCancel().get(),
                            UIUtil.getQuestionIcon()
                        );
                        if (rebase == Messages.OK) {
                            isSuccess.set(true);
                            myNoopSituation = true;
                            return;
                        }
                        else {
                            myEditorCancelled = true;
                        }
                    }
                }
            }
            catch (Exception e) {
                LOG.error("Failed to edit the git rebase file: " + path, e);
            }
            isSuccess.set(false);
        });
        return (isSuccess.isNull() || !isSuccess.get()) ? GitRebaseEditorMain.ERROR_EXIT_CODE : 0;
    }

    /**
     * This method is invoked to indicate that this editor will be invoked in the rebase continuation action.
     */
    public void setRebaseEditorShown() {
        myRebaseEditorShown = true;
    }

    /**
     * Check that handler has not yet been closed
     */
    private void ensureOpen() {
        if (myIsClosed) {
            throw new IllegalStateException("The handler was already closed");
        }
    }

    /**
     * Stop using the handler
     */
    @Override
    public void close() {
        ensureOpen();
        myIsClosed = true;
        myService.unregisterHandler(myHandlerNo);
    }

    /**
     * @return the handler number
     */
    public int getHandlerNo() {
        return myHandlerNo;
    }

    /**
     * Tells if there was a "noop" situation during rebase (no commits were rebase, just the label was moved).
     */
    public boolean wasNoopSituationDetected() {
        return myNoopSituation;
    }

    public boolean wasEditorCancelled() {
        return myEditorCancelled;
    }
}
