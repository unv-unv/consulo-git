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
package git4idea.ui.branch;

import consulo.annotation.component.ActionImpl;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

/**
 * Invokes a {@link GitBranchPopup} to checkout and control Git branches.
 */
@ActionImpl(id = "Git.Branches")
public class GitBranchesAction extends DumbAwareAction {
    public GitBranchesAction() {
        super(GitLocalize.actionBranchesText(), LocalizeValue.empty(), PlatformIconGroup.vcsBranch());
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(@Nonnull AnActionEvent e) {
        Project project = e.getRequiredData(Project.KEY);
        VirtualFile file = e.getData(VirtualFile.KEY);
        GitRepository repository = file == null
            ? GitBranchUtil.getCurrentRepository(project)
            : GitBranchUtil.getRepositoryOrGuess(project, file);
        if (repository != null) {
            GitBranchPopup.getInstance(project, repository).asListPopup().showCenteredInCurrentWindow(project);
        }
    }

    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        Project project = e.getData(Project.KEY);
        e.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed());
    }
}
