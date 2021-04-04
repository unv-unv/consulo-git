package git4idea.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import consulo.ui.annotation.RequiredUIAccess;
import git4idea.GitVcs;

import javax.annotation.Nonnull;
import java.util.Objects;

// from kotlin
public class GitMainMenuActionGroup extends DefaultActionGroup implements DumbAware
{
	@RequiredUIAccess
	@Override
	public void update(@Nonnull AnActionEvent e)
	{
		Presentation presentation = e.getPresentation();

		presentation.setEnabledAndVisible(false);

		Project project = e.getProject();
		if(project == null)
		{
			return;
		}

		AbstractVcs vcs = ProjectLevelVcsManager.getInstance(project).getSingleVCS();
		if(vcs == null)
		{
			return;
		}

		presentation.setEnabledAndVisible(Objects.equals(vcs.getKeyInstanceMethod(), GitVcs.getKey()));
	}
}
