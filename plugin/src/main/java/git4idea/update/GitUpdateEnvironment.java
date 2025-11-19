/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

import static git4idea.GitUtil.getRepositoriesFromRoots;
import static git4idea.GitUtil.getRepositoryManager;
import static git4idea.GitUtil.gitRoots;
import static git4idea.GitUtil.isUnderGit;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import consulo.ui.annotation.RequiredUIAccess;
import jakarta.annotation.Nonnull;

import consulo.application.progress.ProgressIndicator;
import consulo.component.ProcessCanceledException;
import consulo.configurable.Configurable;
import consulo.project.Project;
import consulo.versionControlSystem.update.UpdateEnvironment;
import consulo.versionControlSystem.update.UpdatedFiles;
import consulo.util.lang.ref.Ref;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.update.SequentialUpdatesContext;
import consulo.versionControlSystem.update.UpdateSession;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepositoryManager;

import jakarta.annotation.Nullable;

public class GitUpdateEnvironment implements UpdateEnvironment {
    private final Project myProject;
    private final GitVcsSettings mySettings;

    public GitUpdateEnvironment(@Nonnull Project project, @Nonnull GitVcsSettings settings) {
        myProject = project;
        mySettings = settings;
    }

    @Override
    public void fillGroups(UpdatedFiles updatedFiles) {
        //unused, there are no custom categories yet
    }

    @Nonnull
    @Override
    @RequiredUIAccess
    public UpdateSession updateDirectories(
        @Nonnull FilePath[] filePaths,
        UpdatedFiles updatedFiles,
        ProgressIndicator progressIndicator,
        @Nonnull Ref<SequentialUpdatesContext> sequentialUpdatesContextRef
    ) throws ProcessCanceledException {
        Set<VirtualFile> roots = gitRoots(Arrays.asList(filePaths));
        GitRepositoryManager repositoryManager = getRepositoryManager(myProject);
        GitUpdateProcess gitUpdateProcess = new GitUpdateProcess(
            myProject,
            progressIndicator,
            getRepositoriesFromRoots(repositoryManager, roots),
            updatedFiles,
            true,
            true
        );
        boolean result = gitUpdateProcess.update(mySettings.getUpdateType()).isSuccess();
        return new GitUpdateSession(result);
    }

    @Override
    public boolean validateOptions(Collection<FilePath> filePaths) {
        for (FilePath p : filePaths) {
            if (!isUnderGit(p)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    @Override
    public Configurable createConfigurable(Collection<FilePath> files) {
        return new GitUpdateConfigurable(mySettings);
    }
}
