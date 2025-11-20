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
package git4idea.vfs;

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.Platform;
import consulo.project.Project;
import consulo.project.ui.util.AppUIUtil;
import consulo.util.io.FileUtil;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsVFSListener;
import consulo.versionControlSystem.update.RefreshVFsSynchronously;
import consulo.versionControlSystem.util.ObjectsConvertor;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandler;
import git4idea.commands.GitSimpleHandler;
import git4idea.util.GitFileUtils;
import git4idea.util.GitVcsConsoleWriter;
import jakarta.annotation.Nonnull;

import java.io.File;
import java.util.*;
import java.util.function.Function;

import static consulo.util.collection.ContainerUtil.map2Map;

public class GitVFSListener extends VcsVFSListener {
    private final Git myGit;

    public GitVFSListener(Project project, GitVcs vcs, Git git) {
        super(project, vcs);
        myGit = git;
    }

    @Nonnull
    @Override
    protected LocalizeValue getAddTitleValue() {
        return GitLocalize.vfsListenerAddTitle();
    }

    @Nonnull
    @Override
    protected LocalizeValue getSingleFileAddTitleValue() {
        return GitLocalize.vfsListenerAddSingleTitle();
    }

    @Nonnull
    @Override
    protected Function<Object, LocalizeValue> getSingleFileAddPromptGenerator() {
        return GitLocalize::vfsListenerAddSinglePrompt;
    }

    @Override
    protected void executeAdd(@Nonnull final List<VirtualFile> addedFiles, @Nonnull final Map<VirtualFile, VirtualFile> copiedFiles) {
        // Filter added files before further processing
        Map<VirtualFile, List<VirtualFile>> sortedFiles;
        try {
            sortedFiles = GitUtil.sortFilesByGitRoot(addedFiles, true);
        }
        catch (VcsException e) {
            throw new RuntimeException("The exception is not expected here", e);
        }
        final Set<VirtualFile> retainedFiles = new HashSet<>();
        ProgressManager progressManager = ProgressManager.getInstance();
        progressManager.run(new Task.Backgroundable(myProject, GitLocalize.vfsListenerCheckingIgnored(), true) {
            @Override
            public void run(@Nonnull ProgressIndicator pi) {
                for (Map.Entry<VirtualFile, List<VirtualFile>> e : sortedFiles.entrySet()) {
                    VirtualFile root = e.getKey();
                    List<VirtualFile> files = e.getValue();
                    pi.setText(root.getPresentableUrl());
                    try {
                        retainedFiles.addAll(myGit.untrackedFiles((Project) myProject, root, files));
                    }
                    catch (VcsException ex) {
                        GitVcsConsoleWriter.getInstance((Project) myProject).showMessage(ex.getMessage());
                    }
                }
                addedFiles.retainAll(retainedFiles);

                AppUIUtil.invokeLaterIfProjectAlive((Project) myProject, () -> originalExecuteAdd(addedFiles, copiedFiles));
            }
        });
    }

    /**
     * The version of execute add before overriding
     *
     * @param addedFiles  the added files
     * @param copiedFiles the copied files
     */
    private void originalExecuteAdd(List<VirtualFile> addedFiles, Map<VirtualFile, VirtualFile> copiedFiles) {
        super.executeAdd(addedFiles, copiedFiles);
    }

    @Override
    protected void performAdding(Collection<VirtualFile> addedFiles, Map<VirtualFile, VirtualFile> copyFromMap) {
        // copied files (copyFromMap) are ignored, because they are included into added files.
        performAdding(ObjectsConvertor.vf2fp(new ArrayList<>(addedFiles)));
    }

    private GitVcs gitVcs() {
        return ((GitVcs) myVcs);
    }

    private void performAdding(Collection<FilePath> filesToAdd) {
        performBackgroundOperation(filesToAdd, GitLocalize.addAdding(), new LongOperationPerRootExecutor() {
            @Override
            public void execute(@Nonnull VirtualFile root, @Nonnull List<FilePath> files) throws VcsException {
                LOG.debug("Git: adding files: " + files);
                GitFileUtils.addPaths(myProject, root, files);
                VcsFileUtil.markFilesDirty(myProject, files);
            }

            @Override
            public Collection<File> getFilesToRefresh() {
                return Collections.emptyList();
            }
        });
    }

    @Nonnull
    @Override
    protected LocalizeValue getDeleteTitleValue() {
        return GitLocalize.vfsListenerDeleteTitle();
    }

    @Nonnull
    @Override
    protected LocalizeValue getSingleFileDeleteTitleValue() {
        return GitLocalize.vfsListenerDeleteSingleTitle();
    }

    @Nonnull
    @Override
    protected Function<Object, LocalizeValue> getSingleFileDeletePromptGenerator() {
        return GitLocalize::vfsListenerDeleteSinglePrompt;
    }

