package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.application.dumb.DumbAware;
import consulo.localize.LocalizeValue;
import consulo.ui.ex.action.AnSeparator;
import consulo.ui.ex.action.DefaultActionGroup;
import git4idea.actions.GitAdd;
import git4idea.actions.GitCompareWithBranchAction;
import git4idea.actions.GitResolveConflictsAction;

/**
 * @author UNV
 * @since 2025-11-09
 */
@ActionImpl(
    id = "Git.FileActions",
    children = {
        @ActionRef(id = "CheckinFiles"),
        @ActionRef(type = GitAdd.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(id = "Annotate"),
        @ActionRef(id = "Show.Current.Revision"),
        @ActionRef(id = "Compare.SameVersion"),
        @ActionRef(id = "Compare.LastVersion"),
        @ActionRef(id = "Compare.Selected"),
        @ActionRef(type = GitCompareWithBranchAction.class),
        @ActionRef(id = "Vcs.ShowTabbedFileHistory"),
        @ActionRef(id = "Vcs.ShowHistoryForBlock"),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(id = "ChangesView.Revert"),
        @ActionRef(type = GitResolveConflictsAction.class)
    }
)
public class FileActionsGroup extends DefaultActionGroup implements DumbAware {
    public FileActionsGroup() {
        super(LocalizeValue.empty(), false);
    }
}
