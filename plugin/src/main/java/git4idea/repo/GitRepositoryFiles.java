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

import consulo.logging.Logger;
import consulo.util.io.FileUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

/**
 * Stores paths to Git service files (from .git/ directory) that are used by IDEA, and provides test-methods to check if a file
 * matches once of them.
 */
public class GitRepositoryFiles {
    private static final Logger LOG = Logger.getInstance(GitRepositoryFiles.class);

    private static final String CHERRY_PICK_HEAD = "CHERRY_PICK_HEAD";
    private static final String COMMIT_EDITMSG = "COMMIT_EDITMSG";
    private static final String CONFIG = "config";
    private static final String HEAD = "HEAD";
    private static final String INDEX = "index";
    private static final String INFO = "info";
    private static final String INFO_EXCLUDE = INFO + "/exclude";
    private static final String MERGE_HEAD = "MERGE_HEAD";
    private static final String MERGE_MSG = "MERGE_MSG";
    private static final String ORIG_HEAD = "ORIG_HEAD";
    private static final String REBASE_APPLY = "rebase-apply";
    private static final String REBASE_MERGE = "rebase-merge";
    private static final String PACKED_REFS = "packed-refs";
    private static final String REFS = "refs";
    private static final String HEADS = "heads";
    private static final String TAGS = "tags";
    private static final String REMOTES = "remotes";
    private static final String SQUASH_MSG = "SQUASH_MSG";
    private static final String HOOKS = "hooks";
    private static final String PRE_COMMIT_HOOK = "pre-commit";
    private static final String PRE_PUSH_HOOK = "pre-push";

    private final VirtualFile myMainDir;
    private final VirtualFile myWorktreeDir;

    private final String myConfigFilePath;
    private final String myHeadFilePath;
    private final String myIndexFilePath;
    private final String myMergeHeadPath;
    private final String myCherryPickHeadPath;
    private final String myOrigHeadPath;
    private final String myRebaseApplyPath;
    private final String myRebaseMergePath;
    private final String myPackedRefsPath;
    private final String myRefsHeadsDirPath;
    private final String myRefsRemotesDirPath;
    private final String myRefsTagsPath;
    private final String myCommitMessagePath;
    private final String myMergeMessagePath;
    private final String myMergeSquashPath;
    private final String myInfoDirPath;
    private final String myExcludePath;
    private final String myHooksDirPath;

    private GitRepositoryFiles(@Nonnull VirtualFile mainDir, @Nonnull VirtualFile worktreeDir) {
        myMainDir = mainDir;
        myWorktreeDir = worktreeDir;

        String mainPath = myMainDir.getPath();
        myConfigFilePath = mainPath + slash(CONFIG);
        myPackedRefsPath = mainPath + slash(PACKED_REFS);
        String refsPath = mainPath + slash(REFS);
        myRefsHeadsDirPath = refsPath + slash(HEADS);
        myRefsTagsPath = refsPath + slash(TAGS);
        myRefsRemotesDirPath = refsPath + slash(REMOTES);
        myInfoDirPath = mainPath + slash(INFO);
        myExcludePath = mainPath + slash(INFO_EXCLUDE);
        myHooksDirPath = mainPath + slash(HOOKS);

        String worktreePath = myWorktreeDir.getPath();
        myHeadFilePath = worktreePath + slash(HEAD);
        myIndexFilePath = worktreePath + slash(INDEX);
        myMergeHeadPath = worktreePath + slash(MERGE_HEAD);
        myCherryPickHeadPath = worktreePath + slash(CHERRY_PICK_HEAD);
        myOrigHeadPath = worktreePath + slash(ORIG_HEAD);
        myCommitMessagePath = worktreePath + slash(COMMIT_EDITMSG);
        myMergeMessagePath = worktreePath + slash(MERGE_MSG);
        myMergeSquashPath = worktreePath + slash(SQUASH_MSG);
        myRebaseApplyPath = worktreePath + slash(REBASE_APPLY);
        myRebaseMergePath = worktreePath + slash(REBASE_MERGE);
    }

    @Nonnull
    public static GitRepositoryFiles getInstance(@Nonnull VirtualFile gitDir) {
        VirtualFile gitDirForWorktree = getMainGitDirForWorktree(gitDir);
        VirtualFile mainDir = gitDirForWorktree == null ? gitDir : gitDirForWorktree;
        return new GitRepositoryFiles(mainDir, gitDir);
    }

    /**
     * Checks if the given .git directory is actually a worktree's git directory, and returns the main .git directory if it is true.
     * If it is not a worktree, returns null.
     * <p>
     * Worktree's ".git" file references {@code <main-project>/.git/worktrees/<worktree-name>}
     */
    @Nullable
    private static VirtualFile getMainGitDirForWorktree(@Nonnull VirtualFile gitDir) {
        File gitDirFile = VirtualFileUtil.virtualToIoFile(gitDir);
        File commonDir = new File(gitDirFile, "commondir");
        if (!commonDir.exists()) {
            return null;
        }
        String pathToMain;
        try {
            pathToMain = Files.readString(commonDir.toPath()).trim();
        }
        catch (IOException e) {
            LOG.error("Couldn't load " + commonDir, e);
            return null;
        }
        String mainDir = FileUtil.toCanonicalPath(gitDirFile.getPath() + File.separator + pathToMain, true);
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        VirtualFile mainDirVF = lfs.refreshAndFindFileByPath(mainDir);
        if (mainDirVF != null) {
            return mainDirVF;
        }
        return lfs.refreshAndFindFileByPath(pathToMain); // absolute path is also possible
    }

