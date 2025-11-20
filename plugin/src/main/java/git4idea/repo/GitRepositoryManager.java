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
package git4idea.repo;

import consulo.annotation.component.ExtensionImpl;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.change.VirtualFileHierarchicalComparator;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.branch.DvcsSyncSettings;
import consulo.versionControlSystem.distributed.repository.AbstractRepositoryManager;
import consulo.versionControlSystem.distributed.repository.RepositoryManager;
import consulo.versionControlSystem.distributed.repository.VcsRepositoryManager;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitVcs;
import git4idea.config.GitVcsSettings;
import git4idea.rebase.GitRebaseSpec;
import git4idea.ui.branch.GitMultiRootBranchConfig;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@ExtensionImpl
public class GitRepositoryManager extends AbstractRepositoryManager<GitRepository> {
    @Nullable
    public static GitRepositoryManager getInstance(@Nonnull Project project) {
        return (GitRepositoryManager) RepositoryManager.<GitRepository>getInstance(project, GitVcs.getKey());
    }

    private static final Logger LOG = Logger.getInstance(GitRepositoryManager.class);

    public static final Comparator<GitRepository> DEPENDENCY_COMPARATOR =
        (repo1, repo2) -> -VirtualFileHierarchicalComparator.getInstance().compare(repo1.getRoot(), repo2.getRoot());

    @Nullable
    private volatile GitRebaseSpec myOngoingRebaseSpec;

    @Inject
    public GitRepositoryManager(@Nonnull Project project, @Nonnull VcsRepositoryManager vcsRepositoryManager) {
        super(project, vcsRepositoryManager, GitVcs.getKey());
    }

    @Override
    public boolean isSyncEnabled() {
        GitVcs vcs = (GitVcs) getVcs();
        GitVcsSettings gitVcsSettings = GitVcsSettings.getInstance(vcs.getProject());
        return gitVcsSettings.getSyncSetting() == DvcsSyncSettings.Value.SYNC && !new GitMultiRootBranchConfig(getRepositories()).diverged();
    }

    @Nonnull
    @Override
    public List<GitRepository> getRepositories() {
        return getRepositories(GitRepository.class);
    }

    @Nullable
    public GitRebaseSpec getOngoingRebaseSpec() {
        GitRebaseSpec rebaseSpec = myOngoingRebaseSpec;
        return rebaseSpec != null && rebaseSpec.isValid() ? rebaseSpec : null;
    }

    public boolean hasOngoingRebase() {
        return getOngoingRebaseSpec() != null;
    }

    public void setOngoingRebaseSpec(@Nullable GitRebaseSpec ongoingRebaseSpec) {
        myOngoingRebaseSpec = ongoingRebaseSpec != null && ongoingRebaseSpec.isValid() ? ongoingRebaseSpec : null;
    }

    @Nonnull
    public Collection<GitRepository> getDirectSubmodules(@Nonnull GitRepository superProject) {
        Collection<GitSubmoduleInfo> modules = superProject.getSubmodules();
        return ContainerUtil.mapNotNull(modules, module ->
        {
            VirtualFile submoduleDir = superProject.getRoot().findFileByRelativePath(module.path());
            if (submoduleDir == null) {
                LOG.debug("submodule dir not found at declared path [" + module.path() + "] of root [" + superProject.getRoot() + "]");
                return null;
            }
            GitRepository repository = getRepositoryForRoot(submoduleDir);
            if (repository == null) {
                LOG.warn("Submodule not registered as a repository: " + submoduleDir);
            }
            return repository;
        });
    }

    /**
     * <p>Sorts repositories "by dependency",
     * which means that if one repository "depends" on the other, it should be updated or pushed first.</p>
     * <p>Currently submodule-dependency is the only one which is taken into account.</p>
     * <p>If repositories are independent of each other, they are sorted {@link DvcsUtil#REPOSITORY_COMPARATOR by path}.</p>
     */
    @Nonnull
    public List<GitRepository> sortByDependency(@Nonnull Collection<GitRepository> repositories) {
        return ContainerUtil.sorted(repositories, DEPENDENCY_COMPARATOR);
    }
}
