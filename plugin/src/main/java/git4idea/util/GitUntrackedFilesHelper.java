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
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.JBLabel;
import consulo.ui.ex.awt.ScrollPaneFactory;
import consulo.ui.ex.awt.VerticalFlowLayout;
import consulo.ui.ex.awt.internal.laf.MultiLineLabelUI;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ref.SimpleReference;
import consulo.util.lang.xml.XmlStringUtil;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.ui.awt.LegacyComponentFactory;
import consulo.versionControlSystem.ui.awt.LegacyDialog;
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
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> relativePaths,
        @Nonnull LocalizeValue operation,
        @Nonnull LocalizeValue description
    ) {
        Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativePaths);
        List<VirtualFile> untrackedFiles = ContainerUtil.mapNotNull(absolutePaths, GitUtil::findRefreshFileOrLog);

        NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO(operation.capitalize() + " failed"))
            .content(
                description == LocalizeValue.empty()
                    ? createUntrackedFilesOverwrittenDescription(operation, true)
                    : description
            )
            .hyperlinkListener((notification, event) -> {
                if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    LocalizeValue dialogDesc = createUntrackedFilesOverwrittenDescription(operation, false);
                    LocalizeValue title = LocalizeValue.localizeTODO("Untracked Files Preventing " + operation.capitalize());
                    if (untrackedFiles.isEmpty()) {
                        GitUtil.showPathsInDialog(project, absolutePaths, title, dialogDesc);
                    }
                    else {
                        LegacyComponentFactory componentFactory = Application.get().getInstance(LegacyComponentFactory.class);

                        LegacyDialog legacyDialog = componentFactory.createSelectFilesDialogOnlyOk(
                            project,
                            new ArrayList<>(untrackedFiles),
                            dialogDesc.map((localizeManager, string) -> StringUtil.stripHtml(string, true)).get(),
                            null,
                            false,
                            false,
                            true
                        );

                        legacyDialog.setTitle(title);
                        legacyDialog.show();
                    }
                }
            })
            .notify(project);
    }

    @Nonnull
    public static LocalizeValue createUntrackedFilesOverwrittenDescription(@Nonnull LocalizeValue operation, boolean addLinkToViewFiles) {
        String description1 = " untracked working tree files would be overwritten by " + operation + ".";
        String description2 = "Please move or remove them before you can " + operation + ".";
        String notificationDesc;
        if (addLinkToViewFiles) {
            notificationDesc = "Some" + description1 + "<br/>" + description2 + " <a href='view'>View them</a>";
        }
        else {
            notificationDesc = "These" + description1 + "<br/>" + description2;
        }
        return LocalizeValue.localizeTODO(notificationDesc);
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
    @RequiredUIAccess
    public static boolean showUntrackedFilesDialogWithRollback(
        @Nonnull Project project,
        @Nonnull LocalizeValue operationName,
        @Nonnull LocalizeValue rollbackProposal,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> relativePaths
    ) {
        Collection<String> absolutePaths = GitUtil.toAbsolute(root, relativePaths);
        List<VirtualFile> untrackedFiles = ContainerUtil.mapNotNull(absolutePaths, GitUtil::findRefreshFileOrLog);

        SimpleReference<Boolean> rollback = SimpleReference.create();
        Application application = project.getApplication();
        application.invokeAndWait(
            () -> {
                JComponent filesBrowser;
                if (untrackedFiles.isEmpty()) {
                    filesBrowser = new GitSimplePathsBrowser(project, absolutePaths);
                }
                else {
                    LegacyComponentFactory componentFactory = application.getInstance(LegacyComponentFactory.class);

                    filesBrowser = ScrollPaneFactory.createScrollPane(
                        componentFactory.createVirtualFileList(project, untrackedFiles, false, false).getComponent()
                    );
                }
                LocalizeValue title = LocalizeValue.localizeTODO("Could not " + operationName.capitalize());
                LocalizeValue description = createUntrackedFilesOverwrittenDescription(operationName, false)
                    .map((localizeManager, string) -> StringUtil.stripHtml(string, true));
                DialogWrapper dialog = new UntrackedFilesRollBackDialog(project, filesBrowser, description, rollbackProposal);
                dialog.setTitle(title);
                DialogManager.show(dialog);
                rollback.set(dialog.isOK());
            },
            application.getDefaultModalityState()
        );
        return rollback.get();
    }

    private static class UntrackedFilesRollBackDialog extends DialogWrapper {
        @Nonnull
        private final JComponent myFilesBrowser;
        @Nonnull
        private final LocalizeValue myPrompt;
        @Nonnull
        private final LocalizeValue myRollbackProposal;

        public UntrackedFilesRollBackDialog(
            @Nonnull Project project,
            @Nonnull JComponent filesBrowser,
            @Nonnull LocalizeValue prompt,
            @Nonnull LocalizeValue rollbackProposal
        ) {
            super(project);
            myFilesBrowser = filesBrowser;
            myPrompt = prompt;
            myRollbackProposal = rollbackProposal;
            setOKButtonText(LocalizeValue.localizeTODO("Rollback"));
            setCancelButtonText(LocalizeValue.localizeTODO("Don't rollback"));
            init();
        }

        @Override
        @RequiredUIAccess
        protected JComponent createSouthPanel() {
            JComponent buttons = super.createSouthPanel();
            JPanel panel = new JPanel(new VerticalFlowLayout());
            panel.add(
                new JBLabel(myRollbackProposal.map((localizeManager, string) -> XmlStringUtil.wrapInHtml(string)).get())
            );
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
            JLabel label = new JLabel(myPrompt.get());
            label.setUI(new MultiLineLabelUI());
            label.setBorder(new EmptyBorder(5, 1, 5, 1));
            return label;
        }
    }
}
