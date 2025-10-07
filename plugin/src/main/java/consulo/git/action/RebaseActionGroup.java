package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.git.localize.GitLocalize;
import consulo.versionControlSystem.distributed.repository.Repository;
import git4idea.actions.GitRebaseAbort;
import git4idea.actions.GitRebaseContinue;
import git4idea.actions.GitRebaseSkip;
import git4idea.actions.GitRepositoryStateActionGroup;

/**
 * @author UNV
 * @since 2025-10-03
 */
@ActionImpl(
    id = "Git.MainMenu.RebaseActions",
    children = {
        @ActionRef(type = GitRebaseAbort.class),
        @ActionRef(type = GitRebaseContinue.class),
        @ActionRef(type = GitRebaseSkip.class)
    }
)
public class RebaseActionGroup extends GitRepositoryStateActionGroup {
    public RebaseActionGroup() {
        super(GitLocalize.groupMainMenuRebaseActionsText(), true, Repository.State.REBASING);
    }
}
