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
package git4idea.actions;

import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.document.FileDocumentManager;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionPlaces;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.action.Presentation;
import consulo.ui.ex.awt.UIUtil;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import consulo.virtualFileSystem.util.VirtualFileVisitor;
import git4idea.GitVcs;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static consulo.virtualFileSystem.util.VirtualFileVisitor.ONE_LEVEL_DEEP;
import static consulo.virtualFileSystem.util.VirtualFileVisitor.SKIP_ROOT;

/**
 * Basic abstract action handler for all Git actions to extend.
 */
public abstract class BasicAction extends DumbAwareAction {
    /**
     * {@inheritDoc}
     */
    @Override
    @RequiredUIAccess
    public void actionPerformed(@Nonnull AnActionEvent event) {
        final Project project = event.getRequiredData(Project.KEY);
        project.getApplication().runWriteAction(() -> FileDocumentManager.getInstance().saveAllDocuments());
        VirtualFile[] vFiles = event.getData(VirtualFile.KEY_OF_ARRAY);
        assert vFiles != null : "The action is only available when files are selected";

        GitVcs vcs = GitVcs.getInstance(project);
        if (!ProjectLevelVcsManager.getInstance(project).checkAllFilesAreUnder(vcs, vFiles)) {
            return;
        }
        final LocalizeValue actionName = getActionName();

        final VirtualFile[] affectedFiles = collectAffectedFiles(project, vFiles);
        final List<VcsException> exceptions = new ArrayList<>();
        boolean background = perform(project, vcs, exceptions, affectedFiles);
        if (!background) {
            GitVcs.runInBackground(new Task.Backgroundable(project, actionName) {
                @Override
                public void run(@Nonnull ProgressIndicator indicator) {
                    VirtualFileUtil.markDirtyAndRefresh(false, true, false, affectedFiles);
                    VcsFileUtil.markFilesDirty(project, Arrays.asList(affectedFiles));
                    UIUtil.invokeLaterIfNeeded(() -> GitUIUtil.showOperationErrors(project, exceptions, actionName));
                }
            });
        }
    }

    /**
     * Perform the action over set of files
     *
     * @param project       the context project
     * @param mksVcs        the vcs instance
     * @param exceptions    the list of exceptions to be collected.
     * @param affectedFiles the files to be affected by the operation
     * @return true if the operation scheduled a background job, or cleanup is not needed
     */
    protected abstract boolean perform(
        @Nonnull Project project,
        GitVcs mksVcs,
        @Nonnull List<VcsException> exceptions,
        @Nonnull VirtualFile[] affectedFiles
    );

    /**
     * given a list of action-target files, returns ALL the files that should be
     * subject to the action Does not keep directories, but recursively adds
     * directory contents
     *
     * @param project the project subject of the action
     * @param files   the root selection
     * @return the complete set of files this action should apply to
     */
    @Nonnull
    protected VirtualFile[] collectAffectedFiles(@Nonnull Project project, @Nonnull VirtualFile[] files) {
        List<VirtualFile> affectedFiles = new ArrayList<>(files.length);
        ProjectLevelVcsManager projectLevelVcsManager = ProjectLevelVcsManager.getInstance(project);
        for (VirtualFile file : files) {
            if (!file.isDirectory() && projectLevelVcsManager.getVcsFor(file) instanceof GitVcs) {
                affectedFiles.add(file);
            }
            else if (file.isDirectory() && isRecursive()) {
                addChildren(project, affectedFiles, file);
            }

        }
        return VirtualFileUtil.toVirtualFileArray(affectedFiles);
    }

    /**
     * recursively adds all the children of file to the files list, for which
     * this action makes sense ({@link #appliesTo(Project, VirtualFile)}
     * returns true)
     *
     * @param project the project subject of the action
     * @param files   result list
     * @param file    the file whose children should be added to the result list
     *                (recursively)
     */
    private void addChildren(@Nonnull final Project project, @Nonnull final List<VirtualFile> files, @Nonnull VirtualFile file) {
        VirtualFileUtil.visitChildrenRecursively(file, new VirtualFileVisitor(SKIP_ROOT, (isRecursive() ? null : ONE_LEVEL_DEEP)) {
            @Override
            public boolean visitFile(@Nonnull VirtualFile file) {
                if (!file.isDirectory() && appliesTo(project, file)) {
                    files.add(file);
                }
                return true;
            }
        });
    }

    /**
     * @return the name of action (it is used in a number of ui elements)
     */
    @Nonnull
    protected abstract LocalizeValue getActionName();

    /**
     * @return true if the action could be applied recursively
     */
    @SuppressWarnings({"MethodMayBeStatic"})
    protected boolean isRecursive() {
        return true;
    }

    /**
     * Check if the action is applicable to the file. The default checks if the file is a directory
     *
     * @param project the context project
     * @param file    the file to check
     * @return true if the action is applicable to the virtual file
     */
    @SuppressWarnings({"MethodMayBeStatic", "UnusedDeclaration"})
    protected boolean appliesTo(@Nonnull Project project, @Nonnull VirtualFile file) {
        return !file.isDirectory();
    }

    /**
     * Disable the action if the event does not apply in this context.
     *
     * @param e The update event
     */
    @Override
    public void update(@Nonnull AnActionEvent e) {
        super.update(e);
        Presentation presentation = e.getPresentation();
        Project project = e.getData(Project.KEY);
        if (project == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        VirtualFile[] vFiles = e.getData(VirtualFile.KEY_OF_ARRAY);
        if (vFiles == null || vFiles.length == 0) {
            presentation.setEnabled(false);
            presentation.setVisible(true);
            return;
        }
        GitVcs vcs = GitVcs.getInstance(project);
        boolean enabled = ProjectLevelVcsManager.getInstance(project).checkAllFilesAreUnder(vcs, vFiles) && isEnabled(project, vcs, vFiles);
        // only enable action if all the targets are under the vcs and the action supports all of them

        presentation.setEnabled(enabled);
        if (ActionPlaces.isPopupPlace(e.getPlace())) {
            presentation.setVisible(enabled);
        }
        else {
            presentation.setVisible(true);
        }
    }

    /**
     * Check if the action should be enabled for the set of the files
     *
     * @param project the context project
     * @param vcs     the vcs to use
     * @param vFiles  the set of files
     * @return true if the action should be enabled
     */
    protected abstract boolean isEnabled(@Nonnull Project project, @Nonnull GitVcs vcs, @Nonnull VirtualFile... vFiles);

    /**
     * Save all files in the application (the operation creates write action)
     */
    @RequiredUIAccess
    public static void saveAll() {
        Application.get().runWriteAction(() -> FileDocumentManager.getInstance().saveAllDocuments());
    }
}
