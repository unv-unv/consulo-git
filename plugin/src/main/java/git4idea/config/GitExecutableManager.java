// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.config;

import jakarta.inject.Singleton;

import javax.annotation.Nonnull;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.AtomicNotNullLazyValue;

/**
 * Manager for "current git executable".
 * Allows to get a path to git executable.
 */
//TODO: move git version related stuff here
@Singleton
public class GitExecutableManager
{
	public static GitExecutableManager getInstance()
	{
		return ServiceManager.getService(GitExecutableManager.class);
	}

	@Nonnull
	private final GitVcsApplicationSettings myApplicationSettings;
	@Nonnull
	private final AtomicNotNullLazyValue<String> myDetectedExecutable;

	public GitExecutableManager(@Nonnull GitVcsApplicationSettings applicationSettings)
	{
		myApplicationSettings = applicationSettings;
		myDetectedExecutable = AtomicNotNullLazyValue.createValue(new GitExecutableDetector()::detect);
	}

	@Nonnull
	public String getPathToGit()
	{
		String path = myApplicationSettings.getSavedPathToGit();
		return path == null ? getDetectedExecutable() : path;
	}

	@Nonnull
	public String getPathToGit(@Nonnull Project project)
	{
		String path = GitVcsSettings.getInstance(project).getPathToGit();
		return path == null ? getPathToGit() : path;
	}

	@Nonnull
	public String getDetectedExecutable()
	{
		return myDetectedExecutable.getValue();
	}
}