    @Override
    protected void performDeletion(List<FilePath> filesToDelete) {
        performBackgroundOperation(
            filesToDelete,
            GitLocalize.removeRemoving(),
            new LongOperationPerRootExecutor() {
                Set<File> filesToRefresh = new HashSet<>();

                @Override
                public void execute(@Nonnull VirtualFile root, @Nonnull List<FilePath> files) throws VcsException {
                    GitFileUtils.delete(myProject, root, files, "--ignore-unmatch", "--cached");
                    if (!myProject.isDisposed()) {
                        VcsFileUtil.markFilesDirty(myProject, files);
                    }
                    File rootFile = new File(root.getPath());
                    for (FilePath p : files) {
                        for (File f = p.getIOFile(); f != null && !FileUtil.filesEqual(f, rootFile); f = f.getParentFile()) {
                            filesToRefresh.add(f);
                        }
                    }
                }

                @Override
                public Collection<File> getFilesToRefresh() {
                    return filesToRefresh;
                }
            }
        );
    }

    @Override
    protected void performMoveRename(List<MovedFileInfo> movedFiles) {
        List<FilePath> toAdd = new ArrayList<>();
        List<FilePath> toRemove = new ArrayList<>();
        List<MovedFileInfo> toForceMove = new ArrayList<>();
        for (MovedFileInfo movedInfo : movedFiles) {
            String oldPath = movedInfo.myOldPath;
            String newPath = movedInfo.myNewPath;
            if (!Platform.current().fs().isCaseSensitive() && GitUtil.isCaseOnlyChange(oldPath, newPath)) {
                toForceMove.add(movedInfo);
            }
            else {
                toRemove.add(VcsUtil.getFilePath(oldPath));
                toAdd.add(VcsUtil.getFilePath(newPath));
            }
        }
        performAdding(toAdd);
        performDeletion(toRemove);
        performForceMove(toForceMove);
    }

    private void performForceMove(@Nonnull List<MovedFileInfo> files) {
        Map<FilePath, MovedFileInfo> filesToMove = map2Map(files, (info) -> Pair.create(VcsUtil.getFilePath(info.myNewPath), info));
        Set<File> toRefresh = new HashSet<>();
        performBackgroundOperation(
            filesToMove.keySet(),
            GitLocalize.progressTitleMovingFiles(),
            new LongOperationPerRootExecutor() {
                @Override
                public void execute(@Nonnull VirtualFile root, @Nonnull List<FilePath> files) throws VcsException {
                    for (FilePath file : files) {
                        GitHandler h = new GitSimpleHandler(myProject, root, GitCommand.MV);
                        MovedFileInfo info = filesToMove.get(file);
                        h.addParameters("-f", info.myOldPath, info.myNewPath);
                        h.runInCurrentThread(null);
                        toRefresh.add(new File(info.myOldPath));
                        toRefresh.add(new File(info.myNewPath));
                    }
                }

                @Override
                public Collection<File> getFilesToRefresh() {
                    return toRefresh;
                }
            }
        );
    }

    @Override
    protected boolean isRecursiveDeleteSupported() {
        return true;
    }

    @Override
    protected boolean isFileCopyingFromTrackingSupported() {
        return false;
    }

    @Nonnull
    @Override
    protected List<FilePath> selectFilePathsToDelete(List<FilePath> deletedFiles) {
        // For git asking about vcs delete does not make much sense. The result is practically identical.
        return deletedFiles;
    }

    private void performBackgroundOperation(
        @Nonnull Collection<FilePath> files,
        @Nonnull LocalizeValue operationTitle,
        @Nonnull LongOperationPerRootExecutor executor
    ) {
        Map<VirtualFile, List<FilePath>> sortedFiles;
        try {
            sortedFiles = GitUtil.sortFilePathsByGitRoot(files, true);
        }
        catch (VcsException e) {
            GitVcsConsoleWriter.getInstance(myProject).showMessage(e.getMessage());
            return;
        }

        GitVcs.runInBackground(new Task.Backgroundable(myProject, operationTitle) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                for (Map.Entry<VirtualFile, List<FilePath>> e : sortedFiles.entrySet()) {
                    try {
                        executor.execute(e.getKey(), e.getValue());
                    }
                    catch (VcsException ex) {
                        GitVcsConsoleWriter.getInstance((Project) myProject).showMessage(ex.getMessage());
                    }
                }
                RefreshVFsSynchronously.refreshFiles(executor.getFilesToRefresh());
            }
        });
    }

    private interface LongOperationPerRootExecutor {
        void execute(@Nonnull VirtualFile root, @Nonnull List<FilePath> files) throws VcsException;

        Collection<File> getFilesToRefresh();
    }
}
