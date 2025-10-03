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
package git4idea.actions;

import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Messages;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitTask;
import git4idea.commands.GitTaskResult;
import git4idea.commands.GitTaskResultHandlerAdapter;
import git4idea.rebase.GitInteractiveRebaseEditorHandler;
import git4idea.rebase.GitRebaseEditorService;
import git4idea.rebase.GitRebaseLineListener;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Set;

/**
 * The base class for rebase actions that use editor
 */
public abstract class GitRebaseActionBase extends GitRepositoryAction {
    /**
     * {@inheritDoc}
     */
    @Override
    @RequiredUIAccess
    protected void perform(
        @Nonnull final Project project,
        @Nonnull List<VirtualFile> gitRoots,
        @Nonnull VirtualFile defaultRoot,
        Set<VirtualFile> affectedRoots,
        final List<VcsException> exceptions
    ) throws VcsException {
        GitLineHandler h = createHandler(project, gitRoots, defaultRoot);
        if (h == null) {
            return;
        }
        final VirtualFile root = h.workingDirectoryFile();
        GitRebaseEditorService service = GitRebaseEditorService.getInstance();
        final GitInteractiveRebaseEditorHandler editor = new GitInteractiveRebaseEditorHandler(service, project, root, h);
        final GitRebaseLineListener resultListener = new GitRebaseLineListener();
        h.addLineListener(resultListener);
        configureEditor(editor);
        affectedRoots.add(root);

        service.configureHandler(h, editor.getHandlerNo());
        GitTask task = new GitTask(project, h, GitLocalize.taskRebasingTitle());
        task.executeInBackground(
            false,
            new GitTaskResultHandlerAdapter() {
                @Override
                @RequiredUIAccess
                protected void run(GitTaskResult taskResult) {
                    editor.close();
                    GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
                    manager.updateRepository(root);
                    root.refresh(false, true);
                    notifyAboutErrorResult(taskResult, resultListener, exceptions, project);
                }
            }
        );
    }

    @RequiredUIAccess
    private static void notifyAboutErrorResult(
        GitTaskResult taskResult,
        GitRebaseLineListener resultListener,
        List<VcsException> exceptions,
        Project project
    ) {
        if (taskResult == GitTaskResult.CANCELLED) {
            return;
        }
        GitRebaseLineListener.Result result = resultListener.getResult();
        switch (result.status) {
            case CONFLICT:
                Messages.showErrorDialog(
                    project,
                    GitLocalize.rebaseResultConflict(result.current, result.total).get(),
                    GitLocalize.rebaseResultConflictTitle().get()
                );
                break;
            case ERROR:
                Messages.showErrorDialog(
                    project,
                    GitLocalize.rebaseResultError(result.current, result.total).get(),
                    GitLocalize.rebaseResultErrorTitle().get()
                );
                break;
            case CANCELLED:
                // we do not need to show a message if editing was cancelled.
                exceptions.clear();
                return;
            case EDIT:
                Messages.showInfoMessage(
                    project,
                    GitLocalize.rebaseResultAmend(result.current, result.total).get(),
                    GitLocalize.rebaseResultAmendTitle().get()
                );
                break;
        }
    }

    /**
     * This method could be overridden to supply additional information to the editor.
     *
     * @param editor the editor to configure
     */
    protected void configureEditor(GitInteractiveRebaseEditorHandler editor) {
    }

    /**
     * Create line handler that represents a git operation
     *
     * @param project     the context project
     * @param gitRoots    the git roots
     * @param defaultRoot the default root
     * @return the line handler or null
     */
    @Nullable
    protected abstract GitLineHandler createHandler(Project project, List<VirtualFile> gitRoots, VirtualFile defaultRoot);
}
