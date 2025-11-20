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
package git4idea.ui.branch;

import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.branch.DvcsMultiRootBranchConfig;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * @author Kirill Likhodedov
 */
public class GitMultiRootBranchConfig extends DvcsMultiRootBranchConfig<GitRepository> {
    public GitMultiRootBranchConfig(@Nonnull Collection<GitRepository> repositories) {
        super(repositories);
    }

    @Override
    @Nonnull
    public Collection<String> getLocalBranchNames() {
        return GitBranchUtil.getCommonBranches(myRepositories, true);
    }

    @Nonnull
    Collection<String> getRemoteBranches() {
        return GitBranchUtil.getCommonBranches(myRepositories, false);
    }

    /**
     * If there is a common remote branch which is commonly tracked by the given branch in all repositories,
     * returns the name of this remote branch. Otherwise returns null. <br/>
     * For one repository just returns the tracked branch or null if there is no tracked branch.
     */
    @Nullable
    public String getTrackedBranch(@Nonnull String branch) {
        String trackedName = null;
        for (GitRepository repository : myRepositories) {
            GitRemoteBranch tracked = getTrackedBranch(repository, branch);
            if (tracked == null) {
                return null;
            }
            if (trackedName == null) {
                trackedName = tracked.getNameForLocalOperations();
            }
            else if (!trackedName.equals(tracked.getNameForLocalOperations())) {
                return null;
            }
        }
        return trackedName;
    }

    /**
     * Returns local branches which track the given remote branch. Usually there is 0 or 1 such branches.
     */
    @Nonnull
    public Collection<String> getTrackingBranches(@Nonnull String remoteBranch) {
        Collection<String> trackingBranches = null;
        for (GitRepository repository : myRepositories) {
            Collection<String> tb = getTrackingBranches(repository, remoteBranch);
            if (trackingBranches == null) {
                trackingBranches = tb;
            }
            else {
                trackingBranches = ContainerUtil.intersection(trackingBranches, tb);
            }
        }
        return trackingBranches == null ? Collections.<String>emptyList() : trackingBranches;
    }

    @Nonnull
    public static Collection<String> getTrackingBranches(@Nonnull GitRepository repository, @Nonnull String remoteBranch) {
        Collection<String> trackingBranches = new ArrayList<>(1);
        for (GitBranchTrackInfo trackInfo : repository.getBranchTrackInfos()) {
            if (remoteBranch.equals(trackInfo.getRemote().getName() + "/" + trackInfo.remoteBranch())) {
                trackingBranches.add(trackInfo.localBranch().getName());
            }
        }
        return trackingBranches;
    }

    @Nullable
    private static GitRemoteBranch getTrackedBranch(@Nonnull GitRepository repository, @Nonnull String branchName) {
        GitLocalBranch branch = repository.getBranches().findLocalBranch(branchName);
        return branch == null ? null : branch.findTrackedBranch(repository);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (GitRepository repository : myRepositories) {
            sb.append(repository.getPresentableUrl())
                .append(":")
                .append(repository.getCurrentBranch())
                .append(":")
                .append(repository.getState());
        }
        return sb.toString();
    }
}
