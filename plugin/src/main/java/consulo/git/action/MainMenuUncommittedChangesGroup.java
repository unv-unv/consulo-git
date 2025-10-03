package consulo.git.action;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.application.dumb.DumbAware;
import consulo.git.localize.GitLocalize;
import consulo.ui.ex.action.DefaultActionGroup;import git4idea.actions.GitStash;import git4idea.actions.GitUnstash;

/**
 * @author UNV
 * @since 2025-10-03
 */
@ActionImpl(
    id = "Git.MainMenu.LocalChanges",
    children = {
        @ActionRef(id = "ChangesView.Shelve"),
        @ActionRef(id = "Vcs.Show.Shelf"),
        @ActionRef(type = GitStash.class),
        @ActionRef(type = GitUnstash.class),
        @ActionRef(id = "ChangesView.Revert")
        //@ActionRef(id = "Vcs.UmlDiff")
    }
)
public class MainMenuUncommittedChangesGroup extends DefaultActionGroup implements DumbAware {
    public MainMenuUncommittedChangesGroup() {
        super(GitLocalize.groupMainMenuUncommittedChangesText(), true);
    }
}
