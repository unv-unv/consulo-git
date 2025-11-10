package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionParentRef;
import consulo.annotation.component.ActionRef;
import consulo.annotation.component.ActionRefAnchor;
import consulo.git.localize.GitLocalize;
import consulo.ui.ex.action.AnSeparator;
import git4idea.actions.GitFileActionGroup;
import git4idea.actions.GitMenu;

/**
 * @author UNV
 * @since 2025-11-09
 */
@ActionImpl(
    id = "Git.Menu",
    children = {
        @ActionRef(type = GitFileActionGroup.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(type = RepositoryActionsGroup.class)
    },
    parents = @ActionParentRef(
        value = @ActionRef(id = "VcsGlobalGroup"),
        anchor = ActionRefAnchor.AFTER,
        relatedToAction = @ActionRef(id = "Vcs.Specific")
    )
)
public class VcsMenuGroup extends GitMenu {
    public VcsMenuGroup() {
        getTemplatePresentation().setTextValue(GitLocalize.groupVcsMenuText());
        setPopup(true);
    }
}
