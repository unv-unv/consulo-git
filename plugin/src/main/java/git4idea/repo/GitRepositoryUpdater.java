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

import consulo.application.util.concurrent.QueueProcessor;
import consulo.component.messagebus.MessageBusConnection;
import consulo.disposer.Disposable;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.event.BulkFileListener;
import consulo.virtualFileSystem.event.VFileEvent;
import git4idea.util.GitFileUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Listens to .git service files changes and updates {@link GitRepository} when needed.
 */
final class GitRepositoryUpdater implements Disposable, BulkFileListener {
    @Nonnull
    private final GitRepository myRepository;
    @Nonnull
    private final GitRepositoryFiles myRepositoryFiles;
    @Nullable
    private final MessageBusConnection myMessageBusConnection;
    @Nonnull
    private final QueueProcessor<Object> myUpdateQueue;
    @Nonnull
    private final Object DUMMY_UPDATE_OBJECT = new Object();
    @Nullable
    private final VirtualFile myRemotesDir;
    @Nullable
    private final VirtualFile myHeadsDir;
    @Nullable
    private final VirtualFile myTagsDir;
    @Nullable
    private final Set<LocalFileSystem.WatchRequest> myWatchRequests;

    GitRepositoryUpdater(@Nonnull GitRepository repository, @Nonnull GitRepositoryFiles gitFiles) {
        myRepository = repository;
        Collection<String> rootPaths = ContainerUtil.map(gitFiles.getRootDirs(), VirtualFile::getPath);
        myWatchRequests = LocalFileSystem.getInstance().addRootsToWatch(rootPaths, true);

        myRepositoryFiles = gitFiles;
        visitSubDirsInVfs();
        myHeadsDir = VcsUtil.getVirtualFile(myRepositoryFiles.getRefsHeadsFile());
        myRemotesDir = VcsUtil.getVirtualFile(myRepositoryFiles.getRefsRemotesFile());
        myTagsDir = VcsUtil.getVirtualFile(myRepositoryFiles.getRefsTagsFile());

        Project project = repository.getProject();
        myUpdateQueue = new QueueProcessor<>(new DvcsUtil.Updater(repository), project.getDisposed());
        if (!project.isDisposed()) {
            myMessageBusConnection = project.getMessageBus().connect();
            myMessageBusConnection.subscribe(BulkFileListener.class, this);
        }
        else {
            myMessageBusConnection = null;
        }
    }

    @Override
    public void dispose() {
        LocalFileSystem.getInstance().removeWatchedRoots(myWatchRequests);
        if (myMessageBusConnection != null) {
            myMessageBusConnection.disconnect();
        }
    }

    @Override
    public void before(@Nonnull List<? extends VFileEvent> events) {
        // everything is handled in #after()
    }

    @Override
    public void after(@Nonnull List<? extends VFileEvent> events) {
        // which files in .git were changed
        boolean configChanged = false;
        boolean headChanged = false;
        boolean branchFileChanged = false;
        boolean packedRefsChanged = false;
        boolean rebaseFileChanged = false;
        boolean mergeFileChanged = false;
        boolean tagChanged = false;
        for (VFileEvent event : events) {
            String filePath = GitFileUtils.stripFileProtocolPrefix(event.getPath());
            if (myRepositoryFiles.isConfigFile(filePath)) {
                configChanged = true;
            }
            else if (myRepositoryFiles.isHeadFile(filePath)) {
                headChanged = true;
            }
            else if (myRepositoryFiles.isBranchFile(filePath)) {
                // it is also possible, that a local branch with complex name ("folder/branch")
                // was created => the folder also to be watched.
                branchFileChanged = true;
                DvcsUtil.ensureAllChildrenInVfs(myHeadsDir);
            }
            else if (myRepositoryFiles.isRemoteBranchFile(filePath)) {
                // it is possible, that a branch from a new remote was fetch => we need to add new remote folder to the VFS
                branchFileChanged = true;
                DvcsUtil.ensureAllChildrenInVfs(myRemotesDir);
            }
            else if (myRepositoryFiles.isPackedRefs(filePath)) {
                packedRefsChanged = true;
            }
            else if (myRepositoryFiles.isRebaseFile(filePath)) {
                rebaseFileChanged = true;
            }
            else if (myRepositoryFiles.isMergeFile(filePath)) {
                mergeFileChanged = true;
            }
            else if (myRepositoryFiles.isTagFile(filePath)) {
                DvcsUtil.ensureAllChildrenInVfs(myTagsDir);
                tagChanged = true;
            }
        }

        if (headChanged || configChanged || branchFileChanged || packedRefsChanged || rebaseFileChanged || mergeFileChanged) {
            myUpdateQueue.add(DUMMY_UPDATE_OBJECT);
        }
        else if (tagChanged) {
            myRepository.getProject().getMessageBus().syncPublisher(GitRepositoryChangeListener.class).repositoryChanged(myRepository);
        }
    }

    private void visitSubDirsInVfs() {
        for (VirtualFile rootDir : myRepositoryFiles.getRootDirs()) {
            rootDir.getChildren();
        }
        for (String path : myRepositoryFiles.getDirsToWatch()) {
            DvcsUtil.ensureAllChildrenInVfs(LocalFileSystem.getInstance().refreshAndFindFileByPath(path));
        }
    }
}
