package git4idea.actions;

import com.intellij.dvcs.repo.Repository;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import git4idea.GitUtil;

import javax.annotation.Nonnull;

// from kotlin
public class GitRepositoryStateActionGroup extends DefaultActionGroup implements DumbAware
{
	public static class Merge extends GitRepositoryStateActionGroup
	{
		public Merge()
		{
			super(Repository.State.MERGING);
		}
	}

	public static class Rebase extends GitRepositoryStateActionGroup
	{
		public Rebase()
		{
			super(Repository.State.REBASING);
		}
	}

	private final Repository.State myState;

	private GitRepositoryStateActionGroup(Repository.State state)
	{
		myState = state;
	}

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

		presentation.setEnabledAndVisible(!GitUtil.getRepositoriesInState(project, myState).isEmpty());
	}
}
