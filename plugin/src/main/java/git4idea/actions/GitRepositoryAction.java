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
package git4idea.actions;

import consulo.document.FileDocumentManager;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionPlaces;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.awt.Messages;
import consulo.ui.image.Image;
import consulo.util.collection.ArrayUtil;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.TransactionRunnable;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Base class for actions that affect the entire git repository.
 * The action is available if there is at least one git root.
 */
public abstract class GitRepositoryAction extends DumbAwareAction {
    /**
     * The task delayed until end of the primary action. These tasks happen after repository refresh.
     */
    final List<TransactionRunnable> myDelayedTasks = new ArrayList<>();

    protected GitRepositoryAction() {
    }

    protected GitRepositoryAction(@Nonnull LocalizeValue text) {
        super(text);
    }

    protected GitRepositoryAction(@Nonnull LocalizeValue text, @Nonnull LocalizeValue description, @Nullable Image icon) {
        super(text, description, icon);
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(@Nonnull AnActionEvent e) {
        myDelayedTasks.clear();
        FileDocumentManager.getInstance().saveAllDocuments();
        Project project = e.getRequiredData(Project.KEY);
        GitVcs vcs = GitVcs.getInstance(project);
        List<VirtualFile> roots = getGitRoots(project, vcs);
        if (roots == null) {
            return;
        }

        VirtualFile defaultRoot = getDefaultRoot(project, roots, e.getData(VirtualFile.KEY_OF_ARRAY));
        Set<VirtualFile> affectedRoots = new HashSet<>();

        List<VcsException> exceptions = new ArrayList<>();
        try {
            perform(project, roots, defaultRoot, affectedRoots, exceptions);
        }
        catch (VcsException ex) {
            exceptions.add(ex);
        }
        if (executeFinalTasksSynchronously()) {
            runFinalTasks(project, vcs, affectedRoots, getActionName(), exceptions);
        }
    }

    @Nonnull
    private static VirtualFile getDefaultRoot(@Nonnull Project project, @Nonnull List<VirtualFile> roots, @Nullable VirtualFile[] vFiles) {
        if (vFiles != null) {
            for (VirtualFile file : vFiles) {
                VirtualFile root = GitUtil.gitRootOrNull(file);
                if (root != null) {
                    return root;
                }
            }
        }
        GitRepository currentRepository = GitBranchUtil.getCurrentRepository(project);
        return currentRepository != null ? currentRepository.getRoot() : roots.get(0);
    }

    protected final void runFinalTasks(
        @Nonnull Project project,
        @Nonnull GitVcs vcs,
        @Nonnull Set<VirtualFile> affectedRoots,
        @Nonnull LocalizeValue actionName,
        @Nonnull List<VcsException> exceptions
    ) {
        VirtualFileUtil.markDirty(true, false, ArrayUtil.toObjectArray(affectedRoots, VirtualFile.class));
        LocalFileSystem.getInstance().refreshFiles(
            affectedRoots,
            true,
            true,
            () -> {
                VcsFileUtil.markFilesDirty(project, affectedRoots);
                for (TransactionRunnable task : myDelayedTasks) {
                    task.run(exceptions);
                }
                myDelayedTasks.clear();
                vcs.showErrors(exceptions, actionName);
            }
        );
    }

    /**
     * Return true to indicate that the final tasks should be executed after the action invocation,
     * false if the task is responsible to call the final tasks manually via
     * {@link #runFinalTasks(Project, GitVcs, Set, LocalizeValue, List)}.
     */
    protected boolean executeFinalTasksSynchronously() {
        return true;
    }

    protected static boolean isRebasing(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project != null) {
            VirtualFile[] files = e.getData(VirtualFile.KEY_OF_ARRAY);
            if (files != null) {
                for (VirtualFile file : files) {
                    GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
                    if (isRebasing(manager.getRepositoryForFile(file))) {
                        return true;
                    }
                }
            }
            if (isRebasing(GitBranchUtil.getCurrentRepository(project))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRebasing(@Nullable GitRepository repository) {
        return repository != null && repository.getState() == Repository.State.REBASING;
    }

    /**
     * Get git roots for the project. The method shows dialogs in the case when roots cannot be retrieved, so it should be called
     * from the event dispatch thread.
     *
     * @param project the project
     * @param vcs     the git Vcs
     * @return the list of the roots, or null
     */
    @Nullable
    @RequiredUIAccess
    public static List<VirtualFile> getGitRoots(Project project, GitVcs vcs) {
        List<VirtualFile> roots;
        try {
            roots = GitUtil.getGitRoots(project, vcs);
        }
        catch (VcsException e) {
            Messages.showErrorDialog(project, e.getMessage(), GitLocalize.repositoryActionMissingRootsTitle().get());
            return null;
        }
        return roots;
    }

    /**
     * Delay task to be executed after refresh
     *
     * @param task the task to run
     */
    public final void delayTask(@Nonnull TransactionRunnable task) {
        myDelayedTasks.add(task);
    }

    /**
     * Get name of action (for error reporting)
     *
     * @return the name of action
     */
    @Nonnull
    protected abstract LocalizeValue getActionName();

    /**
     * Perform action for some repositories
     *
     * @param project       a context project
     * @param gitRoots      a git roots that affect the current project (sorted by {@link VirtualFile#getPresentableUrl()})
     * @param defaultRoot   a guessed default root (based on the currently selected file list)
     * @param affectedRoots a set of roots affected by the action
     * @param exceptions    a list of exceptions from running git
     * @throws VcsException if there is a problem with running git
     *                      (this exception is considered to be added to the end of the exception list)
     */
    protected abstract void perform(
        @Nonnull Project project,
        @Nonnull List<VirtualFile> gitRoots,
        @Nonnull VirtualFile defaultRoot,
        Set<VirtualFile> affectedRoots,
        List<VcsException> exceptions
    ) throws VcsException;

    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        super.update(e);
        boolean enabled = isEnabled(e);
        e.getPresentation().setEnabled(enabled);
        e.getPresentation().setVisible(!ActionPlaces.isPopupPlace(e.getPlace()) || enabled);
    }

    protected boolean isEnabled(AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        if (project == null) {
            return false;
        }
        GitVcs vcs = GitVcs.getInstance(project);
        VirtualFile[] roots = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs);
        return roots != null && roots.length != 0;
    }
}
