package git4idea.actions;

import consulo.application.dumb.DumbAware;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.ui.ex.action.Presentation;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import git4idea.GitVcs;
import jakarta.annotation.Nonnull;

import java.util.Objects;

// from kotlin
public class GitMainMenuActionGroup extends DefaultActionGroup implements DumbAware {
  @RequiredUIAccess
  @Override
  public void update(@Nonnull AnActionEvent e) {
    Presentation presentation = e.getPresentation();

    presentation.setEnabledAndVisible(false);

    Project project = e.getData(Project.KEY);
    if (project == null) {
      return;
    }

    AbstractVcs vcs = ProjectLevelVcsManager.getInstance(project).getSingleVCS();
    if (vcs == null) {
      return;
    }

    presentation.setEnabledAndVisible(Objects.equals(vcs.getKeyInstanceMethod(), GitVcs.getKey()));
  }
}
