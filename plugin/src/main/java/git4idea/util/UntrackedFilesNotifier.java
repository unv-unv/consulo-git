/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

import consulo.ide.impl.idea.openapi.vcs.changes.ui.SelectFilesDialog;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsNotifier;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class UntrackedFilesNotifier
{
	private UntrackedFilesNotifier()
	{
	}

	/**
	 * Displays notification about {@code untracked files would be overwritten by checkout} error.
	 * Clicking on the link in the notification opens a simple dialog with the list of these files.
	 *
	 * @param root
	 * @param relativePaths
	 * @param operation     the name of the Git operation that caused the error: {@code rebase, merge, checkout}.
	 * @param description   the content of the notification or null if the deafult content is to be used.
	 */
	public static void notifyUntrackedFilesOverwrittenBy(@Nonnull final Project project,
			@Nonnull final VirtualFile root,
			@Nonnull Collection<String> relativePaths,
			@Nonnull final String operation,
			@Nullable String description)
	{
		final String notificationTitle = StringUtil.capitalize(operation) + " failed";
		final String notificationDesc = description == null ? createUntrackedFilesOverwrittenDescription(operation, true) : description;

		final Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativePaths);
		final List<VirtualFile> untrackedFiles = ContainerUtil.mapNotNull(absolutePaths, absolutePath -> GitUtil.findRefreshFileOrLog(absolutePath));

		VcsNotifier.getInstance(project).notifyError(notificationTitle, notificationDesc, new NotificationListener()
		{
			@Override
			public void hyperlinkUpdate(@Nonnull Notification notification, @Nonnull HyperlinkEvent event)
			{
				if(event.getEventType() == HyperlinkEvent.EventType.ACTIVATED)
				{
					final String dialogDesc = createUntrackedFilesOverwrittenDescription(operation, false);
					String title = "Untracked Files Preventing " + StringUtil.capitalize(operation);
					if(untrackedFiles.isEmpty())
					{
						GitUtil.showPathsInDialog(project, absolutePaths, title, dialogDesc);
					}
					else
					{
						DialogWrapper dialog;
						dialog = new UntrackedFilesDialog(project, untrackedFiles, dialogDesc);
						dialog.setTitle(title);
						dialog.show();
					}
				}
			}
		});
	}

	public static String createUntrackedFilesOverwrittenDescription(@Nonnull final String operation, boolean addLinkToViewFiles)
	{
		final String description1 = " untracked working tree files would be overwritten by " + operation + ".";
		final String description2 = "Please move or remove them before you can " + operation + ".";
		final String notificationDesc;
		if(addLinkToViewFiles)
		{
			notificationDesc = "Some" + description1 + "<br/>" + description2 + " <a href='view'>View them</a>";
		}
		else
		{
			notificationDesc = "These" + description1 + "<br/>" + description2;
		}
		return notificationDesc;
	}

	private static class UntrackedFilesDialog extends SelectFilesDialog
	{

		public UntrackedFilesDialog(Project project, Collection<VirtualFile> untrackedFiles, String dialogDesc)
		{
			super(project, new ArrayList<VirtualFile>(untrackedFiles), StringUtil.stripHtml(dialogDesc, true), null, false, false, true);
			init();
		}

		@Nonnull
		@Override
		protected Action[] createActions()
		{
			return new Action[]{getOKAction()};
		}

	}
}
