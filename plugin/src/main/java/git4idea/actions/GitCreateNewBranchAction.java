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

import consulo.project.Project;
import consulo.versionControlSystem.log.Hash;
import consulo.ide.ServiceManager;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitBrancher;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

public class GitCreateNewBranchAction extends GitLogSingleCommitAction {
    @Override
    protected void actionPerformed(@Nonnull GitRepository repository, @Nonnull Hash commit) {
        Project project = repository.getProject();
        String reference = commit.asString();
        final String name =
            GitBranchUtil.getNewBranchNameFromUser(project, Collections.singleton(repository), "Checkout New Branch From " + reference);
        if (name != null) {
            GitBrancher brancher = ServiceManager.getService(project, GitBrancher.class);
            brancher.checkoutNewBranchStartingFrom(name, reference, Collections.singletonList(repository), null);
        }
    }
}
