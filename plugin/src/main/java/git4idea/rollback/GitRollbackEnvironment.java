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
package git4idea.rollback;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.rollback.RollbackEnvironment;
import consulo.versionControlSystem.rollback.RollbackProgressListener;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandlerUtil;
import git4idea.commands.GitSimpleHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitUntrackedFilesHolder;
import git4idea.util.GitFileUtils;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.File;
import java.util.*;

/**
 * Git rollback/revert environment
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitRollbackEnvironment implements RollbackEnvironment {
    private final Project myProject;

    @Inject
    public GitRollbackEnvironment(@Nonnull Project project) {
        myProject = project;
    }

    @Nonnull
    @Override
    public String getRollbackOperationName() {
        return GitLocalize.revertActionName().get();
    }

    @Override
    public void rollbackModifiedWithoutCheckout(
        @Nonnull List<VirtualFile> files,
        List<VcsException> exceptions,
        RollbackProgressListener listener
    ) {
        throw new UnsupportedOperationException("Explicit file checkout is not supported by GIT.");
    }

    @Override
    public void rollbackMissingFileDeletion(
        @Nonnull List<FilePath> files,
        List<VcsException> exceptions,
        RollbackProgressListener listener
    ) {
        throw new UnsupportedOperationException("Missing file delete is not reported by GIT.");
    }

    @Override
    public void rollbackIfUnchanged(@Nonnull VirtualFile file) {
        // do nothing
    }

    @Override
    public void rollbackChanges(
        @Nonnull List<Change> changes,
        List<VcsException> exceptions,
        @Nonnull RollbackProgressListener listener
    ) {
        HashMap<VirtualFile, List<FilePath>> toUnindex = new HashMap<>();
        HashMap<VirtualFile, List<FilePath>> toUnversion = new HashMap<>();
        HashMap<VirtualFile, List<FilePath>> toRevert = new HashMap<>();
        List<FilePath> toDelete = new ArrayList<>();

        listener.determinate();
        // collect changes to revert
        for (Change c : changes) {
            switch (c.getType()) {
                case NEW:
                    // note that this the only change that could happen
                    // for HEAD-less working directories.
                    registerFile(toUnversion, c.getAfterRevision().getFile(), exceptions);
                    break;
                case MOVED:
                    registerFile(toRevert, c.getBeforeRevision().getFile(), exceptions);
                    registerFile(toUnindex, c.getAfterRevision().getFile(), exceptions);
                    toDelete.add(c.getAfterRevision().getFile());
                    break;
                case MODIFICATION:
                    // note that changes are also removed from index, if they got into index somehow
                    registerFile(toUnindex, c.getBeforeRevision().getFile(), exceptions);
                    registerFile(toRevert, c.getBeforeRevision().getFile(), exceptions);
                    break;
                case DELETED:
                    registerFile(toRevert, c.getBeforeRevision().getFile(), exceptions);
                    break;
            }
        }
        // unindex files
        for (Map.Entry<VirtualFile, List<FilePath>> entry : toUnindex.entrySet()) {
            listener.accept(entry.getValue());
            try {
                unindex(entry.getKey(), entry.getValue(), false);
            }
            catch (VcsException e) {
                exceptions.add(e);
            }
        }
        // unversion files
        for (Map.Entry<VirtualFile, List<FilePath>> entry : toUnversion.entrySet()) {
            listener.accept(entry.getValue());
            try {
                unindex(entry.getKey(), entry.getValue(), true);
            }
            catch (VcsException e) {
                exceptions.add(e);
            }
        }
        // delete files
        for (FilePath file : toDelete) {
            listener.accept(file);
            try {
                File ioFile = file.getIOFile();
                if (ioFile.exists() && !ioFile.delete()) {
                    //noinspection ThrowableInstanceNeverThrown
                    exceptions.add(new VcsException("Unable to delete file: " + file));
                }
            }
            catch (Exception e) {
                //noinspection ThrowableInstanceNeverThrown
                exceptions.add(new VcsException("Unable to delete file: " + file, e));
            }
        }
        // revert files from HEAD
        for (Map.Entry<VirtualFile, List<FilePath>> entry : toRevert.entrySet()) {
            listener.accept(entry.getValue());
            try {
                revert(entry.getKey(), entry.getValue());
            }
            catch (VcsException e) {
                exceptions.add(e);
            }
        }
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        Set<File> filesToRefresh = new HashSet<>();
        for (Change c : changes) {
            ContentRevision before = c.getBeforeRevision();
            if (before != null) {
                filesToRefresh.add(new File(before.getFile().getPath()));
            }
            ContentRevision after = c.getAfterRevision();
            if (after != null) {
                filesToRefresh.add(new File(after.getFile().getPath()));
            }
        }
        lfs.refreshIoFiles(filesToRefresh);

        for (GitRepository repo : GitUtil.getRepositoryManager(myProject).getRepositories()) {
            repo.update();
        }
    }

    /**
     * Reverts the list of files we are passed.
     *
     * @param root  the VCS root
     * @param files The array of files to revert.
     * @throws VcsException Id it breaks.
     */
    public void revert(VirtualFile root, List<FilePath> files) throws VcsException {
        for (List<String> paths : VcsFileUtil.chunkPaths(root, files)) {
            GitSimpleHandler handler = new GitSimpleHandler(myProject, root, GitCommand.CHECKOUT);
            handler.addParameters("HEAD");
            handler.endOptions();
            handler.addParameters(paths);
            handler.run();
        }
    }

    /**
     * Remove file paths from index (git remove --cached).
     *
     * @param root          a git root
     * @param files         files to remove from index.
     * @param toUnversioned passed true if the file will be unversioned after unindexing, i.e. it was added before the revert operation.
     * @throws VcsException if there is a problem with running git
     */
    private void unindex(VirtualFile root, List<FilePath> files, boolean toUnversioned) throws VcsException {
        GitFileUtils.delete(myProject, root, files, "--cached", "-f");

        if (toUnversioned) {
            GitRepository repo = GitUtil.getRepositoryManager(myProject).getRepositoryForRoot(root);
            GitUntrackedFilesHolder untrackedFilesHolder = (repo == null ? null : repo.getUntrackedFilesHolder());
            for (FilePath path : files) {
                VirtualFile vf = VcsUtil.getVirtualFile(path.getIOFile());
                if (untrackedFilesHolder != null && vf != null) {
                    untrackedFilesHolder.add(vf);
                }
            }
        }
    }

    /**
     * Register file in the map under appropriate root
     *
     * @param files      a map to use
     * @param file       a file to register
     * @param exceptions the list of exceptions to update
     */
    private static void registerFile(Map<VirtualFile, List<FilePath>> files, FilePath file, List<VcsException> exceptions) {
        VirtualFile root;
        try {
            root = GitUtil.getGitRoot(file);
        }
        catch (VcsException e) {
            exceptions.add(e);
            return;
        }
        List<FilePath> paths = files.get(root);
        if (paths == null) {
            paths = new ArrayList<>();
            files.put(root, paths);
        }
        paths.add(file);
    }

    /**
     * Get instance of the service
     *
     * @param project a context project
     * @return a project-specific instance of the service
     */
    public static GitRollbackEnvironment getInstance(Project project) {
        return ServiceManager.getService(project, GitRollbackEnvironment.class);
    }

    public static void resetHardLocal(Project project, VirtualFile root) {
        GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.RESET);
        handler.addParameters("--hard");
        handler.endOptions();
        GitHandlerUtil.runInCurrentThread(handler, null);
    }
}
