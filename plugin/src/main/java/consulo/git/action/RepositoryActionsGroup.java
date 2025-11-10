package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.application.dumb.DumbAware;
import consulo.localize.LocalizeValue;
import consulo.ui.ex.action.AnSeparator;
import consulo.ui.ex.action.DefaultActionGroup;
import git4idea.actions.*;
import git4idea.ui.branch.GitBranchesAction;

/**
 * @author UNV
 * @since 2025-11-09
 */
@ActionImpl(
    id = "GitRepositoryActions",
    children = {
        @ActionRef(type = GitBranchesAction.class),
        @ActionRef(type = GitTag.class),
        @ActionRef(type = GitMerge.class),
        @ActionRef(type = GitStash.class),
        @ActionRef(type = GitUnstash.class),
        @ActionRef(type = GitResetHead.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(type = GitFetch.class),
        @ActionRef(type = GitPull.class),
        @ActionRef(id = "Vcs.Push"),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(type = GitRebase.class),
        @ActionRef(type = GitRebaseAbort.class),
        @ActionRef(type = GitRebaseContinue.class),
        @ActionRef(type = GitRebaseSkip.class),
        @ActionRef(type = AnSeparator.class)
    }
)
public class RepositoryActionsGroup extends DefaultActionGroup implements DumbAware {
    public RepositoryActionsGroup() {
        super(LocalizeValue.empty(), false);
    }
}
