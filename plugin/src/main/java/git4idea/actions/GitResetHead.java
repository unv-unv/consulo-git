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

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitHandlerUtil;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepositoryManager;
import git4idea.ui.GitResetDialog;
import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.Set;

/**
 * The reset action
 */
public class GitResetHead extends GitRepositoryAction {
    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    protected LocalizeValue getActionName() {
        return GitLocalize.resetActionName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiredUIAccess
    protected void perform(
        @Nonnull Project project,
        @Nonnull List<VirtualFile> gitRoots,
        @Nonnull VirtualFile defaultRoot,
        Set<VirtualFile> affectedRoots,
        List<VcsException> exceptions
    ) throws VcsException {
        GitResetDialog d = new GitResetDialog(project, gitRoots, defaultRoot);
        d.show();
        if (!d.isOK()) {
            return;
        }
        GitLineHandler h = d.handler();
        affectedRoots.add(d.getGitRoot());
        GitHandlerUtil.doSynchronously(h, GitLocalize.resettingTitle(), LocalizeValue.ofNullable(h.printableCommandLine()));
        GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
        manager.updateRepository(d.getGitRoot());
    }
}
