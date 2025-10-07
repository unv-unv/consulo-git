package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.application.dumb.DumbAware;
import consulo.git.localize.GitLocalize;
import consulo.ui.ex.action.DefaultActionGroup;

/**
 * @author UNV
 * @since 2025-10-03
 */
@ActionImpl(
    id = "Patch.MainMenu",
    children = {
        @ActionRef(id = "ChangesView.CreatePatch"),
        @ActionRef(id = "ChangesView.ApplyPatch"),
        @ActionRef(id = "ChangesView.ApplyPatchFromClipboard")
    }
)
public class MainMenuPatchGroup extends DefaultActionGroup implements DumbAware {
    public MainMenuPatchGroup() {
        super(GitLocalize.groupMainMenuPatchText(), true);
    }
}
