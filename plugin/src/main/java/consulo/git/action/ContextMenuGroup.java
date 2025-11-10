package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionParentRef;
import consulo.annotation.component.ActionRef;
import consulo.git.localize.GitLocalize;
import consulo.ui.ex.action.AnSeparator;
import git4idea.actions.GitMenu;

/**
 * @author UNV
 * @since 2025-11-09
 */
@ActionImpl(
    id = "Git.ContextMenu",
    children = {
        @ActionRef(type = FileActionsGroup.class),
        @ActionRef(type = AnSeparator.class),
        @ActionRef(type = RepositoryContextMenuGroup.class)
    },
    parents = @ActionParentRef(@ActionRef(id = "VcsGroup"))
)
public class ContextMenuGroup extends GitMenu {
    public ContextMenuGroup() {
        getTemplatePresentation().setTextValue(GitLocalize.groupContextMenuText());
        setPopup(true);
    }
}
