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
package git4idea.repo;

import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.project.Project;
import consulo.versionControlSystem.distributed.repository.RepositoryImpl;
import consulo.versionControlSystem.util.StopWatch;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchesCollection;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.util.Collection;

import static consulo.util.lang.ObjectUtil.assertNotNull;
import static consulo.versionControlSystem.distributed.DvcsUtil.getShortRepositoryName;

public class GitRepositoryImpl extends RepositoryImpl implements GitRepository {
    @Nonnull
    private final GitVcs myVcs;
    @Nonnull
    private final GitRepositoryReader myReader;
    @Nonnull
    private final VirtualFile myGitDir;
    @Nonnull
    private final GitRepositoryFiles myRepositoryFiles;

    @Nullable
    private final GitUntrackedFilesHolder myUntrackedFilesHolder;

    @Nonnull
    private volatile GitRepoInfo myInfo;

    private GitRepositoryImpl(
        @Nonnull VirtualFile rootDir,
        @Nonnull VirtualFile gitDir,
        @Nonnull Project project,
        @Nonnull Disposable parentDisposable,
        boolean light
    ) {
        super(project, rootDir, parentDisposable);
        myVcs = assertNotNull(GitVcs.getInstance(project));
        myGitDir = gitDir;
        myRepositoryFiles = GitRepositoryFiles.getInstance(gitDir);
        myReader = new GitRepositoryReader(myRepositoryFiles);
        myInfo = readRepoInfo();
        if (!light) {
            myUntrackedFilesHolder = new GitUntrackedFilesHolder(this, myRepositoryFiles);
            Disposer.register(this, myUntrackedFilesHolder);
        }
        else {
            myUntrackedFilesHolder = null;
        }
    }

    @Nonnull
    public static GitRepository getInstance(@Nonnull VirtualFile root, @Nonnull Project project, boolean listenToRepoChanges) {
        return getInstance(root, assertNotNull(GitUtil.findGitDir(root)), project, listenToRepoChanges);
    }

    @Nonnull
    public static GitRepository getInstance(
        @Nonnull VirtualFile root,
        @Nonnull VirtualFile gitDir,
        @Nonnull Project project,
        boolean listenToRepoChanges
    ) {
        GitRepositoryImpl repository = new GitRepositoryImpl(root, gitDir, project, project, !listenToRepoChanges);
        if (listenToRepoChanges) {
            repository.getUntrackedFilesHolder().setupVfsListener(project);
            repository.setupUpdater();
            notifyListenersAsync(repository);
        }
        return repository;
    }

    private void setupUpdater() {
        GitRepositoryUpdater updater = new GitRepositoryUpdater(this, myRepositoryFiles);
        Disposer.register(this, updater);
    }

    @Deprecated
    @Nonnull
    @Override
    public VirtualFile getGitDir() {
        return myGitDir;
    }

    @Nonnull
    @Override
    public GitRepositoryFiles getRepositoryFiles() {
        return myRepositoryFiles;
    }

    @Nonnull
    @Override
    public GitUntrackedFilesHolder getUntrackedFilesHolder() {
        if (myUntrackedFilesHolder == null) {
            throw new IllegalStateException("Using untracked files holder with light git repository instance " + this);
        }
        return myUntrackedFilesHolder;
    }

    @Nonnull
    @Override
    public GitRepoInfo getInfo() {
        return myInfo;
    }

    @Nullable
    @Override
    public GitLocalBranch getCurrentBranch() {
        return myInfo.currentBranch();
    }

    @Nullable
    @Override
    public String getCurrentRevision() {
        return myInfo.currentRevision();
    }

    @Nonnull
    @Override
    public State getState() {
        return myInfo.state();
    }

    @Nullable
    @Override
    public String getCurrentBranchName() {
        GitLocalBranch currentBranch = getCurrentBranch();
        return currentBranch == null ? null : currentBranch.getName();
    }

    @Nonnull
    @Override
    public GitVcs getVcs() {
        return myVcs;
    }

    @Nonnull
    @Override
    public Collection<GitSubmoduleInfo> getSubmodules() {
        return myInfo.submodules();
    }

    /**
     * @return local and remote branches in this repository.
     */
    @Nonnull
    @Override
    public GitBranchesCollection getBranches() {
        GitRepoInfo info = myInfo;
        return new GitBranchesCollection(info.localBranches(), info.remoteBranches());
    }

    @Override
    @Nonnull
    public Collection<GitRemote> getRemotes() {
        return myInfo.remotes();
    }

    @Nonnull
    @Override
    public Collection<GitBranchTrackInfo> getBranchTrackInfos() {
        return myInfo.branchTrackInfos();
    }

    @Override
    public boolean isRebaseInProgress() {
        return getState() == State.REBASING;
    }

    @Override
    public boolean isOnBranch() {
        return getState() != State.DETACHED && getState() != State.REBASING;
    }

    @Override
    public boolean isFresh() {
        return getCurrentRevision() == null;
    }

    @Override
    public void update() {
        GitRepoInfo previousInfo = myInfo;
        myInfo = readRepoInfo();
        notifyIfRepoChanged(this, previousInfo, myInfo);
    }

    @Nonnull
    private GitRepoInfo readRepoInfo() {
        StopWatch sw = StopWatch.start("Reading Git repo info in " + getShortRepositoryName(this));
        File configFile = myRepositoryFiles.getConfigFile();
        GitConfig config = GitConfig.read(configFile);
        Collection<GitRemote> remotes = config.parseRemotes();
        GitBranchState state = myReader.readState(remotes);
        Collection<GitBranchTrackInfo> trackInfos =
            config.parseTrackInfos(state.getLocalBranches().keySet(), state.getRemoteBranches().keySet());
        GitHooksInfo hooksInfo = myReader.readHooksInfo();
        Collection<GitSubmoduleInfo> submodules = new GitModulesFileReader().read(getSubmoduleFile());
        sw.report();
        return new GitRepoInfo(
            state.getCurrentBranch(),
            state.getCurrentRevision(),
            state.getState(),
            remotes,
            state.getLocalBranches(),
            state.getRemoteBranches(),
            trackInfos,
            submodules,
            hooksInfo
        );
    }

    @Nonnull
    private File getSubmoduleFile() {
        return new File(VirtualFileUtil.virtualToIoFile(getRoot()), ".gitmodules");
    }

    private static void notifyIfRepoChanged(
        @Nonnull GitRepository repository,
        @Nonnull GitRepoInfo previousInfo,
        @Nonnull GitRepoInfo info
    ) {
        if (!repository.getProject().isDisposed() && !info.equals(previousInfo)) {
            notifyListenersAsync(repository);
        }
    }

    private static void notifyListenersAsync(@Nonnull GitRepository repository) {
        repository.getProject().getApplication().executeOnPooledThread((Runnable) () -> {
            Project project = repository.getProject();
            if (!project.isDisposed()) {
                project.getMessageBus().syncPublisher(GitRepositoryChangeListener.class).repositoryChanged(repository);
            }
        });
    }

    @Nonnull
    @Override
    public String toLogString() {
        return "GitRepository " + getRoot() + " : " + myInfo;
    }
}
