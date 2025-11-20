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

import consulo.logging.Logger;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.push.PushTarget;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.GitStandardRemoteBranch;
import git4idea.GitUtil;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.validators.GitRefNameValidator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.text.ParseException;
import java.util.Collection;
import java.util.List;

import static git4idea.GitBranch.REFS_REMOTES_PREFIX;
import static git4idea.GitUtil.findRemoteBranch;

public class GitPushTarget implements PushTarget {

    private static final Logger LOG = Logger.getInstance(GitPushTarget.class);

    @Nonnull
    private final GitRemoteBranch myRemoteBranch;
    private final boolean myIsNewBranchCreated;
    private final boolean myPushingToSpecialRef;

    public GitPushTarget(@Nonnull GitRemoteBranch remoteBranch, boolean isNewBranchCreated) {
        this(remoteBranch, isNewBranchCreated, false);
    }

    public GitPushTarget(@Nonnull GitRemoteBranch remoteBranch, boolean isNewBranchCreated, boolean isPushingToSpecialRef) {
        myRemoteBranch = remoteBranch;
        myIsNewBranchCreated = isNewBranchCreated;
        myPushingToSpecialRef = isPushingToSpecialRef;
    }

    @Nonnull
    public GitRemoteBranch getBranch() {
        return myRemoteBranch;
    }

    @Override
    public boolean hasSomethingToPush() {
        return isNewBranchCreated();
    }

    @Nonnull
    @Override
    public String getPresentation() {
        return myPushingToSpecialRef ? myRemoteBranch.getFullName() : myRemoteBranch.getNameForRemoteOperations();
    }

    public boolean isNewBranchCreated() {
        return myIsNewBranchCreated;
    }

    @TestOnly
    boolean isSpecialRef() {
        return myPushingToSpecialRef;
    }

    @Nonnull
    public static GitPushTarget parse(
        @Nonnull GitRepository repository,
        @Nullable String remoteName,
        @Nonnull String branchName
    ) throws ParseException {
        if (remoteName == null) {
            throw new ParseException("No remotes defined", -1);
        }

        if (!GitRefNameValidator.getInstance().checkInput(branchName)) {
            throw new ParseException("Invalid destination branch name: " + branchName, -1);
        }

        GitRemote remote = findRemote(repository.getRemotes(), remoteName);
        if (remote == null) {
            LOG.error("Remote [" + remoteName + "] is not found among " + repository.getRemotes());
            throw new ParseException("Invalid remote: " + remoteName, -1);
        }

        GitRemoteBranch existingRemoteBranch = findRemoteBranch(repository, remote, branchName);
        if (existingRemoteBranch != null) {
            return new GitPushTarget(existingRemoteBranch, false);
        }
        GitRemoteBranch rb = new GitStandardRemoteBranch(remote, branchName);
        return new GitPushTarget(rb, true);
    }

    @Nullable
    private static GitRemote findRemote(@Nonnull Collection<GitRemote> remotes, @Nonnull String candidate) {
        return ContainerUtil.find(remotes, remote -> remote.getName().equals(candidate));
    }

    @Nullable
    public static GitPushTarget getFromPushSpec(@Nonnull GitRepository repository, @Nonnull GitLocalBranch sourceBranch) {
        GitRemote remote = getRemoteToPush(repository, GitBranchUtil.getTrackInfoForBranch(repository, sourceBranch));
        if (remote == null) {
            return null;
        }
        List<String> specs = remote.getPushRefSpecs();
        if (specs.isEmpty()) {
            return null;
        }

        String targetRef = GitPushSpecParser.getTargetRef(repository, sourceBranch.getName(), specs);
        if (targetRef == null) {
            return null;
        }

        String remotePrefix = REFS_REMOTES_PREFIX + remote.getName() + "/";
        if (targetRef.startsWith(remotePrefix)) {
            targetRef = targetRef.substring(remotePrefix.length());
            GitRemoteBranch remoteBranch = GitUtil.findOrCreateRemoteBranch(repository, remote, targetRef);
            boolean existingBranch = repository.getBranches().getRemoteBranches().contains(remoteBranch);
            return new GitPushTarget(remoteBranch, !existingBranch, false);
        }
        else {
            GitRemoteBranch remoteBranch = new GitSpecialRefRemoteBranch(targetRef, remote);
            return new GitPushTarget(remoteBranch, true, true);
        }
    }

    @Nullable
    private static GitRemote getRemoteToPush(@Nonnull GitRepository repository, @Nullable GitBranchTrackInfo trackInfo) {
        if (trackInfo != null) {
            return trackInfo.getRemote();
        }
        return GitUtil.findOrigin(repository.getRemotes());
    }

    @Override
    public boolean equals(Object o) {
        return this == o
            || o instanceof GitPushTarget target
            && myIsNewBranchCreated == target.myIsNewBranchCreated
            && myRemoteBranch.equals(target.myRemoteBranch);
    }

    @Override
    public int hashCode() {
        int result = myRemoteBranch.hashCode();
        result = 31 * result + (myIsNewBranchCreated ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return myRemoteBranch.getNameForLocalOperations();
    }
}
