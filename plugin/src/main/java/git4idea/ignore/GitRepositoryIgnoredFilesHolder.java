// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ignore;

import consulo.versionControlSystem.FilePath;

import java.util.Collection;
import java.util.Set;

public abstract class GitRepositoryIgnoredFilesHolder {
  public abstract Set<FilePath> getIgnoredFilePaths();

  public abstract boolean isInitialized();

  public abstract boolean isInUpdateMode();

  public abstract boolean containsFile(FilePath file);

  public abstract void removeIgnoredFiles(Collection<FilePath> filePaths);
}
