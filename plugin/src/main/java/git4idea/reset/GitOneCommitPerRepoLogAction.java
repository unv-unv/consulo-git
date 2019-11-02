package git4idea.reset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.intellij.dvcs.repo.AbstractRepositoryManager;
import com.intellij.dvcs.ui.VcsLogOneCommitPerRepoAction;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

public abstract class GitOneCommitPerRepoLogAction extends VcsLogOneCommitPerRepoAction<GitRepository>
{

	@Nonnull
	@Override
	protected AbstractRepositoryManager<GitRepository> getRepositoryManager(@Nonnull Project project)
	{
		return ServiceManager.getService(project, GitRepositoryManager.class);
	}

	@Nullable
	@Override
	protected GitRepository getRepositoryForRoot(@Nonnull Project project, @Nonnull VirtualFile root)
	{
		return getRepositoryManager(project).getRepositoryForRoot(root);
	}
}
