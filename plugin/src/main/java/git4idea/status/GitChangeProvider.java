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
package git4idea.status;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.document.Document;
import consulo.document.FileDocumentManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.action.VcsContextFactory;
import consulo.versionControlSystem.change.*;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import git4idea.GitContentRevision;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.*;

/**
 * Git repository change provider
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitChangeProvider implements ChangeProvider {

    private static final Logger LOG = Logger.getInstance("#GitStatus");

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final Git myGit;
    @Nonnull
    private final ChangeListManager myChangeListManager;
    @Nonnull
    private final FileDocumentManager myFileDocumentManager;
    @Nonnull
    private final ProjectLevelVcsManager myVcsManager;

    @Inject
    public GitChangeProvider(@Nonnull Project project, @Nonnull Git git, @Nonnull ChangeListManager changeListManager,
                             @Nonnull FileDocumentManager fileDocumentManager, @Nonnull ProjectLevelVcsManager vcsManager) {
        myProject = project;
        myGit = git;
        myChangeListManager = changeListManager;
        myFileDocumentManager = fileDocumentManager;
        myVcsManager = vcsManager;
    }

    @Override
    public void getChanges(final VcsDirtyScope dirtyScope,
                           final ChangelistBuilder builder,
                           final ProgressIndicator progress,
                           final ChangeListManagerGate addGate) throws VcsException {
        final GitVcs vcs = GitVcs.getInstance(myProject);
        if (vcs == null) {
            // already disposed or not yet initialized => ignoring
            return;
        }

        appendNestedVcsRootsToDirt(dirtyScope, vcs, myVcsManager);

        final Collection<VirtualFile> affected = dirtyScope.getAffectedContentRoots();
        Collection<VirtualFile> roots = GitUtil.gitRootsForPaths(affected);

        try {
            final NonChangedHolder holder = new NonChangedHolder(myProject, addGate,
                myFileDocumentManager);
            for (VirtualFile root : roots) {
                debug("checking root: " + root.getPath());
                GitNewChangesCollector collector = GitNewChangesCollector.collect(myProject, myGit, myChangeListManager, myVcsManager,
                    vcs, dirtyScope, root);
                final Collection<Change> changes = collector.getChanges();
                holder.markHeadRevision(root, collector.getHead());

                for (Change file : changes) {
                    FilePath filePath = ChangesUtil.getFilePath(file);
                    debug("process change: " + filePath.getPath());
                    builder.processChange(file, GitVcs.getKey());

                    holder.markPathProcessed(filePath);
                }

                for (VirtualFile f : collector.getUnversionedFiles()) {
                    builder.processUnversionedFile(f);
                }

                holder.feedBuilder(dirtyScope, builder);
            }
        }
        catch (VcsException e) {
            LOG.info(e);
            // most probably the error happened because git is not configured
            vcs.getExecutableValidator().showNotificationOrThrow(e);
        }
    }

    public static void appendNestedVcsRootsToDirt(final VcsDirtyScope dirtyScope, GitVcs vcs, final ProjectLevelVcsManager vcsManager) {
        final Set<FilePath> recursivelyDirtyDirectories = dirtyScope.getRecursivelyDirtyDirectories();
        if (recursivelyDirtyDirectories.isEmpty()) {
            return;
        }

        final LocalFileSystem lfs = LocalFileSystem.getInstance();
        final Set<VirtualFile> rootsUnderGit = new HashSet<VirtualFile>(Arrays.asList(vcsManager.getRootsUnderVcs(vcs)));
        final Set<VirtualFile> inputColl = new HashSet<VirtualFile>(rootsUnderGit);
        final Set<VirtualFile> existingInScope = new HashSet<VirtualFile>();
        for (FilePath dir : recursivelyDirtyDirectories) {
            VirtualFile vf = dir.getVirtualFile();
            if (vf == null) {
                vf = lfs.findFileByIoFile(dir.getIOFile());
            }
            if (vf == null) {
                vf = lfs.refreshAndFindFileByIoFile(dir.getIOFile());
            }
            if (vf != null) {
                existingInScope.add(vf);
            }
        }
        inputColl.addAll(existingInScope);
        FileUtil.removeAncestors(inputColl, o -> o.getPath(), (parent, child) -> {
                if (!existingInScope.contains(child) && existingInScope.contains(parent)) {
                    debug("adding git root for check: " + child.getPath());
                    ((VcsModifiableDirtyScope) dirtyScope).addDirtyDirRecursively(VcsContextFactory.getInstance().createFilePathOn(child));
                }
                return true;
            }
        );
    }

    /**
     * Common debug logging method for all Git status related operations.
     * Primarily used for measuring performance and tracking calls to heavy methods.
     */
    public static void debug(String message) {
        LOG.debug(message);
    }

    private static class NonChangedHolder {
        private final Project myProject;
        private final ChangeListManagerGate myAddGate;
        private FileDocumentManager myFileDocumentManager;

        private final Set<FilePath> myProcessedPaths = new HashSet<>();
        private final Map<VirtualFile, VcsRevisionNumber> myHeadRevisions = new HashMap<>();

        private NonChangedHolder(Project project,
                                 ChangeListManagerGate addGate,
                                 FileDocumentManager fileDocumentManager) {
            myProject = project;
            myAddGate = addGate;
            myFileDocumentManager = fileDocumentManager;
        }

        public void markPathProcessed(@Nonnull FilePath path) {
            myProcessedPaths.add(path);
        }

        public void markHeadRevision(@Nonnull VirtualFile root, @Nonnull VcsRevisionNumber revision) {
            myHeadRevisions.put(root, revision);
        }

        public void feedBuilder(@Nonnull VcsDirtyScope dirtyScope, final ChangelistBuilder builder) throws VcsException {
            final VcsKey gitKey = GitVcs.getKey();

            for (Document document : myFileDocumentManager.getUnsavedDocuments()) {
                VirtualFile vf = myFileDocumentManager.getFile(document);
                if (vf == null || !vf.isValid()) {
                    continue;
                }
                if (!myFileDocumentManager.isFileModified(vf)) {
                    continue;
                }
                if (myAddGate.getStatus(vf) != null) {
                    continue;
                }

                VirtualFile vcsFile = vf;// TODO VcsUtil.resolveSymlinkIfNeeded(myProject, vf);
                FilePath filePath = VcsUtil.getFilePath(vcsFile);
                if (myProcessedPaths.contains(filePath)) {
                    continue;
                }
                if (!dirtyScope.belongsTo(filePath)) {
                    continue;
                }

                GitRepository repository = GitRepositoryManager.getInstance(myProject).getRepositoryForFile(vcsFile);
                if (repository == null) {
                    continue;
                }
                if (repository.getUntrackedFilesHolder().containsUntrackedFile(vf)) {
                    continue;
                }
                if (repository.getUntrackedFilesHolder().containsIgnoredFile(vf)) {
                    continue;
                }

                VirtualFile root = repository.getRoot();
                VcsRevisionNumber beforeRevisionNumber = myHeadRevisions.get(root);
                if (beforeRevisionNumber == null) {
                    beforeRevisionNumber = GitNewChangesCollector.getHead(repository);
                    myHeadRevisions.put(root, beforeRevisionNumber);
                }

                Change change = new Change(GitContentRevision.createRevision(vf, beforeRevisionNumber, myProject),
                    GitContentRevision.createRevision(vf, null, myProject), FileStatus.MODIFIED);

                LOG.debug("process in-memory change ", change);
                builder.processChange(change, gitKey);
            }
        }
    }

    @Override
    public boolean isModifiedDocumentTrackingRequired() {
        return true;
    }

    @Override
    public void doCleanup(final List<VirtualFile> files) {
    }
}
