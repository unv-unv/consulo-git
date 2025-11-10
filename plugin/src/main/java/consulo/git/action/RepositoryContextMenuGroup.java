package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.application.dumb.DumbAware;
import consulo.git.localize.GitLocalize;
import consulo.ui.ex.action.DefaultActionGroup;

/**
 * @author UNV
 * @since 2025-11-09
 */
@ActionImpl(
    id = "Git.RepositoryContextMenu",
    children = @ActionRef(type = RepositoryActionsGroup.class)
)
public class RepositoryContextMenuGroup extends DefaultActionGroup implements DumbAware {
    public RepositoryContextMenuGroup() {
        super(GitLocalize.groupRepositoryContextMenuText(), true);
    }
}
