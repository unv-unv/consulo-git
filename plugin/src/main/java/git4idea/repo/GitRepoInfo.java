/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

import consulo.util.collection.HashingStrategy;
import consulo.util.collection.Sets;
import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.versionControlSystem.log.Hash;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record GitRepoInfo(
    @Nullable GitLocalBranch currentBranch,
    @Nullable String currentRevision,
    @Nonnull Repository.State state,
    @Nonnull Collection<GitRemote> remotes,
    @Nonnull Map<GitLocalBranch, Hash> localBranches,
    @Nonnull Map<GitRemoteBranch, Hash> remoteBranches,
    @Nonnull Collection<GitBranchTrackInfo> branchTrackInfos,
    @Nonnull Collection<GitSubmoduleInfo> submodules,
    @Nonnull GitHooksInfo hooksInfo
) {
    @Deprecated
    @Nullable
    public GitLocalBranch getCurrentBranch() {
        return currentBranch();
    }

    @Deprecated
    @Nonnull
    public Collection<GitRemote> getRemotes() {
        return remotes();
    }

    @Deprecated
    @Nonnull
    public Map<GitLocalBranch, Hash> getLocalBranchesWithHashes() {
        return localBranches();
    }

    @Deprecated
    @Nonnull
    public Map<GitRemoteBranch, Hash> getRemoteBranchesWithHashes() {
        return remoteBranches();
    }

    @Deprecated
    @Nonnull
    public Collection<GitRemoteBranch> getRemoteBranches() {
        return remoteBranches().keySet();
    }

    @Deprecated
    @Nonnull
    public Collection<GitBranchTrackInfo> getBranchTrackInfos() {
        return branchTrackInfos();
    }

    @Deprecated
    @Nullable
    public String getCurrentRevision() {
        return currentRevision();
    }

    @Deprecated
    @Nonnull
    public Repository.State getState() {
        return state();
    }

    @Deprecated
    @Nonnull
    public Collection<GitSubmoduleInfo> getSubmodules() {
        return submodules();
    }

    @Deprecated
    @Nonnull
    public GitHooksInfo getHooksInfo() {
        return hooksInfo();
    }

    @Override
    public boolean equals(Object o) {
        //noinspection SimplifiableIfStatement
        if (this == o) {
            return true;
        }
        return o instanceof GitRepoInfo info
            && state == info.state
            && Objects.equals(currentRevision, info.currentRevision)
            && Objects.equals(currentBranch, info.currentBranch)
            && remotes.equals(info.remotes)
            && branchTrackInfos.equals(info.branchTrackInfos)
            && areEqual(localBranches, info.localBranches)
            && areEqual(remoteBranches, info.remoteBranches)
            && submodules.equals(info.submodules)
            && hooksInfo.equals(info.hooksInfo);
    }

    private static <T extends GitBranch> boolean areEqual(Map<T, Hash> c1, Map<T, Hash> c2) {
        // GitBranch has perverted equals contract (see the comment there)
        // until GitBranch is created only from a single place with correctly defined Hash, we can't change its equals
        Set<Map.Entry<? extends GitBranch, Hash>> set1 = Sets.newHashSet(c1.entrySet(), new BranchesComparingStrategy());
        Set<Map.Entry<? extends GitBranch, Hash>> set2 = Sets.newHashSet(c2.entrySet(), new BranchesComparingStrategy());
        return set1.equals(set2);
    }

    private static class BranchesComparingStrategy implements HashingStrategy<Map.Entry<? extends GitBranch, Hash>> {
        @Override
        public int hashCode(@Nonnull Map.Entry<? extends GitBranch, Hash> branchEntry) {
            return 31 * branchEntry.getKey().getName().hashCode() + branchEntry.getValue().hashCode();
        }

        @Override
        public boolean equals(@Nonnull Map.Entry<? extends GitBranch, Hash> b1, @Nonnull Map.Entry<? extends GitBranch, Hash> b2) {
            //noinspection SimplifiableIfStatement
            if (b1 == b2) {
                return true;
            }

            return b1.getClass() == b2.getClass()
                && b1.getKey().getName().equals(b2.getKey().getName())
                && b1.getValue().equals(b2.getValue());
        }
    }
}
