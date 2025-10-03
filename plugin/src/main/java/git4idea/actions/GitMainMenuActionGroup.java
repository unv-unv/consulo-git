package git4idea.actions;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionParentRef;
import consulo.annotation.component.ActionRef;
import consulo.annotation.component.ActionRefAnchor;
import consulo.application.dumb.DumbAware;
import consulo.git.action.*;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.*;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import git4idea.GitVcs;
import git4idea.ui.branch.BranchesAction;
import jakarta.annotation.Nonnull;

import java.util.Objects;

// from kotlin
@ActionImpl(
    id = "Git.MainMenu",
    children = {
        @ActionRef(id = IdeActions.ACTION_CHECKIN_PROJECT),
        @ActionRef(id = "Vcs.Push"),
        @ActionRef(id = "Vcs.UpdateProject"),
        @ActionRef(type = PullAction.class),
        @ActionRef(type = FetchAction.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(type = MergeAction.class),
        //@ActionRef(id = "Git.MainMenu.MergeActions"),
        @ActionRef(type = RebaseAction.class),
        @ActionRef(type = RebaseActionGroup.class),
        @ActionRef(type = ResolveConflictsAction.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(type = BranchesAction.class),
        @ActionRef(type = NewBranchAtCommitAction.class),
        @ActionRef(type = NewTagAction.class),
        @ActionRef(type = ResetHeadAction.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(id = "Vcs.Show.Log"),
        @ActionRef(type = MainMenuPatchGroup.class),
        @ActionRef(type = MainMenuUncommittedChangesGroup.class),
        @ActionRef(id = "Git.MainMenu.FileActions"),
        @ActionRef(type = AnSeparator.class),
        //@ActionRef(type = GitConfigureRemotesAction.class),
        //@ActionRef(type = GitCloneAction.class),
        //@ActionRef(type = AnSeparator.class),
        @ActionRef(id = "Vcs.QuickListPopupAction")
        //@ActionRef(type = AnSeparator.class),
        //@ActionRef(type = GitAbortOperationAction$Revert.class),
        //@ActionRef(type = GitAbortOperationAction$CherryPick.class)
    },
    parents = @ActionParentRef(
        value = @ActionRef(id = "VcsGroups"),
        anchor = ActionRefAnchor.AFTER,
        relatedToAction = @ActionRef(id = "Vcs.MainMenu")
    )
)
public class GitMainMenuActionGroup extends DefaultActionGroup implements DumbAware {
    @Override
    @RequiredUIAccess
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
