/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.rebase.GitRebaseDialog;
import git4idea.rebase.GitRebaseUtils;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;

import static consulo.versionControlSystem.distributed.DvcsUtil.sortRepositories;
import static git4idea.GitUtil.*;
import static git4idea.rebase.GitRebaseUtils.getRebasingRepositories;

@ActionImpl(id = "Git.Rebase")
public class GitRebase extends DumbAwareAction {
    public GitRebase() {
        super(GitLocalize.actionRebaseText());
    }

    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        super.update(e);
        Project project = e.getData(Project.KEY);
        if (project == null || !hasGitRepositories(project)) {
            e.getPresentation().setEnabledAndVisible(false);
        }
        else {
            e.getPresentation().setVisible(true);
            e.getPresentation().setEnabled(getRebasingRepositories(project).size() < getRepositories(project).size());
        }
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(@Nonnull AnActionEvent e) {
        final Project project = e.getRequiredData(Project.KEY);
        List<GitRepository> repositories = new ArrayList<>(getRepositories(project));
        repositories.removeAll(getRebasingRepositories(project));
        List<VirtualFile> roots = new ArrayList<>(getRootsFromRepositories(sortRepositories(repositories)));
        VirtualFile defaultRoot = DvcsUtil.guessVcsRoot(project, e.getData(VirtualFile.KEY));
        final GitRebaseDialog dialog = new GitRebaseDialog(project, roots, defaultRoot);
        if (dialog.showAndGet()) {
            ProgressManager.getInstance().run(new Task.Backgroundable(project, GitLocalize.taskRebasingTitle()) {
                @Override
                public void run(@Nonnull ProgressIndicator indicator) {
                    GitRebaseUtils.rebase(project, List.of(dialog.getSelectedRepository()), dialog.getSelectedParams(), indicator);
                }
            });
        }
    }
}
