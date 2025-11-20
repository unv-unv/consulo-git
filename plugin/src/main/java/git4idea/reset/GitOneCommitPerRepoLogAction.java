package git4idea.reset;

import consulo.project.Project;
import consulo.versionControlSystem.distributed.action.VcsLogOneCommitPerRepoAction;
import consulo.versionControlSystem.distributed.repository.AbstractRepositoryManager;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public abstract class GitOneCommitPerRepoLogAction extends VcsLogOneCommitPerRepoAction<GitRepository> {
    @Nonnull
    @Override
    protected AbstractRepositoryManager<GitRepository> getRepositoryManager(@Nonnull Project project) {
        return GitRepositoryManager.getInstance(project);
    }

    @Nullable
    @Override
    protected GitRepository getRepositoryForRoot(@Nonnull Project project, @Nonnull VirtualFile root) {
        return getRepositoryManager(project).getRepositoryForRoot(root);
    }
}
