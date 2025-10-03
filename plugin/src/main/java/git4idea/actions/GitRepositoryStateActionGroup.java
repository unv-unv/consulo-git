package git4idea.actions;

import consulo.application.dumb.DumbAware;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.ui.ex.action.Presentation;
import consulo.versionControlSystem.distributed.repository.Repository;
import git4idea.GitUtil;

import jakarta.annotation.Nonnull;

// from kotlin
public class GitRepositoryStateActionGroup extends DefaultActionGroup implements DumbAware {
    public static class Merge extends GitRepositoryStateActionGroup {
        public Merge() {
            super(Repository.State.MERGING);
        }
    }

    private final Repository.State myState;

    protected GitRepositoryStateActionGroup(Repository.State state) {
        myState = state;
    }

    public GitRepositoryStateActionGroup(
        @Nonnull LocalizeValue text,
        boolean popup,
        Repository.State state
    ) {
        super(text, popup);
        myState = state;
    }

    @Override
    @RequiredUIAccess
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
