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
package git4idea.update;

import static git4idea.GitUtil.HEAD;
import static git4idea.config.UpdateMethod.MERGE;
import static git4idea.config.UpdateMethod.REBASE;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nonnull;
import consulo.logging.Logger;
import consulo.application.progress.ProgressIndicator;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import consulo.versionControlSystem.AbstractVcsHelper;
import consulo.versionControlSystem.update.UpdatedFiles;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchPair;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.config.GitConfigUtil;
import git4idea.config.GitVersionSpecialty;
import git4idea.config.UpdateMethod;
import git4idea.merge.MergeChangeCollector;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

/**
 * Updates a single repository via merge or rebase.
 *
 * @see GitRebaseUpdater
 * @see GitMergeUpdater
 */
public abstract class GitUpdater {
    private static final Logger LOG = Logger.getInstance(GitUpdater.class);

    @Nonnull
    protected final Project myProject;
    @Nonnull
    protected final Git myGit;
    @Nonnull
    protected final VirtualFile myRoot;
    @Nonnull
    protected final GitRepository myRepository;
    @Nonnull
    protected final GitBranchPair myBranchPair;
    @Nonnull
    protected final ProgressIndicator myProgressIndicator;
    @Nonnull
    protected final UpdatedFiles myUpdatedFiles;
    @Nonnull
    protected final AbstractVcsHelper myVcsHelper;
    @Nonnull
    protected final GitRepositoryManager myRepositoryManager;
    protected final GitVcs myVcs;

    protected GitRevisionNumber myBefore; // The revision that was before update

    protected GitUpdater(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull GitRepository repository,
        @Nonnull GitBranchPair branchAndTracked,
        @Nonnull ProgressIndicator progressIndicator,
        @Nonnull UpdatedFiles updatedFiles
    ) {
        myProject = project;
        myGit = git;
        myRoot = repository.getRoot();
        myRepository = repository;
        myBranchPair = branchAndTracked;
        myProgressIndicator = progressIndicator;
        myUpdatedFiles = updatedFiles;
        myVcsHelper = AbstractVcsHelper.getInstance(project);
        myVcs = GitVcs.getInstance(project);
        myRepositoryManager = GitUtil.getRepositoryManager(myProject);
    }

    /**
     * Returns proper updater based on the update policy (merge or rebase) selected by user or stored in his .git/config
     *
     * @return {@link GitMergeUpdater} or {@link GitRebaseUpdater}.
     */
    @Nonnull
    public static GitUpdater getUpdater(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull GitBranchPair trackedBranches,
        @Nonnull GitRepository repository,
        @Nonnull ProgressIndicator progressIndicator,
        @Nonnull UpdatedFiles updatedFiles,
        @Nonnull UpdateMethod updateMethod
    ) {
        if (updateMethod == UpdateMethod.BRANCH_DEFAULT) {
            updateMethod = resolveUpdateMethod(repository);
        }
        return updateMethod == UpdateMethod.REBASE
            ? new GitRebaseUpdater(project, git, repository, trackedBranches, progressIndicator, updatedFiles)
            : new GitMergeUpdater(project, git, repository, trackedBranches, progressIndicator, updatedFiles);
    }

    @Nonnull
    public static UpdateMethod resolveUpdateMethod(@Nonnull GitRepository repository) {
        Project project = repository.getProject();
        GitLocalBranch branch = repository.getCurrentBranch();
        if (branch != null) {
            String branchName = branch.getName();
            try {
                String rebaseValue = GitConfigUtil.getValue(project, repository.getRoot(), "branch." + branchName + ".rebase");
                if (rebaseValue != null) {
                    if (isRebaseValue(rebaseValue)) {
                        return REBASE;
                    }
                    if (GitConfigUtil.getBooleanValue(rebaseValue) == Boolean.FALSE) {
                        // explicit override of a more generic pull.rebase config value
                        return MERGE;
                    }
                    LOG.warn("Unknown value for branch." + branchName + ".rebase: " + rebaseValue);
                }
            }
            catch (VcsException e) {
                LOG.warn("Couldn't get git config branch." + branchName + ".rebase");
            }
        }

        if (GitVersionSpecialty.KNOWS_PULL_REBASE.existsIn(GitVcs.getInstance(project).getVersion())) {
            try {
                String pullRebaseValue = GitConfigUtil.getValue(project, repository.getRoot(), "pull.rebase");
                if (pullRebaseValue != null && isRebaseValue(pullRebaseValue)) {
                    return REBASE;
                }
            }
            catch (VcsException e) {
                LOG.warn("Couldn't get git config pull.rebase");
            }
        }

        return MERGE;
    }

    private static boolean isRebaseValue(@Nonnull String configValue) {
        // 'yes' is not specified in the man, but actually works
        return GitConfigUtil.getBooleanValue(configValue) == Boolean.TRUE
            || configValue.equalsIgnoreCase("interactive")
            || configValue.equalsIgnoreCase("preserve");
    }

    @Nonnull
    public GitUpdateResult update() throws VcsException {
        markStart(myRoot);
        try {
            return doUpdate();
        }
        finally {
            markEnd(myRoot);
        }
    }

    /**
     * Checks the repository if local changes need to be saved before update.
     * For rebase local changes need to be saved always,
     * for merge - only in the case if merge affects the same files or there is something in the index.
     *
     * @return true if local changes from this root need to be saved, false if not.
     */
    public abstract boolean isSaveNeeded();

    /**
     * Checks if update is needed, i.e. if there are remote changes that weren't merged into the current branch.
     *
     * @return true if update is needed, false otherwise.
     */
    public boolean isUpdateNeeded() throws VcsException {
        GitBranch dest = myBranchPair.getDest();
        assert dest != null;
        String remoteBranch = dest.getName();
        if (!hasRemoteChanges(remoteBranch)) {
            LOG.info("isUpdateNeeded: No remote changes, update is not needed");
            return false;
        }
        return true;
    }

    /**
     * Performs update (via rebase or merge - depending on the implementing classes).
     */
    @Nonnull
    protected abstract GitUpdateResult doUpdate();

    @Nonnull
    GitBranchPair getSourceAndTarget() {
        return myBranchPair;
    }

    protected void markStart(VirtualFile root) throws VcsException {
        // remember the current position
        myBefore = GitRevisionNumber.resolve(myProject, root, "HEAD");
    }

    protected void markEnd(VirtualFile root) throws VcsException {
        // find out what have changed, this is done even if the process was cancelled.
        MergeChangeCollector collector = new MergeChangeCollector(myProject, root, myBefore);
        List<VcsException> exceptions = new ArrayList<>();
        collector.collect(myUpdatedFiles, exceptions);
        if (!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }
    }

    protected boolean hasRemoteChanges(@Nonnull String remoteBranch) throws VcsException {
        GitLineHandler handler = new GitLineHandler(myProject, myRoot, GitCommand.REV_LIST);
        handler.setSilent(true);
        handler.addParameters("-1");
        handler.addParameters(HEAD + ".." + remoteBranch);
        String output = myGit.runCommand(handler).getOutputOrThrow();
        return !output.isEmpty();
    }
}
