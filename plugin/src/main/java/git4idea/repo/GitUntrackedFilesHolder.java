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
package git4idea.repo;

import consulo.component.messagebus.MessageBusConnection;
import consulo.disposer.Disposable;
import consulo.application.Application;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.change.VcsDirtyScopeManager;
import consulo.versionControlSystem.change.VcsManagedFilesHolderListener;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.event.*;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.ignore.GitRepositoryIgnoredFilesHolder;
import git4idea.status.GitNewChangesCollector;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

/**
 * <p>Stores files which are untracked by the Git repository.
 * Should be updated by calling {@link #add(VirtualFile)} and {@link #remove(Collection)}
 * whenever the list of unversioned files changes.
 * Able to get the list of unversioned files from Git.</p>
 *
 * <p>This class is used by {@link GitNewChangesCollector}.
 * By keeping track of unversioned files in the Git repository we may invoke
 * <code>'git status --porcelain --untracked-files=no'</code> which gives a significant speed boost: the command gets more than twice
 * faster, because it doesn't need to seek for untracked files.</p>
 *
 * <p>"Keeping track" means the following:
 * <ul>
 * <li>
 * Once a file is created, it is added to untracked (by this class).
 * Once a file is deleted, it is removed from untracked.
 * </li>
 * <li>
 * Once a file is added to the index, it is removed from untracked.
 * Once it is removed from the index, it is added to untracked.
 * </li>
 * </ul>
 * </p>
 *
 * <p>In some cases (file creation/deletion) the file is not silently added/removed from the list - instead the file is marked as
 * "possibly untracked" and Git is asked for the exact status of this file.
 * It is needed, since the file may be created and added to the index independently, and events may race.</p>
 *
 * <p>Also, if .git/index changes, then a full refresh is initiated. The reason is not only untracked files tracking, but also handling
 * committing outside IDEA, etc.</p>
 *
 * <p>Synchronization policy used in this class:<br/>
 * myDefinitelyUntrackedFiles is accessed under the myDefinitelyUntrackedFiles lock.<br/>
 * myPossiblyUntrackedFiles and myReady is accessed under the LOCK lock.<br/>
 * This is done so, because the latter two variables are accessed from the AWT in after() and we don't want to lock the AWT long,
 * while myDefinitelyUntrackedFiles is modified along with native request to Git.</p>
 *
 * @author Kirill Likhodedov
 */
public class GitUntrackedFilesHolder implements Disposable, BulkFileListener {

    private static final Logger LOG = Logger.getInstance(GitUntrackedFilesHolder.class);

    private final Project myProject;
    private final VirtualFile myRoot;
    private final GitRepository myRepository;
    private final ChangeListManager myChangeListManager;
    private final VcsDirtyScopeManager myDirtyScopeManager;
    private final ProjectLevelVcsManager myVcsManager;
    private final GitRepositoryFiles myRepositoryFiles;
    private final Git myGit;

    private final Set<VirtualFile> myDefinitelyUntrackedFiles = new HashSet<>();
    private final Set<VirtualFile> myPossiblyUntrackedFiles = new HashSet<>();
    private boolean myReady;   // if false, total refresh is needed
    private final Object LOCK = new Object();
    private final GitRepositoryManager myRepositoryManager;

    private final MyGitRepositoryIgnoredFilesHolder myIgnoredFilesHolder = new MyGitRepositoryIgnoredFilesHolder();

    GitUntrackedFilesHolder(@Nonnull GitRepository repository, @Nonnull GitRepositoryFiles gitFiles) {
        myProject = repository.getProject();
        myRepository = repository;
        myRoot = repository.getRoot();
        myChangeListManager = ChangeListManager.getInstance(myProject);
        myDirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject);
        myGit = Application.get().getInstance(Git.class);
        myVcsManager = ProjectLevelVcsManager.getInstance(myProject);

