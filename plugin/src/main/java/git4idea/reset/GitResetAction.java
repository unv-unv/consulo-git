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
package git4idea.reset;

import consulo.annotation.component.ActionImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.ObjectUtil;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.VcsFullCommitDetails;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@ActionImpl(id = "Git.Reset.In.Log")
public class GitResetAction extends GitOneCommitPerRepoLogAction {
    public GitResetAction() {
        getTemplatePresentation().setTextValue(GitLocalize.actionLogResetText());
    }

    @Override
    @RequiredUIAccess
    protected void actionPerformed(@Nonnull final Project project, @Nonnull final Map<GitRepository, VcsFullCommitDetails> commits) {
        GitVcsSettings settings = GitVcsSettings.getInstance(project);
        GitResetMode defaultMode = ObjectUtil.notNull(settings.getResetMode(), GitResetMode.getDefault());
        GitNewResetDialog dialog = new GitNewResetDialog(project, commits, defaultMode);
        if (dialog.showAndGet()) {
            final GitResetMode selectedMode = dialog.getResetMode();
            settings.setResetMode(selectedMode);
            new Task.Backgroundable(project, GitLocalize.dialogTitleReset(), true) {
                @Override
                @RequiredUIAccess
                public void run(@Nonnull ProgressIndicator indicator) {
                    Map<GitRepository, Hash> hashes = commits.keySet().stream()
                        .collect(Collectors.toMap(Function.identity(), repo -> commits.get(repo).getId()));
                    new GitResetOperation(project, hashes, selectedMode, indicator).execute();
                }
            }.queue();
        }
    }
}
