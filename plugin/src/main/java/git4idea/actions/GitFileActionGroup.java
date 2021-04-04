package git4idea.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.JBIterable;
import consulo.git.localize.GitLocalize;
import consulo.ui.annotation.RequiredUIAccess;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

// from kotlin
public class GitFileActionGroup extends DefaultActionGroup implements DumbAware
{
	@RequiredUIAccess
	@Override
	public void update(@Nonnull AnActionEvent e)
	{
		Presentation presentation = e.getPresentation();

		presentation.setTextValue(GitLocalize.groupMainmenuVcsCurrentFileText());

		VirtualFile[] data = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
		if(data == null)
		{
			return;
		}
		
		List<VirtualFile> selection = JBIterable.from(Arrays.asList(data)).take(2).toList();

		if(e.getData(CommonDataKeys.EDITOR) == null && !selection.isEmpty())
		{
			if(selection.get(0).isDirectory())
			{
				presentation.setTextValue(GitLocalize.actionSelectedDirectoryText(selection.size()));
			}
			else
			{
				presentation.setTextValue(GitLocalize.actionSelectedFileText(selection.size()));
			}
		}
	}
}
