/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package git4idea.rebase;

import consulo.application.progress.ProgressIndicator;
import consulo.ide.ServiceManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.log.Hash;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.branch.GitRebaseParams;
import git4idea.commands.Git;
import git4idea.repo.GitRepository;
import git4idea.stash.GitChangesSaver;
import git4idea.stash.GitStashChangesSaver;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

import static consulo.versionControlSystem.distributed.DvcsUtil.getShortNames;

public class GitRebaseSpec {
    private static final Logger LOG = Logger.getInstance(GitRebaseSpec.class);

    @Nullable
    private final GitRebaseParams myParams;
    @Nonnull
    private final Map<GitRepository, GitRebaseStatus> myStatuses;
    @Nonnull
    private final Map<GitRepository, String> myInitialHeadPositions;
    @Nonnull
    private final Map<GitRepository, String> myInitialBranchNames;
    @Nonnull
    private final GitChangesSaver mySaver;
    private final boolean myShouldBeSaved;

    public GitRebaseSpec(
        @Nullable GitRebaseParams params,
        @Nonnull Map<GitRepository, GitRebaseStatus> statuses,
        @Nonnull Map<GitRepository, String> initialHeadPositions,
        @Nonnull Map<GitRepository, String> initialBranchNames,
        @Nonnull GitChangesSaver saver,
        boolean shouldBeSaved
    ) {
        myParams = params;
        myStatuses = statuses;
        myInitialHeadPositions = initialHeadPositions;
        myInitialBranchNames = initialBranchNames;
        mySaver = saver;
        myShouldBeSaved = shouldBeSaved;
    }

    @Nonnull
    public static GitRebaseSpec forNewRebase(
        @Nonnull Project project,
        @Nonnull GitRebaseParams params,
        @Nonnull Collection<GitRepository> repositories,
        @Nonnull ProgressIndicator indicator
    ) {
        GitUtil.updateRepositories(repositories);
        Map<GitRepository, String> initialHeadPositions = findInitialHeadPositions(repositories, params.getBranch());
        Map<GitRepository, String> initialBranchNames = findInitialBranchNames(repositories);
        Map<GitRepository, GitRebaseStatus> initialStatusMap = new TreeMap<>(DvcsUtil.REPOSITORY_COMPARATOR);
        for (GitRepository repository : repositories) {
            initialStatusMap.put(repository, GitRebaseStatus.notStarted());
        }
        return new GitRebaseSpec(params, initialStatusMap, initialHeadPositions, initialBranchNames, newSaver(project, indicator), true);
    }

    @Nullable
    public static GitRebaseSpec forResumeInSingleRepository(
        @Nonnull Project project,
        @Nonnull GitRepository repository,
        @Nonnull ProgressIndicator indicator
    ) {
        if (!repository.isRebaseInProgress()) {
            return null;
        }
        GitRebaseStatus suspended = new GitRebaseStatus(GitRebaseStatus.Type.SUSPENDED, Collections.<GitRebaseUtils.CommitInfo>emptyList());
        return new GitRebaseSpec(
            null,
            Collections.singletonMap(repository, suspended),
            Collections.<GitRepository, String>emptyMap(),
            Collections.<GitRepository, String>emptyMap(),
            newSaver(project, indicator),
            false
        );
    }

    public boolean isValid() {
        return singleOngoingRebase() && rebaseStatusesMatch();
    }

    @Nonnull
    public GitChangesSaver getSaver() {
        return mySaver;
    }

    @Nonnull
    public Collection<GitRepository> getAllRepositories() {
        return myStatuses.keySet();
    }

    @Nullable
    public GitRepository getOngoingRebase() {
        return ContainerUtil.getFirstItem(getOngoingRebases());
    }

    @Nullable
    public GitRebaseParams getParams() {
        return myParams;
    }

    @Nonnull
    public Map<GitRepository, GitRebaseStatus> getStatuses() {
        return Collections.unmodifiableMap(myStatuses);
    }

    @Nonnull
    public Map<GitRepository, String> getHeadPositionsToRollback() {
        return ContainerUtil.filter(
            myInitialHeadPositions,
            repository -> myStatuses.get(repository).getType() == GitRebaseStatus.Type.SUCCESS
        );
    }

    /**
     * Returns names of branches which were current at the moment of this GitRebaseSpec creation. <br/>
     * The map may contain null elements, if some repositories were in the detached HEAD state.
     */
    @Nonnull
    public Map<GitRepository, String> getInitialBranchNames() {
        return myInitialBranchNames;
    }

    @Nonnull
    public GitRebaseSpec cloneWithNewStatuses(@Nonnull Map<GitRepository, GitRebaseStatus> statuses) {
        return new GitRebaseSpec(myParams, statuses, myInitialHeadPositions, myInitialBranchNames, mySaver, true);
    }

