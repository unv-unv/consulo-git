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
package git4idea;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.branch.DvcsTaskHandler;
import git4idea.branch.GitBrancher;
import git4idea.branch.GitBranchesCollection;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.validators.GitRefNameValidator;
import jakarta.inject.Inject;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * @author Dmitry Avdeev
 * @since 2013-07-17
 */
@ExtensionImpl
public class GitTaskHandler extends DvcsTaskHandler<GitRepository> {
    @Nonnull
    private final GitBrancher myBrancher;
    @Nonnull
    private final GitRefNameValidator myNameValidator;

    @Inject
    public GitTaskHandler(@Nonnull GitBrancher brancher, @Nonnull GitRepositoryManager repositoryManager, @Nonnull Project project) {
        super(repositoryManager, project, "branch");
        myBrancher = brancher;
        myNameValidator = GitRefNameValidator.getInstance();
    }

    @Override
    protected void checkout(@Nonnull String taskName, @Nonnull List<GitRepository> repos, @Nullable Runnable callInAwtLater) {
        myBrancher.checkout(taskName, false, repos, callInAwtLater);
    }

    @Override
    protected void checkoutAsNewBranch(@Nonnull String name, @Nonnull List<GitRepository> repositories) {
        myBrancher.checkoutNewBranch(name, repositories);
    }

    @Override
    protected String getActiveBranch(GitRepository repository) {
        return repository.getCurrentBranchName();
    }

    @Override
    protected void mergeAndClose(@Nonnull String branch, @Nonnull List<GitRepository> repositories) {
        myBrancher.merge(branch, GitBrancher.DeleteOnMergeOption.DELETE, repositories);
    }

    @Override
    protected boolean hasBranch(@Nonnull GitRepository repository, @Nonnull TaskInfo info) {
        GitBranchesCollection branches = repository.getBranches();
        return info.isRemote()
            ? branches.getRemoteBranches().stream().anyMatch(branch -> info.getName().equals(branch.getName()))
            : branches.findLocalBranch(info.getName()) != null;
    }

    @Nonnull
    @Override
    protected Iterable<TaskInfo> getAllBranches(@Nonnull GitRepository repository) {
        GitBranchesCollection branches = repository.getBranches();
        List<TaskInfo> list = ContainerUtil.map(
            branches.getLocalBranches(),
            branch -> new TaskInfo(
                branch.getName(),
                Collections.singleton(repository.getPresentableUrl())
            )
        );
        list.addAll(ContainerUtil.map(
            branches.getRemoteBranches(),
            (Function<GitBranch, TaskInfo>)branch -> new TaskInfo(branch.getName(), Collections.singleton(repository.getPresentableUrl())) {
                @Override
                public boolean isRemote() {
                    return true;
                }
            }
        ));
        return list;
    }

    @Override
    public boolean isBranchNameValid(@Nonnull String branchName) {
        return myNameValidator.checkInput(branchName);
    }

    @Nonnull
    @Override
    public String cleanUpBranchName(@Nonnull String suggestedName) {
        return myNameValidator.cleanUpBranchName(suggestedName);
    }
}
