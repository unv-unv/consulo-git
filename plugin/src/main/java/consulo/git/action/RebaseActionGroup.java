package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.git.localize.GitLocalize;
import consulo.versionControlSystem.distributed.repository.Repository;
import git4idea.actions.GitRepositoryStateActionGroup;

/**
 * @author UNV
 * @since 2025-10-03
 */
@ActionImpl(
    id = "Git.MainMenu.RebaseActions",
    children = {
        @ActionRef(type = RebaseAbort.class),
        @ActionRef(type = RebaseContinueAction.class),
        @ActionRef(type = RebaseSkipAction.class)
    }
)
public class RebaseActionGroup extends GitRepositoryStateActionGroup {
    public RebaseActionGroup() {
        super(GitLocalize.groupMainMenuRebaseActionsText(), true, Repository.State.REBASING);
    }
}
