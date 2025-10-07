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
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.virtualFileSystem.VirtualFile;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.actions.GitRepositoryAction;import git4idea.commands.GitHandlerUtil;
import git4idea.commands.GitLineHandler;
import git4idea.ui.GitStashDialog;

import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.Set;

/**
 * Git stash action
 */
@ActionImpl(id = "Git.Stash")
public class GitStash extends GitRepositoryAction {
    public GitStash() {
        super(GitLocalize.actionStashText());
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
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        if (changeListManager.isFreezedWithNotification("Can not stash changes now")) {
            return;
        }
        GitStashDialog d = new GitStashDialog(project, gitRoots, defaultRoot);
        d.show();
        if (!d.isOK()) {
            return;
        }
        VirtualFile root = d.getGitRoot();
        affectedRoots.add(root);
        GitLineHandler h = d.handler();
        GitHandlerUtil.doSynchronously(h, GitLocalize.stashingTitle(), LocalizeValue.ofNullable(h.printableCommandLine()));
        VirtualFileUtil.markDirtyAndRefresh(true, true, false, root);
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    protected LocalizeValue getActionName() {
        return GitLocalize.actionStashName();
    }
}
