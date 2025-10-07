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
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionPlaces;
import consulo.ui.ex.action.AnActionEvent;
import consulo.versionControlSystem.AbstractVcsHelper;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.actions.GitAction;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

import java.util.*;

/**
 * Git merge tool for resolving conflicts. Use IDEA built-in 3-way merge tool.
 */
@ActionImpl(id = "Git.ResolveConflicts")
public class GitResolveConflictsAction extends GitAction {
    public GitResolveConflictsAction() {
        super(GitLocalize.actionResolveConflictsText());
    }

    @Override
    @RequiredUIAccess
    public void actionPerformed(@Nonnull AnActionEvent event) {
        Project project = event.getData(Project.KEY);
        if (project == null) {
            return;
        }

        Set<VirtualFile> conflictedFiles =
            new TreeSet<>((Comparator<VirtualFile>) (f1, f2) -> f1.getPresentableUrl().compareTo(f2.getPresentableUrl()));
        for (Change change : ChangeListManager.getInstance(project).getAllChanges()) {
            if (change.getFileStatus() != FileStatus.MERGED_WITH_CONFLICTS) {
                continue;
            }
            ContentRevision before = change.getBeforeRevision();
            ContentRevision after = change.getAfterRevision();
            if (before != null) {
                VirtualFile file = before.getFile().getVirtualFile();
                if (file != null) {
                    conflictedFiles.add(file);
                }
            }
            if (after != null) {
                VirtualFile file = after.getFile().getVirtualFile();
                if (file != null) {
                    conflictedFiles.add(file);
                }
            }
        }

        AbstractVcsHelper.getInstance(project)
            .showMergeDialog(new ArrayList<>(conflictedFiles), GitVcs.getInstance(project).getMergeProvider());
        for (GitRepository repository : GitUtil.getRepositoriesForFiles(project, conflictedFiles)) {
            repository.update();
        }
    }

    @Override
    protected boolean isEnabled(@Nonnull AnActionEvent event) {
        Collection<Change> changes = ChangeListManager.getInstance(event.getRequiredData(Project.KEY)).getAllChanges();
        if (changes.size() > 1000) {
            return true;
        }
        for (Change change : changes) {
            if (change.getFileStatus() == FileStatus.MERGED_WITH_CONFLICTS) {
                return true;
            }
        }
        return false;
    }

    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        super.update(e);
        if (ActionPlaces.isPopupPlace(e.getPlace())) {
            e.getPresentation().setVisible(e.getPresentation().isEnabled());
        }
    }
}
