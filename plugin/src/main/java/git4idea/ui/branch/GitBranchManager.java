/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.branch.BranchStorage;
import consulo.versionControlSystem.distributed.branch.DvcsBranchInfo;
import git4idea.branch.GitBranchType;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import jakarta.annotation.Nullable;

import java.util.List;

import static consulo.util.collection.ContainerUtil.map2List;
import static git4idea.log.GitRefManager.MASTER;
import static git4idea.log.GitRefManager.ORIGIN_MASTER;

@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitBranchManager {
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;
    @Nonnull
    private final GitVcsSettings mySettings;
    @Nonnull
    public final BranchStorage myPredefinedFavoriteBranches = new BranchStorage();

    @Inject
    public GitBranchManager(@Nonnull Project project, @Nonnull GitVcsSettings settings) {
        myRepositoryManager = GitRepositoryManager.getInstance(project);
        mySettings = settings;
        for (GitBranchType type : GitBranchType.values()) {
            myPredefinedFavoriteBranches.myBranches.put(type.toString(), constructDefaultBranchPredefinedList(type));
        }
    }

    @Nonnull
    private List<DvcsBranchInfo> constructDefaultBranchPredefinedList(GitBranchType type) {
        List<DvcsBranchInfo> branchInfos = ContainerUtil.newArrayList(new DvcsBranchInfo("", getDefaultBranchName(type)));
        branchInfos.addAll(map2List(
            myRepositoryManager.getRepositories(),
            repository -> new DvcsBranchInfo(repository.getRoot().getPath(), getDefaultBranchName(type))
        ));
        return branchInfos;
    }

    @Nonnull
    private static String getDefaultBranchName(@Nonnull GitBranchType type) {
        return type == GitBranchType.LOCAL ? MASTER : ORIGIN_MASTER;
    }

    public boolean isFavorite(@Nonnull GitBranchType branchType, @Nullable GitRepository repository, @Nonnull String branchName) {
        if (mySettings.isFavorite(branchType, repository, branchName)) {
            return true;
        }
        return !mySettings.isExcludedFromFavorites(branchType, repository, branchName)
            && myPredefinedFavoriteBranches.contains(branchType.toString(), repository, branchName);
    }

    public void setFavorite(
        @Nonnull GitBranchType branchType,
        @Nullable GitRepository repository,
        @Nonnull String branchName,
        boolean shouldBeFavorite
    ) {
        if (shouldBeFavorite) {
            mySettings.addToFavorites(branchType, repository, branchName);
            mySettings.removeFromExcluded(branchType, repository, branchName);
        }
        else if (mySettings.isFavorite(branchType, repository, branchName)) {
            mySettings.removeFromFavorites(branchType, repository, branchName);
        }
        else if (myPredefinedFavoriteBranches.contains(branchType.toString(), repository, branchName)) {
            mySettings.excludedFromFavorites(branchType, repository, branchName);
        }
    }
}
