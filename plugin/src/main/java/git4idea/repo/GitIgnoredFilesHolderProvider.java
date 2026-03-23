// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
package git4idea.repo;

import consulo.annotation.component.ExtensionImpl;
import consulo.project.Project;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.change.VcsManagedFilesHolder;
import consulo.versionControlSystem.change.VcsManagedIgnoredFilesHolderProvider;
import git4idea.GitVcs;
import jakarta.inject.Inject;

/**
 * Provider for {@link GitIgnoredFilesHolder}.
 * Matches the role of {@code GitIgnoredFilesHolder.Provider} from JetBrains' {@code GitIgnoredFilesHolder.kt}.
 */
@ExtensionImpl
public class GitIgnoredFilesHolderProvider implements VcsManagedIgnoredFilesHolderProvider {
  private final Project myProject;
  private final GitRepositoryManager myManager;

  @Inject
  public GitIgnoredFilesHolderProvider(Project project) {
    myProject = project;
    myManager = GitRepositoryManager.getInstance(project);
  }

  @Override
  public AbstractVcs getVcs() {
    return GitVcs.getInstance(myProject);
  }

  @Override
  public VcsManagedFilesHolder createHolder() {
    return new GitIgnoredFilesHolder(myManager);
  }
}
