/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.util;

import consulo.ide.impl.idea.openapi.vcs.changes.ui.FilePathChangesTreeList;
import consulo.project.Project;
import consulo.ui.ex.action.ActionManager;
import consulo.ui.ex.action.ActionPlaces;
import consulo.ui.ex.action.ActionToolbar;
import consulo.ui.ex.action.DefaultActionGroup;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.action.VcsContextFactory;

import jakarta.annotation.Nonnull;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Collection;
import java.util.List;

public class GitSimplePathsBrowser extends JPanel
{
	public GitSimplePathsBrowser(@Nonnull Project project, @Nonnull Collection<String> absolutePaths)
	{
		super(new BorderLayout());

		FilePathChangesTreeList browser = createBrowser(project, absolutePaths);
		ActionToolbar toolbar = createToolbar(browser);

		add(toolbar.getComponent(), BorderLayout.NORTH);
		add(browser);
	}

	@Nonnull
	private static FilePathChangesTreeList createBrowser(@Nonnull Project project, @Nonnull Collection<String> absolutePaths)
	{
		List<FilePath> filePaths = toFilePaths(absolutePaths);
		FilePathChangesTreeList browser = new FilePathChangesTreeList(project, filePaths, false, false, null, null);
		browser.setChangesToDisplay(filePaths);
		return browser;
	}

	@Nonnull
	private static ActionToolbar createToolbar(@Nonnull FilePathChangesTreeList browser)
	{
		DefaultActionGroup actionGroup = new DefaultActionGroup(browser.getTreeActions());
		return ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, actionGroup, true);
	}

	@Nonnull
	private static List<FilePath> toFilePaths(@Nonnull Collection<String> absolutePaths)
	{
		VcsContextFactory vcsContextFactory = VcsContextFactory.getInstance();
		return ContainerUtil.map(absolutePaths, path -> vcsContextFactory.createFilePathOn(new File(path), false));
	}
}
