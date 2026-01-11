/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

import consulo.annotation.DeprecationInfo;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.NotificationType;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.ListCellRendererWrapper;
import consulo.ui.ex.awt.Messages;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.AbstractVcsHelper;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitBranch;
import git4idea.GitUtil;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Utilities for git plugin user interface
 */
public class GitUIUtil {
    /**
     * A private constructor for utility class
     */
    private GitUIUtil() {
    }

    public static void notifyMessages(
        @Nonnull Project project,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue description,
        boolean important,
        @Nullable Collection<String> messages
    ) {
        LocalizeValue desc = description.map((localizeManager, string) -> string.replace("\n", "<br/>"));
        if (messages != null && !messages.isEmpty()) {
            desc = LocalizeValue.join(desc, LocalizeValue.of(StringUtil.join(messages, "<hr/><br/>")));
        }
        NotificationService.getInstance().newOfType(
                VcsNotifier.IMPORTANT_ERROR_NOTIFICATION,
                important ? NotificationType.ERROR : NotificationType.WARNING
            )
            .title(title)
            .content(desc)
            .notify(project);
    }

    public static void notifyMessage(
        Project project,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue description,
        boolean important,
        @Nullable Collection<? extends Exception> errors
    ) {
        Collection<String> errorMessages;
        if (errors == null) {
            errorMessages = null;
        }
        else {
            errorMessages = new HashSet<>(errors.size());
            for (Exception error : errors) {
                if (error instanceof VcsException vcsException) {
                    for (String message : vcsException.getMessages()) {
                        errorMessages.add(message.replace("\n", "<br/>"));
                    }
                }
                else {
                    errorMessages.add(error.getMessage().replace("\n", "<br/>"));
                }
            }
        }
        notifyMessages(project, title, description, important, errorMessages);
    }

    public static void notifyError(
        Project project,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue description,
        boolean important,
        @Nullable Exception error
    ) {
        notifyMessage(project, title, description, important, error == null ? null : Collections.singleton(error));
    }

    /**
     * Splits the given VcsExceptions to one string. Exceptions are separated by &lt;br/&gt;
     * Line separator is also replaced by &lt;br/&gt;
     */
    public static
    @Nonnull
    String stringifyErrors(@Nullable Collection<VcsException> errors) {
        if (errors == null) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (VcsException e : errors) {
            for (String message : e.getMessages()) {
                content.append(message.replace("\n", "<br/>")).append("<br/>");
            }
        }
        return content.toString();
    }

    public static void notifyImportantError(Project project, @Nonnull LocalizeValue title, @Nonnull LocalizeValue description) {
        notifyMessage(project, title, description, true, null);
    }

    public static void notifyGitErrors(
        Project project,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue description,
        Collection<VcsException> gitErrors
    ) {
        StringBuilder content = new StringBuilder();
        if (description.isNotEmpty()) {
            content.append(description);
        }
        if (!gitErrors.isEmpty()) {
            content.append("<br/>");
        }
        for (VcsException e : gitErrors) {
            content.append(e.getLocalizedMessage()).append("<br/>");
        }
        notifyMessage(project, title, LocalizeValue.of(content.toString()), false, null);
    }

    /**
     * @return a list cell renderer for virtual files (it renders presentable URL)
     */
    public static ListCellRendererWrapper<VirtualFile> getVirtualFileListCellRenderer() {
        return new ListCellRendererWrapper<>() {
            @Override
            public void customize(
                JList list,
                VirtualFile file,
                int index,
                boolean selected,
                boolean hasFocus
            ) {
                setText(file == null ? "(invalid)" : file.getPresentableUrl());
            }
        };
    }

    /**
     * Get text field from combobox
     *
     * @param comboBox a combobox to examine
     * @return the text field reference
     */
    public static JTextField getTextField(JComboBox comboBox) {
        return (JTextField) comboBox.getEditor().getEditorComponent();
    }

    /**
     * Setup root chooser with specified elements and link selection to the current branch label.
     *
     * @param project            a context project
     * @param roots              git roots for the project
     * @param defaultRoot        a default root
     * @param gitRootChooser     git root selector
     * @param currentBranchLabel current branch label (might be null)
     */
    public static void setupRootChooser(
        @Nonnull Project project,
        @Nonnull List<VirtualFile> roots,
        @Nullable VirtualFile defaultRoot,
        @Nonnull JComboBox gitRootChooser,
        @Nullable JLabel currentBranchLabel
    ) {
        for (VirtualFile root : roots) {
            gitRootChooser.addItem(root);
        }
        gitRootChooser.setRenderer(getVirtualFileListCellRenderer());
        gitRootChooser.setSelectedItem(defaultRoot != null ? defaultRoot : roots.get(0));
        if (currentBranchLabel != null) {
            ActionListener listener = e -> {
                VirtualFile root = (VirtualFile) gitRootChooser.getSelectedItem();
                assert root != null : "The root must not be null";
                GitRepository repo = GitUtil.getRepositoryManager(project).getRepositoryForRoot(root);
                assert repo != null : "The repository must not be null";
                GitBranch current = repo.getCurrentBranch();
                if (current == null) {
                    currentBranchLabel.setText(GitLocalize.commonNoActiveBranch().get());
                }
                else {
                    currentBranchLabel.setText(current.getName());
                }
            };
            listener.actionPerformed(null);
            gitRootChooser.addActionListener(listener);
        }
    }

    /**
     * Show error associated with the specified operation
     *
     * @param project   the project
     * @param ex        the exception
     * @param operation the operation name
     */
    @RequiredUIAccess
    public static void showOperationError(Project project, VcsException ex, @Nonnull String operation) {
        showOperationError(project, operation, ex.getMessage());
    }

