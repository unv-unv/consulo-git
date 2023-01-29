/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

import consulo.language.editor.CommonDataKeys;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.action.Presentation;
import consulo.project.Project;
import javax.annotation.Nonnull;

/**
 * Common class for most git actions.
 * @author Kirill Likhodedov
 */
public abstract class GitAction extends DumbAwareAction {

  @Override
  public void update(@Nonnull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    Project project = e.getData(CommonDataKeys.PROJECT);
    if (project == null || project.isDisposed()) {
      presentation.setEnabled(false);
      presentation.setVisible(false);
      return;
    }

    presentation.setEnabled(isEnabled(e));
  }

  /**
   * Checks if this action should be enabled.
   * Called in {@link #update(AnActionEvent)}, so don't execute long tasks here.
   * @return true if the action is enabled.
   */
  protected boolean isEnabled(@Nonnull AnActionEvent event) {
    return true;
  }

}
