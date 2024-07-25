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

import consulo.project.Project;
import consulo.project.ui.wm.StatusBarWidget;
import consulo.project.ui.wm.StatusBarWidgetFactory;
import consulo.ui.ex.popup.ListPopup;
import consulo.util.lang.ObjectUtil;
import consulo.versionControlSystem.distributed.ui.DvcsStatusWidget;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchUtil;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Status bar widget which displays the current branch for the file currently open in the editor.
 */
public class GitBranchWidget extends DvcsStatusWidget<GitRepository> {
  private final GitVcsSettings mySettings;

  public GitBranchWidget(@Nonnull Project project, @Nonnull StatusBarWidgetFactory factory) {
    super(project, factory, GitVcs.NAME);
    mySettings = GitVcsSettings.getInstance(project);
    project.getMessageBus().connect().subscribe(GitRepositoryChangeListener.class, repository -> updateLater());
  }

  @Override
  public StatusBarWidget copy() {
    return new GitBranchWidget(ObjectUtil.assertNotNull(getProject()), myFactory);
  }

  @Nullable
  @Override
  protected GitRepository guessCurrentRepository(@Nonnull Project project)  {
    return GitBranchUtil.getCurrentRepository(project);
  }

  @Nonnull
  @Override
  protected String getFullBranchName(@Nonnull GitRepository repository) {
    return GitBranchUtil.getDisplayableBranchText(repository);
  }

  @Override
  protected boolean isMultiRoot(@Nonnull Project project) {
    return !GitUtil.justOneGitRepository(project);
  }

  @Nonnull
  @Override
  protected ListPopup getPopup(@Nonnull Project project, @Nonnull GitRepository repository) {
    return GitBranchPopup.getInstance(project, repository).asListPopup();
  }

  @Override
  protected void rememberRecentRoot(@Nonnull String path) {
    mySettings.setRecentRoot(path);
  }
}
