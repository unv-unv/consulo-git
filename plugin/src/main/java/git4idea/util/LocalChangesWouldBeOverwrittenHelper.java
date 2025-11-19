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

import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogBuilder;
import consulo.ui.ex.awt.MultiLineLabel;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangesBrowserFactory;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import jakarta.annotation.Nonnull;

import javax.swing.event.HyperlinkEvent;
import java.util.Collection;
import java.util.List;

public class LocalChangesWouldBeOverwrittenHelper {
    @Nonnull
    private static LocalizeValue getErrorNotificationDescription() {
        return getErrorDescription(true);
    }

    @Nonnull
    private static LocalizeValue getErrorDialogDescription() {
        return getErrorDescription(false);
    }

    @Nonnull
    private static LocalizeValue getErrorDescription(boolean forNotification) {
        String line1 = "Your local changes would be overwritten by merge.";
        String line2 = "Commit, stash or revert them to proceed.";
        if (forNotification) {
            return LocalizeValue.localizeTODO(line1 + "<br/>" + line2 + " <a href='view'>View them</a>");
        }
        else {
            return LocalizeValue.localizeTODO(line1 + "\n" + line2);
        }
    }

    public static void showErrorNotification(
        @Nonnull final Project project,
        @Nonnull VirtualFile root,
        @Nonnull final String operationName,
        @Nonnull Collection<String> relativeFilePaths
    ) {
        final Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativeFilePaths);
        final List<Change> changes = GitUtil.findLocalChangesForPaths(project, root, absolutePaths, false);
        NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Git " + StringUtil.capitalize(operationName) + " Failed"))
            .content(getErrorNotificationDescription())
            .hyperlinkListener(new NotificationListener.Adapter() {
                @Override
                @RequiredUIAccess
                protected void hyperlinkActivated(@Nonnull Notification notification, @Nonnull HyperlinkEvent e) {
                    showErrorDialog(project, operationName, changes, absolutePaths);
                }
            })
            .notify(project);
    }

    @RequiredUIAccess
    public static void showErrorDialog(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String operationName,
        @Nonnull Collection<String> relativeFilePaths
    ) {
        Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativeFilePaths);
        List<Change> changes = GitUtil.findLocalChangesForPaths(project, root, absolutePaths, false);
        showErrorDialog(project, operationName, changes, absolutePaths);
    }

    @RequiredUIAccess
    private static void showErrorDialog(
        @Nonnull Project project,
        @Nonnull String operationName,
        @Nonnull List<Change> changes,
        @Nonnull Collection<String> absolutePaths
    ) {
        LocalizeValue title = LocalizeValue.localizeTODO("Local Changes Prevent from " + StringUtil.capitalize(operationName));
        LocalizeValue description = getErrorDialogDescription();
        if (changes.isEmpty()) {
            GitUtil.showPathsInDialog(project, absolutePaths, title, description);
        }
        else {
            DialogBuilder builder = new DialogBuilder(project);
            builder.setNorthPanel(new MultiLineLabel(description.get()));
            ChangesBrowserFactory browserFactory = project.getApplication().getInstance(ChangesBrowserFactory.class);
            builder.setCenterPanel(browserFactory.createChangeBrowserWithRollback(project, changes).getComponent());
            builder.addOkAction();
            builder.setTitle(title);
            builder.show();
        }
    }
}
