/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package git4idea.config;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.component.persist.PersistentStateComponent;
import consulo.component.persist.State;
import consulo.component.persist.Storage;
import consulo.component.persist.StoragePathMacros;
import consulo.ide.ServiceManager;
import consulo.project.Project;
import consulo.util.collection.ArrayUtil;
import consulo.util.lang.ObjectUtil;
import consulo.util.xml.serializer.annotation.AbstractCollection;
import consulo.util.xml.serializer.annotation.Attribute;
import consulo.util.xml.serializer.annotation.Tag;
import consulo.versionControlSystem.distributed.branch.BranchStorage;
import consulo.versionControlSystem.distributed.branch.DvcsSyncSettings;
import consulo.versionControlSystem.distributed.repository.Repository;
import git4idea.GitRemoteBranch;
import git4idea.GitUtil;
import git4idea.branch.GitBranchType;
import git4idea.push.GitPushTagMode;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.reset.GitResetMode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jetbrains.annotations.Contract;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

/**
 * Git VCS settings
 */
@Singleton
@State(name = "Git.Settings", storages = {@Storage(file = StoragePathMacros.WORKSPACE_FILE)})
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitVcsSettings implements PersistentStateComponent<GitVcsSettings.State>, DvcsSyncSettings {
    private static final int PREVIOUS_COMMIT_AUTHORS_LIMIT = 16; // Limit for previous commit authors

    private final GitVcsApplicationSettings myAppSettings;
    private State myState = new State();

    /**
     * The way the local changes are saved before update if user has selected auto-stash
     */
    public enum UpdateChangesPolicy {
        STASH,
        SHELVE,
    }

    public static class State {
        public String PATH_TO_GIT = null;

        // The previously entered authors of the commit (up to {@value #PREVIOUS_COMMIT_AUTHORS_LIMIT})
        public List<String> PREVIOUS_COMMIT_AUTHORS = new ArrayList<>();
        public GitVcsApplicationSettings.SshExecutable SSH_EXECUTABLE = GitVcsApplicationSettings.SshExecutable.IDEA_SSH;
        // The policy that specifies how files are saved before update or rebase
        public UpdateChangesPolicy UPDATE_CHANGES_POLICY = UpdateChangesPolicy.STASH;
        public UpdateMethod UPDATE_TYPE = UpdateMethod.BRANCH_DEFAULT;
        public boolean PUSH_AUTO_UPDATE = false;
        public boolean PUSH_UPDATE_ALL_ROOTS = true;
        public Value ROOT_SYNC = Value.NOT_DECIDED;
        public String RECENT_GIT_ROOT_PATH = null;
        public Map<String, String> RECENT_BRANCH_BY_REPOSITORY = new HashMap<>();
        public String RECENT_COMMON_BRANCH = null;
        public boolean AUTO_COMMIT_ON_CHERRY_PICK = false;
        public boolean WARN_ABOUT_CRLF = true;
        public boolean WARN_ABOUT_DETACHED_HEAD = true;
        public GitResetMode RESET_MODE = null;
        public boolean FORCE_PUSH_ALLOWED = false;
        public GitPushTagMode PUSH_TAGS = null;
        public boolean SIGN_OFF_COMMIT = false;

        @AbstractCollection(surroundWithTag = false)
        @Tag("push-targets")
        public List<PushTargetInfo> PUSH_TARGETS = new ArrayList<>();

        @Tag("favorite-branches")
        public BranchStorage FAVORITE_BRANCHES = new BranchStorage();
        @Tag("excluded-from-favorite")
        public BranchStorage EXCLUDED_FAVORITES = new BranchStorage();
    }

    @Inject
    public GitVcsSettings(GitVcsApplicationSettings appSettings) {
        myAppSettings = appSettings;
    }

    public GitVcsApplicationSettings getAppSettings() {
        return myAppSettings;
    }

    public static GitVcsSettings getInstance(Project project) {
        return ServiceManager.getService(project, GitVcsSettings.class);
    }

    @Nonnull
    public UpdateMethod getUpdateType() {
        return ObjectUtil.notNull(myState.UPDATE_TYPE, UpdateMethod.BRANCH_DEFAULT);
    }

    public void setUpdateType(UpdateMethod updateType) {
        myState.UPDATE_TYPE = updateType;
    }

    @Nonnull
    public UpdateChangesPolicy updateChangesPolicy() {
        return myState.UPDATE_CHANGES_POLICY;
    }

    public void setUpdateChangesPolicy(UpdateChangesPolicy value) {
        myState.UPDATE_CHANGES_POLICY = value;
    }

    /**
     * Save an author of the commit and make it the first one. If amount of authors exceeds the limit, remove least recently selected author.
     *
     * @param author an author to save
     */
    public void saveCommitAuthor(String author) {
        myState.PREVIOUS_COMMIT_AUTHORS.remove(author);
        while (myState.PREVIOUS_COMMIT_AUTHORS.size() >= PREVIOUS_COMMIT_AUTHORS_LIMIT) {
            myState.PREVIOUS_COMMIT_AUTHORS.remove(myState.PREVIOUS_COMMIT_AUTHORS.size() - 1);
        }
        myState.PREVIOUS_COMMIT_AUTHORS.add(0, author);
    }

    public String[] getCommitAuthors() {
        return ArrayUtil.toStringArray(myState.PREVIOUS_COMMIT_AUTHORS);
    }

    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(State state) {
        myState = state;
    }

    @Nullable
    public String getPathToGit() {
        return myState.PATH_TO_GIT;
    }

    public void setPathToGit(@Nullable String pathToGit) {
        myState.PATH_TO_GIT = pathToGit;
    }

    public boolean autoUpdateIfPushRejected() {
        return myState.PUSH_AUTO_UPDATE;
    }

    public void setAutoUpdateIfPushRejected(boolean autoUpdate) {
        myState.PUSH_AUTO_UPDATE = autoUpdate;
    }

    public boolean shouldUpdateAllRootsIfPushRejected() {
        return myState.PUSH_UPDATE_ALL_ROOTS;
    }

    public void setUpdateAllRootsIfPushRejected(boolean updateAllRoots) {
        myState.PUSH_UPDATE_ALL_ROOTS = updateAllRoots;
    }

    @Nonnull
    @Override
    public Value getSyncSetting() {
        return myState.ROOT_SYNC;
    }

    @Override
    public void setSyncSetting(@Nonnull Value syncSetting) {
        myState.ROOT_SYNC = syncSetting;
    }

    @Nullable
    public String getRecentRootPath() {
        return myState.RECENT_GIT_ROOT_PATH;
    }

    public void setRecentRoot(@Nonnull String recentGitRootPath) {
        myState.RECENT_GIT_ROOT_PATH = recentGitRootPath;
    }

    @Nonnull
    public Map<String, String> getRecentBranchesByRepository() {
        return myState.RECENT_BRANCH_BY_REPOSITORY;
    }

    public void setRecentBranchOfRepository(@Nonnull String repositoryPath, @Nonnull String branch) {
        myState.RECENT_BRANCH_BY_REPOSITORY.put(repositoryPath, branch);
    }

    @Nullable
    public String getRecentCommonBranch() {
        return myState.RECENT_COMMON_BRANCH;
    }

    public void setRecentCommonBranch(@Nonnull String branch) {
        myState.RECENT_COMMON_BRANCH = branch;
    }

    public void setAutoCommitOnCherryPick(boolean autoCommit) {
        myState.AUTO_COMMIT_ON_CHERRY_PICK = autoCommit;
    }

    public boolean isAutoCommitOnCherryPick() {
        return myState.AUTO_COMMIT_ON_CHERRY_PICK;
    }

    public boolean warnAboutCrlf() {
        return myState.WARN_ABOUT_CRLF;
    }

    public void setWarnAboutCrlf(boolean warn) {
        myState.WARN_ABOUT_CRLF = warn;
    }

    public boolean warnAboutDetachedHead() {
        return myState.WARN_ABOUT_DETACHED_HEAD;
    }

    public void setWarnAboutDetachedHead(boolean warn) {
        myState.WARN_ABOUT_DETACHED_HEAD = warn;
    }

    @Nullable
    public GitResetMode getResetMode() {
        return myState.RESET_MODE;
    }

    public void setResetMode(@Nonnull GitResetMode mode) {
        myState.RESET_MODE = mode;
    }

    public boolean isForcePushAllowed() {
        return myState.FORCE_PUSH_ALLOWED;
    }

    public void setForcePushAllowed(boolean allowed) {
        myState.FORCE_PUSH_ALLOWED = allowed;
    }

    @Nullable
    public GitPushTagMode getPushTagMode() {
        return myState.PUSH_TAGS;
    }

    public void setPushTagMode(@Nullable GitPushTagMode mode) {
        myState.PUSH_TAGS = mode;
    }

    public boolean shouldSignOffCommit() {
        return myState.SIGN_OFF_COMMIT;
    }

    public void setSignOffCommit(boolean state) {
        myState.SIGN_OFF_COMMIT = state;
    }


    /**
     * Provides migration from project settings.
     * This method is to be removed in IDEA 13: it should be moved to {@link GitVcsApplicationSettings}
     */
    @Deprecated
    public boolean isIdeaSsh() {
        if (getAppSettings().getIdeaSsh() == null) { // app setting has not been initialized yet => migrate the project setting there
            getAppSettings().setIdeaSsh(myState.SSH_EXECUTABLE);
        }
        return getAppSettings().getIdeaSsh() == GitVcsApplicationSettings.SshExecutable.IDEA_SSH;
    }

    @Nullable
    public GitRemoteBranch getPushTarget(@Nonnull GitRepository repository, @Nonnull String sourceBranch) {
        Iterator<PushTargetInfo> iterator = myState.PUSH_TARGETS.iterator();
        PushTargetInfo targetInfo = find(iterator, repository, sourceBranch);
        if (targetInfo == null) {
            return null;
        }
        GitRemote remote = GitUtil.findRemoteByName(repository, targetInfo.targetRemoteName);
        if (remote == null) {
            return null;
        }
        return GitUtil.findOrCreateRemoteBranch(repository, remote, targetInfo.targetBranchName);
    }

    public void setPushTarget(
        @Nonnull GitRepository repository,
        @Nonnull String sourceBranch,
        @Nonnull String targetRemote,
        @Nonnull String targetBranch
    ) {
        String repositoryPath = repository.getRoot().getPath();
        List<PushTargetInfo> targets = new ArrayList<>(myState.PUSH_TARGETS);
        Iterator<PushTargetInfo> iterator = targets.iterator();
        PushTargetInfo existingInfo = find(iterator, repository, sourceBranch);
        if (existingInfo != null) {
            iterator.remove();
        }
        PushTargetInfo newInfo = new PushTargetInfo(repositoryPath, sourceBranch, targetRemote, targetBranch);
        targets.add(newInfo);
        myState.PUSH_TARGETS = targets;
    }

    @Nullable
    @Contract(pure = false)
    private static PushTargetInfo find(
        @Nonnull Iterator<PushTargetInfo> iterator,
        @Nonnull GitRepository repository,
        @Nonnull String sourceBranch
    ) {
        while (iterator.hasNext()) {
            PushTargetInfo targetInfo = iterator.next();
            if (targetInfo.repoPath.equals(repository.getRoot().getPath()) && targetInfo.sourceName.equals(sourceBranch)) {
                return targetInfo;
            }
        }
        return null;
    }

    public void addToFavorites(@Nonnull GitBranchType type, @Nullable GitRepository repository, @Nonnull String branchName) {
        myState.FAVORITE_BRANCHES.add(type.toString(), repository, branchName);
    }

    public void removeFromFavorites(@Nonnull GitBranchType type, @Nullable GitRepository repository, @Nonnull String branchName) {
        myState.FAVORITE_BRANCHES.remove(type.toString(), repository, branchName);
    }

    public boolean isFavorite(@Nonnull GitBranchType type, @Nullable Repository repository, @Nonnull String branchName) {
        return myState.FAVORITE_BRANCHES.contains(type.toString(), repository, branchName);
    }

    public void excludedFromFavorites(@Nonnull GitBranchType type, @Nullable GitRepository repository, @Nonnull String branchName) {
        myState.EXCLUDED_FAVORITES.add(type.toString(), repository, branchName);
    }

    public void removeFromExcluded(@Nonnull GitBranchType type, @Nullable GitRepository repository, @Nonnull String branchName) {
        myState.EXCLUDED_FAVORITES.remove(type.toString(), repository, branchName);
    }

    public boolean isExcludedFromFavorites(@Nonnull GitBranchType type, @Nullable Repository repository, @Nonnull String branchName) {
        return myState.EXCLUDED_FAVORITES.contains(type.toString(), repository, branchName);
    }

    @Tag("push-target-info")
    private static class PushTargetInfo {
        @Attribute(value = "repo")
        public String repoPath;
        @Attribute(value = "source")
        public String sourceName;
        @Attribute(value = "target-remote")
        public String targetRemoteName;
        @Attribute(value = "target-branch")
        public String targetBranchName;

        @SuppressWarnings("unused")
        public PushTargetInfo() {
            this("", "", "", "");
        }

        PushTargetInfo(@Nonnull String repositoryPath, @Nonnull String source, @Nonnull String targetRemote, @Nonnull String targetBranch) {
            repoPath = repositoryPath;
            sourceName = source;
            targetRemoteName = targetRemote;
            targetBranchName = targetBranch;
        }
    }
}
