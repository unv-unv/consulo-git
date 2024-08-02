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

import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.i18n.GitBundle;
import git4idea.ui.GitTagDialog;
import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.Set;

/**
 * Git "tag" action
 */
public class GitTag extends GitRepositoryAction {
  /**
   * {@inheritDoc}
   */
  @Override
  @Nonnull
  protected String getActionName() {
    return GitBundle.message("tag.action.name");
  }

  /**
   * {@inheritDoc}
   */
  protected void perform(@Nonnull final Project project,
                         @Nonnull final List<VirtualFile> gitRoots,
                         @Nonnull final VirtualFile defaultRoot,
                         final Set<VirtualFile> affectedRoots,
                         final List<VcsException> exceptions) throws VcsException {
    GitTagDialog d = new GitTagDialog(project, gitRoots, defaultRoot);
    d.show();
    if (!d.isOK()) {
      return;
    }
    d.runAction(exceptions);
  }

}