        myRepositoryManager = GitUtil.getRepositoryManager(myProject);
        myRepositoryFiles = gitFiles;
    }

    void setupVfsListener(@Nonnull Project project) {
        if (!project.isDisposed()) {
            MessageBusConnection connection = project.getMessageBus().connect(this);
            connection.subscribe(BulkFileListener.class, this);
            myIgnoredFilesHolder.scheduleUpdate();
        }
    }

    public boolean containsUntrackedFile(VirtualFile file) {
        synchronized (LOCK) {
            return myDefinitelyUntrackedFiles.contains(file) || myPossiblyUntrackedFiles.contains(file);
        }
    }

    public boolean containsIgnoredFile(VirtualFile file) {
        return myIgnoredFilesHolder.containsFile(VcsUtil.getFilePath(file));
    }

    @Nonnull
    public GitRepositoryIgnoredFilesHolder getIgnoredFilesHolder() {
        return myIgnoredFilesHolder;
    }

    @Override
    public void dispose() {
        synchronized (myDefinitelyUntrackedFiles) {
            myDefinitelyUntrackedFiles.clear();
        }
        synchronized (LOCK) {
            myPossiblyUntrackedFiles.clear();
        }
        myIgnoredFilesHolder.clear();
    }

    /**
     * Adds the file to the list of untracked.
     */
    public void add(@Nonnull VirtualFile file) {
        synchronized (myDefinitelyUntrackedFiles) {
            myDefinitelyUntrackedFiles.add(file);
        }
    }

    /**
     * Adds several files to the list of untracked.
     */
    public void add(@Nonnull Collection<VirtualFile> files) {
        synchronized (myDefinitelyUntrackedFiles) {
            myDefinitelyUntrackedFiles.addAll(files);
        }
    }

    /**
     * Removes several files from untracked.
     */
    public void remove(@Nonnull Collection<VirtualFile> files) {
        synchronized (myDefinitelyUntrackedFiles) {
            myDefinitelyUntrackedFiles.removeAll(files);
        }
    }

    /**
     * Returns the list of unversioned files.
     * This method may be slow, if the full-refresh of untracked files is needed.
     *
     * @return untracked files.
     * @throws VcsException if there is an unexpected error during Git execution.
     */
    @Nonnull
    public Collection<VirtualFile> retrieveUntrackedFiles() throws VcsException {
        if (isReady()) {
            verifyPossiblyUntrackedFiles();
        }
        else {
            rescanAll();
        }
        synchronized (myDefinitelyUntrackedFiles) {
            return new ArrayList<>(myDefinitelyUntrackedFiles);
        }
    }

    public void invalidate() {
        synchronized (LOCK) {
            myReady = false;
        }
        myIgnoredFilesHolder.scheduleUpdate();
    }

    /**
     * Resets the list of untracked files after retrieving the full list of them from Git.
     */
    private void rescanAll() throws VcsException {
        Set<VirtualFile> untrackedFiles = myGit.untrackedFiles(myProject, myRoot, null);
        synchronized (myDefinitelyUntrackedFiles) {
            myDefinitelyUntrackedFiles.clear();
            myDefinitelyUntrackedFiles.addAll(untrackedFiles);
        }
        synchronized (LOCK) {
            myPossiblyUntrackedFiles.clear();
            myReady = true;
        }
    }

    /**
     * @return <code>true</code> if untracked files list is initialized and being kept up-to-date, <code>false</code> if full refresh is needed.
     */
    private boolean isReady() {
        synchronized (LOCK) {
            return myReady;
        }
    }

    /**
     * Queries Git to check the status of {@code myPossiblyUntrackedFiles} and moves them to {@code myDefinitelyUntrackedFiles}.
     */
    private void verifyPossiblyUntrackedFiles() throws VcsException {
        Set<VirtualFile> suspiciousFiles = new HashSet<>();
        synchronized (LOCK) {
            suspiciousFiles.addAll(myPossiblyUntrackedFiles);
            myPossiblyUntrackedFiles.clear();
        }

        synchronized (myDefinitelyUntrackedFiles) {
            Set<VirtualFile> untrackedFiles = myGit.untrackedFiles(myProject, myRoot, suspiciousFiles);
            suspiciousFiles.removeAll(untrackedFiles);
            // files that were suspicious (and thus passed to 'git ls-files'), but are not untracked, are definitely tracked.
            @SuppressWarnings("UnnecessaryLocalVariable") Set<VirtualFile> trackedFiles = suspiciousFiles;

            myDefinitelyUntrackedFiles.addAll(untrackedFiles);
            myDefinitelyUntrackedFiles.removeAll(trackedFiles);
        }
    }

    @Override
    public void before(@Nonnull List<? extends VFileEvent> events) {
    }

    @Override
    public void after(@Nonnull List<? extends VFileEvent> events) {
        boolean allChanged = false;
        Set<VirtualFile> filesToRefresh = new HashSet<>();

        for (VFileEvent event : events) {
            if (allChanged) {
                break;
            }
            String path = event.getPath();
            if (totalRefreshNeeded(path)) {
                allChanged = true;
            }
            else {
                VirtualFile affectedFile = getAffectedFile(event);
                if (notIgnored(affectedFile)) {
                    filesToRefresh.add(affectedFile);
                }
            }
        }

        // if index has changed, no need to refresh specific files - we get the full status of all files
        if (allChanged) {
            LOG.debug(String.format("GitUntrackedFilesHolder: total refresh is needed, marking %s recursively dirty", myRoot));
            myDirtyScopeManager.dirDirtyRecursively(myRoot);
            synchronized (LOCK) {
                myReady = false;
            }
        }
        else {
            synchronized (LOCK) {
                myPossiblyUntrackedFiles.addAll(filesToRefresh);
            }
        }
    }

    private boolean totalRefreshNeeded(@Nonnull String path) {
        return indexChanged(path) || externallyCommitted(path) || headMoved(path) ||
            headChanged(path) || currentBranchChanged(path) || gitignoreChanged(path);
    }

    private boolean headChanged(@Nonnull String path) {
        return myRepositoryFiles.isHeadFile(path);
    }

    private boolean currentBranchChanged(@Nonnull String path) {
        GitLocalBranch currentBranch = myRepository.getCurrentBranch();
        return currentBranch != null && myRepositoryFiles.isBranchFile(path, currentBranch.getFullName());
    }

    private boolean headMoved(@Nonnull String path) {
        return myRepositoryFiles.isOrigHeadFile(path);
    }

    private boolean indexChanged(@Nonnull String path) {
        return myRepositoryFiles.isIndexFile(path);
    }

    private boolean externallyCommitted(@Nonnull String path) {
        return myRepositoryFiles.isCommitMessageFile(path);
    }

    private boolean gitignoreChanged(@Nonnull String path) {
        // TODO watch file stored in core.excludesfile
        return path.endsWith(".gitignore") || myRepositoryFiles.isExclude(path);
    }

    @Nullable
    private static VirtualFile getAffectedFile(@Nonnull VFileEvent event) {
        if (event instanceof VFileCreateEvent || event instanceof VFileDeleteEvent || event instanceof VFileMoveEvent || isRename(event)) {
            return event.getFile();
        }
        else if (event instanceof VFileCopyEvent copyEvent) {
            return copyEvent.getNewParent().findChild(copyEvent.getNewChildName());
        }
        return null;
    }

    private static boolean isRename(@Nonnull VFileEvent event) {
        return event instanceof VFilePropertyChangeEvent propertyChangeEvent
            && propertyChangeEvent.getPropertyName().equals(VirtualFile.PROP_NAME);
    }

    private boolean notIgnored(@Nullable VirtualFile file) {
        return file != null && belongsToThisRepository(file) && !myChangeListManager.isIgnoredFile(file);
    }

    private boolean belongsToThisRepository(VirtualFile file) {
        // this check should be quick
        // we shouldn't create a full instance repository here because it may lead to SOE while many unversioned files will be processed
        GitRepository repository = myRepositoryManager.getRepositoryForRootQuick(myVcsManager.getVcsRootFor(file));
        return repository != null && repository.getRoot().equals(myRoot);
    }

    /**
     * Per-repository holder for ignored files.
     * Loaded asynchronously in background; {@link #containsFile} returns false until the first load completes.
     * Matches the role of {@code MyGitRepositoryIgnoredFilesHolder} from JetBrains' {@code GitUntrackedFilesHolder.kt}.
     */
    private class MyGitRepositoryIgnoredFilesHolder extends GitRepositoryIgnoredFilesHolder {
        private final Object IGNORED_LOCK = new Object();
        private volatile Set<FilePath> myIgnoredPaths = Collections.emptySet();
        private volatile boolean myInitialized = false;
        private volatile boolean myInUpdateMode = false;

        @Override
        public Set<FilePath> getIgnoredFilePaths() {
            return Collections.unmodifiableSet(myIgnoredPaths);
        }

        @Override
        public boolean isInitialized() {
            return myInitialized;
        }

        @Override
        public boolean isInUpdateMode() {
            return myInUpdateMode;
        }

        @Override
        public boolean containsFile(@Nonnull FilePath file) {
            Set<FilePath> paths = myIgnoredPaths;
            if (paths.isEmpty()) return false;
            FilePath parent = file;
            while (parent != null) {
                if (paths.contains(parent)) return true;
                parent = parent.getParentPath();
            }
            return false;
        }

        @Override
        public void removeIgnoredFiles(@Nonnull Collection<FilePath> filePaths) {
            synchronized (IGNORED_LOCK) {
                Set<FilePath> newSet = new HashSet<>(myIgnoredPaths);
                newSet.removeAll(filePaths);
                myIgnoredPaths = Collections.unmodifiableSet(newSet);
            }
            scheduleUpdate();
        }

        void scheduleUpdate() {
            if (myInUpdateMode || myProject.isDisposed()) return;
            myInUpdateMode = true;
            myProject.getApplication().executeOnPooledThread(() -> {
                try {
                    // Use path-based FilePaths so files not yet in the VFS are still tracked
                    Set<FilePath> newPaths = myGit.ignoredFilePaths(myProject, myRoot);
                    synchronized (IGNORED_LOCK) {
                        myIgnoredPaths = Collections.unmodifiableSet(newPaths);
                        myInitialized = true;
                    }
                    // Notify ChangeListManager to refresh file statuses now that ignored paths are updated
                    if (!myProject.isDisposed()) {
                        myProject.getMessageBus().syncPublisher(VcsManagedFilesHolderListener.class).updatingModeChanged();
                    }
                }
                catch (VcsException e) {
                    LOG.warn("Failed to collect ignored files for " + myRoot.getPath(), e);
                }
                finally {
                    myInUpdateMode = false;
                }
            });
        }

        void clear() {
            synchronized (IGNORED_LOCK) {
                myIgnoredPaths = Collections.emptySet();
                myInitialized = false;
            }
        }
    }
}
