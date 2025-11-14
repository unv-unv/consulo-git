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
package git4idea.util;

import consulo.logging.Logger;
import consulo.util.lang.Couple;
import consulo.versionControlSystem.change.Change;
import git4idea.GitCommit;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

import java.util.*;

/**
 * @author Kirill Likhodedov
 */
public class GitCommitCompareInfo {
    private static final Logger LOG = Logger.getInstance(GitCommitCompareInfo.class);
    private final Map<GitRepository, Couple<List<GitCommit>>> myInfo = new HashMap<>();
    private final Map<GitRepository, Collection<Change>> myTotalDiff = new HashMap<>();
    private final InfoType myInfoType;

    public GitCommitCompareInfo() {
        this(InfoType.BOTH);
    }

    public GitCommitCompareInfo(@Nonnull InfoType infoType) {
        myInfoType = infoType;
    }

    public void put(@Nonnull GitRepository repository, @Nonnull Couple<List<GitCommit>> commits) {
        myInfo.put(repository, commits);
    }

    public void put(@Nonnull GitRepository repository, @Nonnull Collection<Change> totalDiff) {
        myTotalDiff.put(repository, totalDiff);
    }

    @Nonnull
    public List<GitCommit> getHeadToBranchCommits(@Nonnull GitRepository repo) {
        return getCompareInfo(repo).getFirst();
    }

    @Nonnull
    public List<GitCommit> getBranchToHeadCommits(@Nonnull GitRepository repo) {
        return getCompareInfo(repo).getSecond();
    }

    @Nonnull
    private Couple<List<GitCommit>> getCompareInfo(@Nonnull GitRepository repo) {
        Couple<List<GitCommit>> pair = myInfo.get(repo);
        if (pair == null) {
            LOG.error("Compare info not found for repository " + repo);
            return Couple.of(Collections.<GitCommit>emptyList(), Collections.<GitCommit>emptyList());
        }
        return pair;
    }

    @Nonnull
    public Collection<GitRepository> getRepositories() {
        return myInfo.keySet();
    }

    public boolean isEmpty() {
        return myInfo.isEmpty();
    }

    public InfoType getInfoType() {
        return myInfoType;
    }

    @Nonnull
    public List<Change> getTotalDiff() {
        List<Change> changes = new ArrayList<>();
        for (Collection<Change> changeCollection : myTotalDiff.values()) {
            changes.addAll(changeCollection);
        }
        return changes;
    }

    public enum InfoType {
        BOTH,
        HEAD_TO_BRANCH,
        BRANCH_TO_HEAD
    }
}