    /**
     * Show errors associated with the specified operation
     *
     * @param project   the project
     * @param exs       the exceptions to show
     * @param operation the operation name
     */
    @RequiredUIAccess
    public static void showOperationErrors(
        Project project,
        Collection<VcsException> exs,
        @Nonnull LocalizeValue operation
    ) {
        if (exs.size() == 1) {
            //noinspection ThrowableResultOfMethodCallIgnored
            showOperationError(project, operation, LocalizeValue.ofNullable(exs.iterator().next().getMessage()));
        }
        else if (exs.size() > 1) {
            // TODO use dialog in order to show big messages
            StringBuilder b = new StringBuilder();
            for (VcsException ex : exs) {
                b.append(GitLocalize.errorsMessageItem(ex.getMessage()));
            }
            showOperationError(project, operation, GitLocalize.errorsMessage(b.toString()));
        }
    }

    /**
     * Show error associated with the specified operation
     *
     * @param project   the project
     * @param message   the error description
     * @param operation the operation name
     */
    @RequiredUIAccess
    public static void showOperationError(Project project, @Nonnull LocalizeValue operation, @Nonnull LocalizeValue message) {
        Messages.showErrorDialog(project, message.get(), GitLocalize.errorOccurredDuring(operation).get());
    }

    /**
     * Show error associated with the specified operation
     *
     * @param project   the project
     * @param message   the error description
     * @param operation the operation name
     */
    @Deprecated
    @DeprecationInfo("Use variant with LocalizeValue")
    @RequiredUIAccess
    public static void showOperationError(Project project, String operation, String message) {
        Messages.showErrorDialog(project, message, GitLocalize.errorOccurredDuring(operation).get());
    }

    /**
     * Show errors on the tab
     *
     * @param project the context project
     * @param title   the operation title
     * @param errors  the errors to display
     */
    public static void showTabErrors(Project project, String title, List<VcsException> errors) {
        AbstractVcsHelper.getInstance(project).showErrors(errors, title);
    }

    /**
     * Checks state of the {@code checked} checkbox and if state is {@code checkedState} than to disable {@code changed}
     * checkbox and change its state to {@code impliedState}. When the {@code checked} checkbox changes states to other state,
     * than enable {@code changed} and restore its state. Note that the each checkbox should be implied by only one other checkbox.
     *
     * @param checked      the checkbox to monitor
     * @param checkedState the state that triggers disabling changed state
     * @param changed      the checkbox to change
     * @param impliedState the implied state of checkbox
     */
    public static void imply(final JCheckBox checked, final boolean checkedState, final JCheckBox changed, final boolean impliedState) {
        ActionListener l = new ActionListener() {
            Boolean previousState;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (checked.isSelected() == checkedState) {
                    if (previousState == null) {
                        previousState = changed.isSelected();
                    }
                    changed.setEnabled(false);
                    changed.setSelected(impliedState);
                }
                else {
                    changed.setEnabled(true);
                    if (previousState != null) {
                        changed.setSelected(previousState);
                        previousState = null;
                    }
                }
            }
        };
        checked.addActionListener(l);
        l.actionPerformed(null);
    }

    /**
     * Declares states for two checkboxes to be mutually exclusive. When one of the checkboxes goes to the specified state, other is
     * disabled and forced into reverse of the state (to prevent very fast users from selecting incorrect state or incorrect
     * initial configuration).
     *
     * @param first       the first checkbox
     * @param firstState  the state of the first checkbox
     * @param second      the second checkbox
     * @param secondState the state of the second checkbox
     */
    public static void exclusive(final JCheckBox first, final boolean firstState, final JCheckBox second, final boolean secondState) {
        ActionListener l = new ActionListener() {
            /**
             * One way check for the condition
             * @param checked the first to check
             * @param checkedState the state to match
             * @param changed the changed control
             * @param impliedState the implied state
             */
            private void check(JCheckBox checked, boolean checkedState, JCheckBox changed, boolean impliedState) {
                if (checked.isSelected() == checkedState) {
                    changed.setSelected(impliedState);
                    changed.setEnabled(false);
                }
                else {
                    changed.setEnabled(true);
                }
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void actionPerformed(ActionEvent e) {
                check(first, firstState, second, !secondState);
                check(second, secondState, first, !firstState);
            }
        };
        first.addActionListener(l);
        second.addActionListener(l);
        l.actionPerformed(null);
    }

    /**
     * Checks state of the {@code checked} checkbox and if state is {@code checkedState} than to disable {@code changed}
     * text field and clean it. When the {@code checked} checkbox changes states to other state,
     * than enable {@code changed} and restore its state. Note that the each text field should be implied by
     * only one other checkbox.
     *
     * @param checked      the checkbox to monitor
     * @param checkedState the state that triggers disabling changed state
     * @param changed      the checkbox to change
     */
    public static void implyDisabled(final JCheckBox checked, final boolean checkedState, final JTextComponent changed) {
        ActionListener l = new ActionListener() {
            String previousState;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (checked.isSelected() == checkedState) {
                    if (previousState == null) {
                        previousState = changed.getText();
                    }
                    changed.setEnabled(false);
                    changed.setText("");
                }
                else {
                    changed.setEnabled(true);
                    if (previousState != null) {
                        changed.setText(previousState);
                        previousState = null;
                    }
                }
            }
        };
        checked.addActionListener(l);
        l.actionPerformed(null);
    }

    public static String bold(String s) {
        return surround(s, "b");
    }

    public static String code(String s) {
        return surround(s, "code");
    }

    private static String surround(String s, String tag) {
        return String.format("<%2$s>%1$s</%2$s>", s, tag);
    }
}