    @Nonnull
    private static String slash(@Nonnull String s) {
        return "/" + s;
    }

    /**
     * Returns subdirectories of .git which we are interested in - they should be watched by VFS.
     */
    @Nonnull
    Collection<String> getDirsToWatch() {
        return Arrays.asList(myRefsHeadsDirPath, myRefsRemotesDirPath, myRefsTagsPath, myInfoDirPath, myHooksDirPath);
    }

    @Nonnull
    File getRefsHeadsFile() {
        return file(myRefsHeadsDirPath);
    }

    @Nonnull
    File getRefsRemotesFile() {
        return file(myRefsRemotesDirPath);
    }

    @Nonnull
    File getRefsTagsFile() {
        return file(myRefsTagsPath);
    }

    @Nonnull
    File getPackedRefsPath() {
        return file(myPackedRefsPath);
    }

    @Nonnull
    public File getHeadFile() {
        return file(myHeadFilePath);
    }

    @Nonnull
    File getConfigFile() {
        return file(myConfigFilePath);
    }

    @Nonnull
    public File getRebaseMergeDir() {
        return file(myRebaseMergePath);
    }

    @Nonnull
    public File getRebaseApplyDir() {
        return file(myRebaseApplyPath);
    }

    @Nonnull
    public File getMergeHeadFile() {
        return file(myMergeHeadPath);
    }

    @Nonnull
    public File getCherryPickHead() {
        return file(myCherryPickHeadPath);
    }

    @Nonnull
    public File getMergeMessageFile() {
        return file(myMergeMessagePath);
    }

    @Nonnull
    public File getSquashMessageFile() {
        return file(myMergeSquashPath);
    }

    @Nonnull
    public File getPreCommitHookFile() {
        return file(myHooksDirPath + slash(PRE_COMMIT_HOOK));
    }

    @Nonnull
    public File getPrePushHookFile() {
        return file(myHooksDirPath + slash(PRE_PUSH_HOOK));
    }

    @Nonnull
    private static File file(@Nonnull String filePath) {
        return new File(FileUtil.toSystemDependentName(filePath));
    }

    /**
     * {@code .git/config}
     */
    public boolean isConfigFile(String filePath) {
        return filePath.equals(myConfigFilePath);
    }

    /**
     * .git/index
     */
    public boolean isIndexFile(String filePath) {
        return filePath.equals(myIndexFilePath);
    }

    /**
     * .git/HEAD
     */
    public boolean isHeadFile(String file) {
        return file.equals(myHeadFilePath);
    }

    /**
     * .git/ORIG_HEAD
     */
    public boolean isOrigHeadFile(@Nonnull String file) {
        return file.equals(myOrigHeadPath);
    }

    /**
     * Any file in .git/refs/heads, i.e. a branch reference file.
     */
    public boolean isBranchFile(String filePath) {
        return filePath.startsWith(myRefsHeadsDirPath);
    }

    /**
     * Checks if the given filePath represents the ref file of the given branch.
     *
     * @param filePath       the path to check, in system-independent format (e.g. with "/").
     * @param fullBranchName full name of a ref, e.g. {@code refs/heads/master}.
     * @return true iff the filePath represents the .git/refs/heads... file for the given branch.
     */
    public boolean isBranchFile(@Nonnull String filePath, @Nonnull String fullBranchName) {
        return FileUtil.pathsEqual(filePath, myMainDir.getPath() + slash(fullBranchName));
    }

    /**
     * Any file in .git/refs/remotes, i.e. a remote branch reference file.
     */
    public boolean isRemoteBranchFile(String filePath) {
        return filePath.startsWith(myRefsRemotesDirPath);
    }

    /**
     * .git/refs/tags/*
     */
    public boolean isTagFile(@Nonnull String path) {
        return path.startsWith(myRefsTagsPath);
    }

    /**
     * .git/rebase-merge or .git/rebase-apply
     */
    public boolean isRebaseFile(String path) {
        return path.equals(myRebaseApplyPath) || path.equals(myRebaseMergePath);
    }

    /**
     * .git/MERGE_HEAD
     */
    public boolean isMergeFile(String file) {
        return file.equals(myMergeHeadPath);
    }

    /**
     * .git/packed-refs
     */
    public boolean isPackedRefs(String file) {
        return file.equals(myPackedRefsPath);
    }

    public boolean isCommitMessageFile(@Nonnull String file) {
        return file.equals(myCommitMessagePath);
    }

    /**
     * {@code $GIT_DIR/info/exclude}
     */
    public boolean isExclude(@Nonnull String path) {
        return path.equals(myExcludePath);
    }

    /**
     * Refresh all .git repository files asynchronously and recursively.
     *
     * @see #refreshNonTrackedData() if you need the "main" data (branches, HEAD, etc.) to be updated synchronously.
     */
    public void refresh() {
        VirtualFileUtil.markDirtyAndRefresh(true, true, false, myMainDir, myWorktreeDir);
    }

    /**
     * Refresh that part of .git repository files, which is not covered by {@link GitRepository#update()}, e.g. the {@code refs/tags/} dir.
     * <p>
     * The call to this method should be probably be done together with a call to update(): thus all information will be updated,
     * but some of it will be updated synchronously, the rest - asynchronously.
     */
    public void refreshNonTrackedData() {
        VirtualFile tagsDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(myRefsTagsPath);
        VirtualFileUtil.markDirtyAndRefresh(true, true, false, tagsDir);
    }

    @Nonnull
    Collection<VirtualFile> getRootDirs() {
        if (myMainDir == myWorktreeDir) {
            return Set.of(myMainDir);
        }
        return Set.of(myMainDir, myWorktreeDir);
    }
}
