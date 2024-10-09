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
package git4idea.actions;

import static consulo.ide.impl.idea.openapi.vcs.history.VcsDiffUtil.createChangesWithCurrentContentForFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import jakarta.annotation.Nonnull;
import consulo.ide.impl.idea.dvcs.actions.DvcsCompareWithBranchAction;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.versionControlSystem.FilePath;
import git4idea.GitBranch;
import git4idea.GitContentRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.changes.GitChangeUtils;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

public class GitCompareWithBranchAction extends DvcsCompareWithBranchAction<GitRepository> {
    @Override
    protected boolean noBranchesToCompare(@Nonnull GitRepository repository) {
        int locals = repository.getBranches().getLocalBranches().size();
        boolean haveRemotes = !repository.getBranches().getRemoteBranches().isEmpty();
        if (repository.isOnBranch()) {  // there are other branches to compare
            return locals < 2 && !haveRemotes;
        }
        return locals == 0 && !haveRemotes; // there are at least 1 branch to compare
    }

    @Nonnull
    @Override
    protected List<String> getBranchNamesExceptCurrent(@Nonnull GitRepository repository) {
        List<GitBranch> localBranches = new ArrayList<>(repository.getBranches().getLocalBranches());
        Collections.sort(localBranches);
        List<GitBranch> remoteBranches = new ArrayList<>(repository.getBranches().getRemoteBranches());
        Collections.sort(remoteBranches);

        if (repository.isOnBranch()) {
            localBranches.remove(repository.getCurrentBranch());
        }

        List<String> branchNames = ContainerUtil.newArrayList();
        for (GitBranch branch : localBranches) {
            branchNames.add(branch.getName());
        }
        for (GitBranch branch : remoteBranches) {
            branchNames.add(branch.getName());
        }
        return branchNames;
    }

    @Nonnull
    @Override
    protected GitRepositoryManager getRepositoryManager(@Nonnull Project project) {
        return GitUtil.getRepositoryManager(project);
    }

    @Override
    @Nonnull
    protected Collection<Change> getDiffChanges(
        @Nonnull Project project,
        @Nonnull VirtualFile file,
        @Nonnull String branchToCompare
    ) throws VcsException {
        FilePath filePath = VcsUtil.getFilePath(file);
        final GitRepository gitRepository = GitUtil.getRepositoryManager(project).getRepositoryForFile(file);
        if (gitRepository == null) {
            throw new VcsException("Couldn't find Git Repository for " + file.getName());
        }
        final VirtualFile gitRepositoryRoot = gitRepository.getRoot();
        GitRevisionNumber compareRevisionNumber = new GitRevisionNumber(branchToCompare);
        Collection<Change> changes =
            GitChangeUtils.getDiffWithWorkingDir(project, gitRepositoryRoot, branchToCompare, Collections.singletonList(filePath), false);
        // if git returned no changes we need to check that file exist in compareWith branch to avoid this error in diff dialog
        // a.e. when you perform compareWith for unversioned file
        if (changes.isEmpty() && GitHistoryUtils.getCurrentRevision(project, filePath, branchToCompare) == null) {
            throw new VcsException(fileDoesntExistInBranchError(file, branchToCompare));
        }
        return changes.isEmpty() && !filePath.isDirectory()
            ? createChangesWithCurrentContentForFile(
                filePath,
                GitContentRevision.createRevision(filePath, compareRevisionNumber, project, null)
            ) : changes;
    }
}
