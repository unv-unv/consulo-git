package git4idea.actions;

import consulo.application.dumb.DumbAware;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.ui.ex.action.Presentation;
import consulo.versionControlSystem.distributed.repository.Repository;
import git4idea.GitUtil;

import javax.annotation.Nonnull;

// from kotlin
public class GitRepositoryStateActionGroup extends DefaultActionGroup implements DumbAware {
  public static class Merge extends GitRepositoryStateActionGroup {
    public Merge() {
      super(Repository.State.MERGING);
    }
  }

  public static class Rebase extends GitRepositoryStateActionGroup {
    public Rebase() {
      super(Repository.State.REBASING);
    }
  }

  private final Repository.State myState;

  private GitRepositoryStateActionGroup(Repository.State state) {
    myState = state;
  }

  @RequiredUIAccess
  @Override
  public void update(@Nonnull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    presentation.setEnabledAndVisible(false);

    Project project = e.getData(Project.KEY);
    if (project == null) {
      return;
    }

    presentation.setEnabledAndVisible(!GitUtil.getRepositoriesInState(project, myState).isEmpty());
  }
}
