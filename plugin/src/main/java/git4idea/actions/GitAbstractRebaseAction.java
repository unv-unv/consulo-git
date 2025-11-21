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
package git4idea.actions;

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.util.collection.ContainerUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.rebase.GitRebaseActionDialog;
import git4idea.rebase.GitRebaseUtils;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static consulo.util.lang.ObjectUtil.assertNotNull;
import static git4idea.GitUtil.*;

public abstract class GitAbstractRebaseAction extends DumbAwareAction {
    protected GitAbstractRebaseAction(@Nonnull LocalizeValue text) {
        super(text);
    }

    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        super.update(e);
        Project project = e.getData(Project.KEY);
        e.getPresentation().setEnabledAndVisible(project != null && hasGitRepositories(project) && hasRebaseInProgress(project));
    }

    @Override
    @RequiredUIAccess
    public final void actionPerformed(AnActionEvent e) {
        final Project project = e.getRequiredData(Project.KEY);
        ProgressManager progressManager = ProgressManager.getInstance();
        LocalizeValue progressTitle = getProgressTitle();
        if (getRepositoryManager(project).hasOngoingRebase()) {
            progressManager.run(new Task.Backgroundable(project, progressTitle) {
                @Override
                public void run(@Nonnull ProgressIndicator indicator) {
                    performActionForProject(project, indicator);
                }
            });
        }
        else {
            final GitRepository repositoryToOperate = chooseRepository(project, GitRebaseUtils.getRebasingRepositories(project));
            if (repositoryToOperate != null) {
                progressManager.run(new Task.Backgroundable(project, progressTitle) {
                    @Override
                    public void run(@Nonnull ProgressIndicator indicator) {
                        performActionForRepository(project, repositoryToOperate, indicator);
                    }
                });
            }
        }
    }

    @Nonnull
    protected abstract LocalizeValue getProgressTitle();

    protected abstract void performActionForProject(@Nonnull Project project, @Nonnull ProgressIndicator indicator);

    protected abstract void performActionForRepository(
        @Nonnull Project project,
        @Nonnull GitRepository repository,
        @Nonnull ProgressIndicator indicator
    );

    private static boolean hasRebaseInProgress(@Nonnull Project project) {
        return !GitRebaseUtils.getRebasingRepositories(project).isEmpty();
    }

    @Nullable
    @RequiredUIAccess
    private GitRepository chooseRepository(@Nonnull Project project, @Nonnull Collection<GitRepository> repositories) {
        GitRepository firstRepo = assertNotNull(ContainerUtil.getFirstItem(repositories));
        if (repositories.size() == 1) {
            return firstRepo;
        }
        List<VirtualFile> roots = new ArrayList<>(getRootsFromRepositories(repositories));
        GitRebaseActionDialog dialog =
            new GitRebaseActionDialog(project, getTemplatePresentation().getTextValue(), roots, firstRepo.getRoot());
        dialog.show();
        VirtualFile root = dialog.selectRoot();
        if (root == null) {
            return null;
        }
        return getRepositoryManager(project).getRepositoryForRootQuick(root); // TODO avoid root <-> GitRepository double conversion
    }
}
