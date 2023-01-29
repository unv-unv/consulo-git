// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.config;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.util.AtomicNotNullLazyValue;
import consulo.ide.ServiceManager;
import consulo.project.Project;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.annotation.Nonnull;

/**
 * Manager for "current git executable".
 * Allows to get a path to git executable.
 */
//TODO: move git version related stuff here
@Singleton
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class GitExecutableManager {
  public static GitExecutableManager getInstance() {
    return ServiceManager.getService(GitExecutableManager.class);
  }

  @Nonnull
  private final GitVcsApplicationSettings myApplicationSettings;
  @Nonnull
  private final AtomicNotNullLazyValue<String> myDetectedExecutable;

  @Inject
  public GitExecutableManager(@Nonnull GitVcsApplicationSettings applicationSettings) {
    myApplicationSettings = applicationSettings;
    myDetectedExecutable = AtomicNotNullLazyValue.createValue(new GitExecutableDetector()::detect);
  }

  @Nonnull
  public String getPathToGit() {
    String path = myApplicationSettings.getSavedPathToGit();
    return path == null ? getDetectedExecutable() : path;
  }

  @Nonnull
  public String getPathToGit(@Nonnull Project project) {
    String path = GitVcsSettings.getInstance(project).getPathToGit();
    return path == null ? getPathToGit() : path;
  }

  @Nonnull
  public String getDetectedExecutable() {
    return myDetectedExecutable.getValue();
  }
}
