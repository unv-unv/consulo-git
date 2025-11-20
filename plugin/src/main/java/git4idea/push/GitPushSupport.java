/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.push;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.util.lang.ObjectUtil;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.distributed.push.*;
import consulo.versionControlSystem.distributed.repository.RepositoryManager;
import git4idea.*;
import git4idea.branch.GitBranchUtil;
import git4idea.config.GitSharedSettings;
import git4idea.config.GitVcsSettings;
import git4idea.config.GitVersionSpecialty;
import git4idea.repo.*;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import java.util.Collection;

@ExtensionImpl
public class GitPushSupport extends PushSupport<GitRepository, GitPushSource, GitPushTarget> {
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;
    @Nonnull
    private final GitVcs myVcs;
    @Nonnull
    private final Pusher<GitRepository, GitPushSource, GitPushTarget> myPusher;
    @Nonnull
    private final OutgoingCommitsProvider<GitRepository, GitPushSource, GitPushTarget> myOutgoingCommitsProvider;
    @Nonnull
    private final GitVcsSettings mySettings;
    private final GitSharedSettings mySharedSettings;
    @Nonnull
    private final PushSettings myCommonPushSettings;

    @Inject
    public GitPushSupport(@Nonnull Project project) {
        myRepositoryManager = GitRepositoryManager.getInstance(project);
        myVcs = GitVcs.getInstance(project);
        mySettings = GitVcsSettings.getInstance(project);
        myPusher = new GitPusher(project, mySettings, this);
        myOutgoingCommitsProvider = new GitOutgoingCommitsProvider(project);
        mySharedSettings = project.getInstance(GitSharedSettings.class);
        myCommonPushSettings = project.getInstance(PushSettings.class);
    }

    @Nonnull
    @Override
    public AbstractVcs getVcs() {
        return myVcs;
    }

    @Nonnull
    @Override
    public Pusher<GitRepository, GitPushSource, GitPushTarget> getPusher() {
        return myPusher;
    }

    @Nonnull
    @Override
    public OutgoingCommitsProvider<GitRepository, GitPushSource, GitPushTarget> getOutgoingCommitsProvider() {
        return myOutgoingCommitsProvider;
    }

    @Nullable
    @Override
    public GitPushTarget getDefaultTarget(@Nonnull GitRepository repository) {
        if (repository.isFresh()) {
            return null;
        }
        GitLocalBranch currentBranch = repository.getCurrentBranch();
        if (currentBranch == null) {
            return null;
        }

        GitPushTarget persistedTarget = getPersistedTarget(repository, currentBranch);
        if (persistedTarget != null) {
            return persistedTarget;
        }

        GitPushTarget pushSpecTarget = GitPushTarget.getFromPushSpec(repository, currentBranch);
        if (pushSpecTarget != null) {
            return pushSpecTarget;
        }

        GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(repository, currentBranch);
        if (trackInfo != null) {
            return new GitPushTarget(trackInfo.remoteBranch(), false);
        }
        return proposeTargetForNewBranch(repository, currentBranch);
    }

    @Nullable
    private GitPushTarget getPersistedTarget(@Nonnull GitRepository repository, @Nonnull GitLocalBranch branch) {
        GitRemoteBranch target = mySettings.getPushTarget(repository, branch.getName());
        return target == null ? null : new GitPushTarget(target, !repository.getBranches().getRemoteBranches().contains(target));
    }

    private static GitPushTarget proposeTargetForNewBranch(GitRepository repository, GitLocalBranch currentBranch) {
        Collection<GitRemote> remotes = repository.getRemotes();
        if (remotes.isEmpty()) {
            return null; // TODO need to propose to declare new remote
        }
        else if (remotes.size() == 1) {
            return makeTargetForNewBranch(repository, remotes.iterator().next(), currentBranch);
        }
        else {
            GitRemote remote = GitUtil.getDefaultRemote(remotes);
            if (remote == null) {
                remote = remotes.iterator().next();
            }
            return makeTargetForNewBranch(repository, remote, currentBranch);
        }
    }

    @Nonnull
    private static GitPushTarget makeTargetForNewBranch(
        @Nonnull GitRepository repository,
        @Nonnull GitRemote remote,
        @Nonnull GitLocalBranch currentBranch
    ) {
        GitRemoteBranch existingRemoteBranch = GitUtil.findRemoteBranch(repository, remote, currentBranch.getName());
        if (existingRemoteBranch != null) {
            return new GitPushTarget(existingRemoteBranch, false);
        }
        return new GitPushTarget(new GitStandardRemoteBranch(remote, currentBranch.getName()), true);
    }

    @Nonnull
    @Override
    public GitPushSource getSource(@Nonnull GitRepository repository) {
        GitLocalBranch currentBranch = repository.getCurrentBranch();
        return currentBranch != null ? GitPushSource.create(currentBranch) : GitPushSource.create(ObjectUtil.assertNotNull(repository.getCurrentRevision())); // fresh repository is on branch
    }

    @Nonnull
    @Override
    public RepositoryManager<GitRepository> getRepositoryManager() {
        return myRepositoryManager;
    }

    @Nonnull
    @Override
    public PushTargetPanel<GitPushTarget> createTargetPanel(@Nonnull GitRepository repository, @Nullable GitPushTarget defaultTarget) {
        return new GitPushTargetPanel(this, repository, defaultTarget);
    }

    @Override
    public boolean isForcePushAllowed(@Nonnull GitRepository repo, @Nonnull GitPushTarget target) {
        final String targetBranch = target.getBranch().getNameForRemoteOperations();
        return !mySharedSettings.isBranchProtected(targetBranch);
    }

    @Override
    public boolean isForcePushEnabled() {
        return true;
    }

    @Nullable
    @Override
    public VcsPushOptionsPanel createOptionsPanel() {
        return new GitPushOptionsPanel(
            mySettings.getPushTagMode(),
            GitVersionSpecialty.SUPPORTS_FOLLOW_TAGS.existsIn(myVcs.getVersion()),
            shouldShowSkipHookOption()
        );
    }

    private boolean shouldShowSkipHookOption() {
        return GitVersionSpecialty.PRE_PUSH_HOOK.existsIn(myVcs.getVersion())
            && getRepositoryManager().getRepositories()
            .stream()
            .map(e -> e.getInfo().hooksInfo())
            .anyMatch(GitHooksInfo::prePushHookAvailable);
    }

    @Override
    public boolean isSilentForcePushAllowed(@Nonnull GitPushTarget target) {
        return myCommonPushSettings.containsForcePushTarget(
            target.getBranch().getRemote().getName(),
            target.getBranch().getNameForRemoteOperations()
        );
    }

    @Override
    public void saveSilentForcePushTarget(@Nonnull GitPushTarget target) {
        myCommonPushSettings.addForcePushTarget(target.getBranch().getRemote().getName(), target.getBranch().getNameForRemoteOperations());
    }

    @Override
    public boolean mayChangeTargetsSync() {
        return true;
    }
}
