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
package git4idea.actions;

import consulo.annotation.component.ActionImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import consulo.project.Project;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.actions.GitRepositoryAction;
import git4idea.repo.GitRepositoryManager;
import git4idea.update.GitFetcher;
import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.Set;

/**
 * Git "fetch" action
 */
@ActionImpl(id = "Git.Fetch")
public class GitFetch extends GitRepositoryAction {
    public GitFetch() {
        super(GitLocalize.actionFetchText());
    }

    @Override
    @Nonnull
    protected LocalizeValue getActionName() {
        return GitLocalize.fetchActionName();
    }

    @Override
    protected void perform(
        @Nonnull final Project project,
        @Nonnull final List<VirtualFile> gitRoots,
        @Nonnull VirtualFile defaultRoot,
        Set<VirtualFile> affectedRoots,
        List<VcsException> exceptions
    ) throws VcsException {
        GitVcs.runInBackground(new Task.Backgroundable(project, LocalizeValue.localizeTODO("Fetching..."), false) {
            @Override
            @RequiredUIAccess
            public void run(@Nonnull ProgressIndicator indicator) {
                GitRepositoryManager repositoryManager = GitUtil.getRepositoryManager(project);
                new GitFetcher(project, indicator, true)
                    .fetchRootsAndNotify(GitUtil.getRepositoriesFromRoots(repositoryManager, gitRoots), null, true);
            }
        });
    }

    @Override
    protected boolean executeFinalTasksSynchronously() {
        return false;
    }
}