    public boolean shouldBeSaved() {
        return myShouldBeSaved;
    }

    /**
     * Returns repositories for which rebase is in progress, has failed and we want to retry, or didn't start yet. <br/>
     * It is guaranteed that if there is a rebase in progress (returned by {@link #getOngoingRebase()}, it will be the first in the list.
     */
    @Nonnull
    public List<GitRepository> getIncompleteRepositories() {
        List<GitRepository> incompleteRepositories = new ArrayList<>();
        GitRepository ongoingRebase = getOngoingRebase();
        if (ongoingRebase != null) {
            incompleteRepositories.add(ongoingRebase);
        }
        incompleteRepositories.addAll(DvcsUtil.sortRepositories(ContainerUtil.filter(
            myStatuses.keySet(),
            repository -> !repository.equals(ongoingRebase)
                && myStatuses.get(repository).getType() != GitRebaseStatus.Type.SUCCESS
        )));
        return incompleteRepositories;
    }

    @Nonnull
    private static GitStashChangesSaver newSaver(@Nonnull Project project, @Nonnull ProgressIndicator indicator) {
        Git git = ServiceManager.getService(Git.class);
        return new GitStashChangesSaver(project, git, indicator, "Uncommitted changes before rebase");
    }

    @Nonnull
    private static Map<GitRepository, String> findInitialHeadPositions(
        @Nonnull Collection<GitRepository> repositories,
        @Nullable String branchToCheckout
    ) {
        return ContainerUtil.map2Map(repositories, repository ->
        {
            String currentRevision = findCurrentRevision(repository, branchToCheckout);
            LOG.debug("Current revision in [" + repository.getRoot().getName() + "] is [" + currentRevision + "]");
            return Pair.create(repository, currentRevision);
        });
    }

    @Nullable
    private static String findCurrentRevision(@Nonnull GitRepository repository, @Nullable String branchToCheckout) {
        if (branchToCheckout != null) {
            GitLocalBranch branch = repository.getBranches().findLocalBranch(branchToCheckout);
            if (branch != null) {
                Hash hash = repository.getBranches().getHash(branch);
                if (hash != null) {
                    return hash.asString();
                }
                else {
                    LOG.warn("The hash for branch [" + branchToCheckout + "] is not known!");
                }
            }
            else {
                LOG.warn("The branch [" + branchToCheckout + "] is not known!");
            }
        }
        return repository.getCurrentRevision();
    }

    @Nonnull
    private static Map<GitRepository, String> findInitialBranchNames(@Nonnull Collection<GitRepository> repositories) {
        return ContainerUtil.map2Map(repositories, repository ->
        {
            String currentBranchName = repository.getCurrentBranchName();
            LOG.debug("Current branch in [" + repository.getRoot().getName() + "] is [" + currentBranchName + "]");
            return Pair.create(repository, currentBranchName);
        });
    }

    @Nonnull
    private Collection<GitRepository> getOngoingRebases() {
        return ContainerUtil.filter(
            myStatuses.keySet(),
            repository -> myStatuses.get(repository).getType() == GitRebaseStatus.Type.SUSPENDED
        );
    }

    private boolean singleOngoingRebase() {
        Collection<GitRepository> ongoingRebases = getOngoingRebases();
        if (ongoingRebases.size() > 1) {
            LOG.warn("Invalid rebase spec: rebase is in progress in " + getShortNames(ongoingRebases));
            return false;
        }
        return true;
    }

    private boolean rebaseStatusesMatch() {
        for (GitRepository repository : myStatuses.keySet()) {
            GitRebaseStatus.Type savedStatus = myStatuses.get(repository).getType();
            if (repository.isRebaseInProgress() && savedStatus != GitRebaseStatus.Type.SUSPENDED) {
                LOG.warn("Invalid rebase spec: rebase is in progress in " +
                    DvcsUtil.getShortRepositoryName(repository) + ", but it is saved as " + savedStatus);
                return false;
            }
            else if (!repository.isRebaseInProgress() && savedStatus == GitRebaseStatus.Type.SUSPENDED) {
                LOG.warn("Invalid rebase spec: rebase is not in progress in " + DvcsUtil.getShortRepositoryName(repository));
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        String initialHeadPositions = StringUtil.join(
            myInitialHeadPositions.keySet(),
            repository -> DvcsUtil.getShortRepositoryName(repository) + ": " + myInitialHeadPositions.get(repository),
            ", "
        );
        String statuses = StringUtil.join(
            myStatuses.keySet(),
            repository -> DvcsUtil.getShortRepositoryName(repository) + ": " + myStatuses.get(repository),
            ", "
        );
        return String.format(
            "{Params: [%s].\nInitial positions: %s.\nStatuses: %s.\nSaver: %s}",
            myParams,
            initialHeadPositions,
            statuses,
            mySaver
        );
    }
}
