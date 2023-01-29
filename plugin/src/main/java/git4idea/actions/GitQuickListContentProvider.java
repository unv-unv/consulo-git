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

import consulo.annotation.component.ExtensionImpl;
import consulo.dataContext.DataContext;
import consulo.project.Project;
import consulo.ui.ex.action.ActionManager;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnSeparator;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.action.VcsQuickListContentProvider;
import git4idea.GitVcs;
import git4idea.i18n.GitBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Roman.Chernyatchik
 */
@ExtensionImpl
public class GitQuickListContentProvider implements VcsQuickListContentProvider {
  public List<AnAction> getVcsActions(@Nullable Project project, @Nullable AbstractVcs activeVcs,
                                      @Nullable DataContext dataContext) {

    if (activeVcs == null || !GitVcs.NAME.equals(activeVcs.getName())) {
      return null;
    }

    final ActionManager manager = ActionManager.getInstance();
    final List<AnAction> actions = new ArrayList<AnAction>();

    actions.add(new AnSeparator(activeVcs.getDisplayName()));
    add("CheckinProject", manager, actions);
    add("CheckinFiles", manager, actions);
    add("ChangesView.Revert", manager, actions);

    addSeparator(actions);
    add("Vcs.ShowTabbedFileHistory", manager, actions);
    add("Annotate", manager, actions);
    add("Compare.SameVersion", manager, actions);

    addSeparator(actions);
    add("Git.Branches", manager, actions);
    add("Vcs.Push", manager, actions);
    add("Git.Stash", manager, actions);
    add("Git.Unstash", manager, actions);

    add("ChangesView.AddUnversioned", manager, actions);
    add("Git.ResolveConflicts", manager, actions);

    // Github
    addSeparator(actions);
    final AnAction githubRebase = manager.getAction("Github.Rebase");
    if (githubRebase != null) {
      actions.add(new AnSeparator(GitBundle.message("vcs.popup.git.github.section")));
      actions.add(githubRebase);
    }

    return actions;
  }

  public List<AnAction> getNotInVcsActions(@Nullable Project project, @Nullable DataContext dataContext) {
    final AnAction action = ActionManager.getInstance().getAction("Git.Init");
    return Collections.singletonList(action);
  }

  public boolean replaceVcsActionsFor(@Nonnull AbstractVcs activeVcs, @Nullable DataContext dataContext) {
    if (!GitVcs.NAME.equals(activeVcs.getName())) {
      return false;
    }
    return true;
  }

  private static void addSeparator(@Nonnull final List<AnAction> actions) {
    actions.add(new AnSeparator());
  }

  private static void add(String actionName, ActionManager manager, List<AnAction> actions) {
    final AnAction action = manager.getAction(actionName);
    assert action != null;
    actions.add(action);
  }
}
