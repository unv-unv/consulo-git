// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
package git4idea.repo;

import consulo.logging.Logger;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.change.FileHolder;
import consulo.versionControlSystem.change.VcsManagedFilesHolder;
import consulo.versionControlSystem.change.VcsModifiableDirtyScope;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.ignore.GitRepositoryIgnoredFilesHolder;
import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * VCS-level ignored files holder for Git. One instance serves all git roots in the project.
 * <p>
 * Delegates to each {@link GitRepository}'s per-repository {@link GitRepositoryIgnoredFilesHolder},
 * which is populated in background by {@link GitUntrackedFilesHolder}.
 * <p>
 * Matches the structure of JetBrains' {@code GitIgnoredFilesHolder.kt} (copyright 2000-2018).
 */
public class GitIgnoredFilesHolder implements VcsManagedFilesHolder {
  private static final Logger LOG = Logger.getInstance(GitIgnoredFilesHolder.class);

  private final GitRepositoryManager myManager;

  public GitIgnoredFilesHolder(@Nonnull GitRepositoryManager manager) {
    myManager = manager;
  }

  @Override
  public boolean isInUpdatingMode() {
    return myManager.getRepositories().stream()
      .map(GitRepository::getIgnoredFilesHolder)
      .anyMatch(GitRepositoryIgnoredFilesHolder::isInUpdateMode);
  }

  @Override
  public boolean containsFile(@Nonnull FilePath filePath, @Nonnull VirtualFile vcsRoot) {
    GitRepository repository = myManager.getRepositoryForRootQuick(vcsRoot);
    if (repository == null) return false;
    return repository.getIgnoredFilesHolder().containsFile(filePath);
  }

  @Override
  public @Nonnull Collection<FilePath> values() {
    List<FilePath> result = new ArrayList<>();
    for (GitRepository repo : myManager.getRepositories()) {
      result.addAll(repo.getIgnoredFilesHolder().getIgnoredFilePaths());
    }
    return result;
  }

  // --- VcsManagedFilesHolderBase equivalent ---
  // cleanAll and cleanAndAdjustScope are no-ops: per-repository holders manage their own lifecycle

  @Override
  public void cleanAll() {
    // no-op: per-repository holders manage their own lifecycle
  }

  @Override
  public void cleanAndAdjustScope(@Nonnull VcsModifiableDirtyScope scope) {
    // no-op: per-repository holders manage their own lifecycle
  }

  @Override
  public void addFile(@Nonnull FilePath file) {
    // Attempt to populate vcs-managed files holder externally is not supported
    LOG.warn("Attempt to populate GitIgnoredFilesHolder with " + file, new Throwable());
  }

  @Override
  public @Nonnull FileHolder copy() {
    return this; // holder delegates to per-repository state; immutable wrapper
  }

  @Override
  public HolderType getType() {
    return HolderType.IGNORED;
  }
}
