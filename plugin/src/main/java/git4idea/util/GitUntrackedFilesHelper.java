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

import consulo.application.Application;
import consulo.application.ApplicationManager;
import consulo.ide.impl.idea.openapi.vcs.changes.ui.SelectFilesDialog;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.JBLabel;
import consulo.ui.ex.awt.ScrollPaneFactory;
import consulo.ui.ex.awt.VerticalFlowLayout;
import consulo.ui.ex.awt.internal.laf.MultiLineLabelUI;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ref.Ref;
import consulo.util.lang.xml.XmlStringUtil;
import consulo.versionControlSystem.VcsNotifier;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.GitUtil;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class GitUntrackedFilesHelper {

    private GitUntrackedFilesHelper() {
    }

    /**
     * Displays notification about {@code untracked files would be overwritten by checkout} error.
     * Clicking on the link in the notification opens a simple dialog with the list of these files.
     *
     * @param root
     * @param relativePaths
     * @param operation     the name of the Git operation that caused the error: {@code rebase, merge, checkout}.
     * @param description   the content of the notification or null if the default content is to be used.
     */
    public static void notifyUntrackedFilesOverwrittenBy(
        @Nonnull final Project project,
        @Nonnull final VirtualFile root,
        @Nonnull Collection<String> relativePaths,
        @Nonnull final String operation,
        @Nullable String description
    ) {
        final String notificationTitle = StringUtil.capitalize(operation) + " failed";
        final String notificationDesc = description == null ? createUntrackedFilesOverwrittenDescription(operation, true) : description;

        final Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativePaths);
        final List<VirtualFile> untrackedFiles =
            ContainerUtil.mapNotNull(absolutePaths, absolutePath -> GitUtil.findRefreshFileOrLog(absolutePath));

        VcsNotifier.getInstance(project).notifyError(notificationTitle, notificationDesc, new NotificationListener() {
            @Override
            public void hyperlinkUpdate(@Nonnull Notification notification, @Nonnull HyperlinkEvent event) {
                if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    final String dialogDesc = createUntrackedFilesOverwrittenDescription(operation, false);
                    String title = "Untracked Files Preventing " + StringUtil.capitalize(operation);
                    if (untrackedFiles.isEmpty()) {
                        GitUtil.showPathsInDialog(project, absolutePaths, title, dialogDesc);
                    }
                    else {
                        DialogWrapper dialog;
                        dialog = new UntrackedFilesDialog(project, untrackedFiles, dialogDesc);
                        dialog.setTitle(title);
                        dialog.show();
                    }
                }
            }
        });
    }

    @Nonnull
    public static String createUntrackedFilesOverwrittenDescription(@Nonnull final String operation, boolean addLinkToViewFiles) {
        final String description1 = " untracked working tree files would be overwritten by " + operation + ".";
        final String description2 = "Please move or remove them before you can " + operation + ".";
        final String notificationDesc;
        if (addLinkToViewFiles) {
            notificationDesc = "Some" + description1 + "<br/>" + description2 + " <a href='view'>View them</a>";
        }
        else {
            notificationDesc = "These" + description1 + "<br/>" + description2;
        }
        return notificationDesc;
    }

    /**
     * Show dialog for the "Untracked Files Would be Overwritten by checkout/merge/rebase" error,
     * with a proposal to rollback the action (checkout/merge/rebase) in successful repositories.
     * <p>
     * The method receives the relative paths to some untracked files, returned by Git command,
     * and tries to find corresponding VirtualFiles, based on the given root, to display in the standard dialog.
     * If for some reason it doesn't find any VirtualFile, it shows the paths in a simple dialog.
     *
     * @return true if the user agrees to rollback, false if the user decides to keep things as is and simply close the dialog.
     */
    public static boolean showUntrackedFilesDialogWithRollback(
        @Nonnull final Project project,
        @Nonnull final String operationName,
        @Nonnull final String rollbackProposal,
        @Nonnull VirtualFile root,
        @Nonnull final Collection<String> relativePaths
    ) {
        final Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativePaths);
        final List<VirtualFile> untrackedFiles =
            ContainerUtil.mapNotNull(absolutePaths, absolutePath -> GitUtil.findRefreshFileOrLog(absolutePath));

        final Ref<Boolean> rollback = Ref.create();
        ApplicationManager.getApplication().invokeAndWait(new Runnable() {
            @Override
            public void run() {
                JComponent filesBrowser;
                if (untrackedFiles.isEmpty()) {
                    filesBrowser = new GitSimplePathsBrowser(project, absolutePaths);
                }
                else {
                    filesBrowser =
                        ScrollPaneFactory.createScrollPane(new SelectFilesDialog.VirtualFileList(project, untrackedFiles, false, false));
                }
                String title = "Could not " + StringUtil.capitalize(operationName);
                String description = StringUtil.stripHtml(createUntrackedFilesOverwrittenDescription(operationName, false), true);
                DialogWrapper dialog = new UntrackedFilesRollBackDialog(project, filesBrowser, description, rollbackProposal);
                dialog.setTitle(title);
                DialogManager.show(dialog);
                rollback.set(dialog.isOK());
            }
        }, Application.get().getDefaultModalityState());
        return rollback.get();
    }

    private static class UntrackedFilesDialog extends SelectFilesDialog {

        public UntrackedFilesDialog(Project project, Collection<VirtualFile> untrackedFiles, String dialogDesc) {
            super(project, new ArrayList<>(untrackedFiles), StringUtil.stripHtml(dialogDesc, true), null, false, false, true);
            init();
        }

        @Nonnull
        @Override
        protected Action[] createActions() {
            return new Action[]{getOKAction()};
        }

    }

    private static class UntrackedFilesRollBackDialog extends DialogWrapper {

        @Nonnull
        private final JComponent myFilesBrowser;
        @Nonnull
        private final String myPrompt;
        @Nonnull
        private final String myRollbackProposal;

        public UntrackedFilesRollBackDialog(
            @Nonnull Project project,
            @Nonnull JComponent filesBrowser,
            @Nonnull String prompt,
            @Nonnull String rollbackProposal
        ) {
            super(project);
            myFilesBrowser = filesBrowser;
            myPrompt = prompt;
            myRollbackProposal = rollbackProposal;
            setOKButtonText("Rollback");
            setCancelButtonText("Don't rollback");
            init();
        }

        @Override
        protected JComponent createSouthPanel() {
            JComponent buttons = super.createSouthPanel();
            JPanel panel = new JPanel(new VerticalFlowLayout());
            panel.add(new JBLabel(XmlStringUtil.wrapInHtml(myRollbackProposal)));
            if (buttons != null) {
                panel.add(buttons);
            }
            return panel;
        }

        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            return myFilesBrowser;
        }

        @Nullable
        @Override
        protected JComponent createNorthPanel() {
            JLabel label = new JLabel(myPrompt);
            label.setUI(new MultiLineLabelUI());
            label.setBorder(new EmptyBorder(5, 1, 5, 1));
            return label;
        }
    }
}
