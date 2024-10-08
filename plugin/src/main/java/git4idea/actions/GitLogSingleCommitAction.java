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
package git4idea.actions;

import consulo.ide.ServiceManager;
import consulo.ide.impl.idea.dvcs.ui.VcsLogSingleCommitAction;
import consulo.project.Project;
import consulo.versionControlSystem.distributed.repository.AbstractRepositoryManager;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public abstract class GitLogSingleCommitAction extends VcsLogSingleCommitAction<GitRepository> {
    @Nonnull
    @Override
    protected AbstractRepositoryManager<GitRepository> getRepositoryManager(@Nonnull Project project) {
        return ServiceManager.getService(project, GitRepositoryManager.class);
    }

    @Override
    @Nullable
    protected GitRepository getRepositoryForRoot(@Nonnull Project project, @Nonnull VirtualFile root) {
        return getRepositoryManager(project).getRepositoryForRoot(root);
    }
}
