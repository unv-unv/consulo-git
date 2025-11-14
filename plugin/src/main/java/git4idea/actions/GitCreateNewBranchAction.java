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

import java.util.Collections;

import consulo.annotation.component.ActionImpl;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.log.Hash;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitBrancher;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

@ActionImpl(id = "Git.CreateNewBranch")
public class GitCreateNewBranchAction extends GitLogSingleCommitAction {
    public GitCreateNewBranchAction() {
        getTemplatePresentation().setTextValue(GitLocalize.actionCreateNewBranchText());
        getTemplatePresentation().setDescriptionValue(GitLocalize.actionCreateNewBranchDescription());
    }

    @Override
    @RequiredUIAccess
    protected void actionPerformed(@Nonnull GitRepository repository, @Nonnull Hash commit) {
        Project project = repository.getProject();
        String reference = commit.asString();
        String name = GitBranchUtil.getNewBranchNameFromUser(
            project,
            Collections.singleton(repository),
            GitLocalize.dialogTitleCheckoutNewBranchFrom0(reference)
        );
        if (name != null) {
            GitBrancher brancher = project.getInstance(GitBrancher.class);
            brancher.checkoutNewBranchStartingFrom(name, reference, Collections.singletonList(repository), null);
        }
    }
}
