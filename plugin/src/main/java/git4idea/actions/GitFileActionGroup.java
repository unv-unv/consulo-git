package git4idea.actions;

import consulo.annotation.component.ActionImpl;
import consulo.annotation.component.ActionRef;
import consulo.application.dumb.DumbAware;
import consulo.codeEditor.Editor;
import consulo.git.action.FileActionsGroup;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.virtualFileSystem.VirtualFile;
import consulo.util.collection.JBIterable;
import consulo.git.localize.GitLocalize;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.Presentation;

import jakarta.annotation.Nonnull;

import java.util.Arrays;
import java.util.List;

// from kotlin
@ActionImpl(
    id = "Git.MainMenu.FileActions",
    children = @ActionRef(type = FileActionsGroup.class)
)
public class GitFileActionGroup extends DefaultActionGroup implements DumbAware {
    public GitFileActionGroup() {
        super(GitLocalize.groupMainMenuCurrentFileText(), true);
    }

    @Override
    @RequiredUIAccess
    public void update(@Nonnull AnActionEvent e) {
        Presentation presentation = e.getPresentation();

        VirtualFile[] data = e.getData(VirtualFile.KEY_OF_ARRAY);
        if (data == null) {
            return;
        }

        List<VirtualFile> selection = JBIterable.from(Arrays.asList(data)).take(2).toList();

        if (e.getData(Editor.KEY) == null && !selection.isEmpty()) {
            if (selection.get(0).isDirectory()) {
                presentation.setTextValue(GitLocalize.actionSelectedDirectoryText(selection.size()));
            }
            else {
                presentation.setTextValue(GitLocalize.actionSelectedFileText(selection.size()));
            }
        }
    }
}
