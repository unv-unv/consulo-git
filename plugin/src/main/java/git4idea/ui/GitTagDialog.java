/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package git4idea.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.JBScrollPane;
import consulo.ui.ex.awt.JBUI;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.event.DocumentAdapter;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandlerUtil;
import git4idea.commands.GitSimpleHandler;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitUIUtil;
import git4idea.util.StringScanner;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.io.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The tag dialog for the git
 */
public class GitTagDialog extends DialogWrapper {
    /**
     * Root panel
     */
    private JPanel myPanel;
    /**
     * Git root selector
     */
    private JComboBox myGitRootComboBox;
    /**
     * Current branch label
     */
    private JLabel myCurrentBranch;
    /**
     * Tag name
     */
    private JTextField myTagNameTextField;
    /**
     * Force tag creation checkbox
     */
    private JCheckBox myForceCheckBox;
    /**
     * Text area that contains tag message if non-empty
     */
    private JTextArea myMessageTextArea;
    /**
     * The name of commit to tag
     */
    private JTextField myCommitTextField;
    /**
     * The validate button
     */
    private JButton myValidateButton;
    /**
     * The validator for commit text field
     */
    private final GitReferenceValidator myCommitTextFieldValidator;
    /**
     * The current project
     */
    private final Project myProject;
    /**
     * Existing tags for the project
     */
    private final Set<String> myExistingTags = new HashSet<>();
    /**
     * Prefix for message file name
     */
    private static final String MESSAGE_FILE_PREFIX = "git-tag-message-";
    /**
     * Suffix for message file name
     */
    private static final String MESSAGE_FILE_SUFFIX = ".txt";
    /**
     * Encoding for the message file
     */
    private static final String MESSAGE_FILE_ENCODING = "UTF-8";

    /**
     * A constructor
     *
     * @param project     a project to select
     * @param roots       a git repository roots for the project
     * @param defaultRoot a guessed default root
     */
    public GitTagDialog(Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
        super(project, true);
        setTitle(GitLocalize.tagTitle());
        setOKButtonText(GitLocalize.tagButton());
        myProject = project;
        GitUIUtil.setupRootChooser(myProject, roots, defaultRoot, myGitRootComboBox, myCurrentBranch);
        myGitRootComboBox.addActionListener(e -> {
            fetchTags();
            validateFields();
        });
        fetchTags();
        myTagNameTextField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                validateFields();
            }
        });
        myCommitTextFieldValidator =
            new GitReferenceValidator(project, myGitRootComboBox, myCommitTextField, myValidateButton, this::validateFields);
        myForceCheckBox.addActionListener(e -> {
            if (myForceCheckBox.isEnabled()) {
                validateFields();
            }
        });
        init();
        validateFields();
    }

    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myTagNameTextField;
    }

    /**
     * Perform tagging according to selected options
     *
     * @param exceptions the list where exceptions are collected
     */
    @RequiredUIAccess
    public void runAction(List<VcsException> exceptions) {
        String message = myMessageTextArea.getText();
        boolean hasMessage = message.trim().length() != 0;
        File messageFile;
        if (hasMessage) {
            try {
                messageFile = FileUtil.createTempFile(MESSAGE_FILE_PREFIX, MESSAGE_FILE_SUFFIX);
                messageFile.deleteOnExit();
                try (Writer out = new OutputStreamWriter(new FileOutputStream(messageFile), MESSAGE_FILE_ENCODING)) {
                    out.write(message);
                }
            }
            catch (IOException ex) {
                Messages.showErrorDialog(
                    myProject,
                    GitLocalize.tagErrorCreatingMessageFileMessage(ex.toString()).get(),
                    GitLocalize.tagErrorCreatingMessageFileTitle().get()
                );
                return;
            }
        }
        else {
            messageFile = null;
        }
        try {
            GitSimpleHandler h = new GitSimpleHandler(myProject, getGitRoot(), GitCommand.TAG);
            if (hasMessage) {
                h.addParameters("-a");
            }
            if (myForceCheckBox.isEnabled() && myForceCheckBox.isSelected()) {
                h.addParameters("-f");
            }
            if (hasMessage) {
                h.addParameters("-F", messageFile.getAbsolutePath());
            }
            h.addParameters(myTagNameTextField.getText());
            String object = myCommitTextField.getText().trim();
            if (object.length() != 0) {
                h.addParameters(object);
            }
            try {
                GitHandlerUtil.doSynchronously(h, GitLocalize.taggingTitle(), LocalizeValue.ofNullable(h.printableCommandLine()));
                NotificationService.getInstance().newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                    .title(LocalizeValue.localizeTODO(myTagNameTextField.getText()))
                    .content(LocalizeValue.localizeTODO("Created tag " + myTagNameTextField.getText() + " successfully."))
                    .notify(myProject);
            }
            finally {
                exceptions.addAll(h.errors());
                GitRepositoryManager manager = GitUtil.getRepositoryManager(myProject);
                manager.updateRepository(getGitRoot());
            }
        }
        finally {
            if (messageFile != null) {
                //noinspection ResultOfMethodCallIgnored
                messageFile.delete();
            }
        }
    }

    /**
     * Validate dialog fields
     */
    private void validateFields() {
        String text = myTagNameTextField.getText();
        if (myExistingTags.contains(text)) {
            myForceCheckBox.setEnabled(true);
            if (!myForceCheckBox.isSelected()) {
                setErrorText(GitLocalize.tagErrorTagExists());
                setOKActionEnabled(false);
                return;
            }
        }
        else {
            myForceCheckBox.setEnabled(false);
            myForceCheckBox.setSelected(false);
        }

        if (myCommitTextFieldValidator.isInvalid()) {
            setErrorText(GitLocalize.tagErrorInvalidCommit());
            setOKActionEnabled(false);
        }
        else if (text.isEmpty()) {
            clearErrorText();
            setOKActionEnabled(false);
        }
        else {
            clearErrorText();
            setOKActionEnabled(true);
        }
    }

    /**
     * Fetch tags
     */
    private void fetchTags() {
        myExistingTags.clear();
        GitSimpleHandler h = new GitSimpleHandler(myProject, getGitRoot(), GitCommand.TAG);
        h.setSilent(true);
        String output =
            GitHandlerUtil.doSynchronously(h, GitLocalize.tagGettingExistingTags(), LocalizeValue.ofNullable(h.printableCommandLine()));
        for (StringScanner s = new StringScanner(output); s.hasMoreData(); ) {
            String line = s.line();
            if (line.length() == 0) {
                continue;
            }
            myExistingTags.add(line);
        }
    }

    /**
     * @return the current git root
     */
    private VirtualFile getGitRoot() {
        return (VirtualFile) myGitRootComboBox.getSelectedItem();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected JComponent createCenterPanel() {
        return myPanel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDimensionServiceKey() {
        return getClass().getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getHelpId() {
        return "reference.VersionControl.Git.TagFiles";
    }

    {
// GUI initializer generated by Consulo GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by Consulo GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     */
    private void $$$setupUI$$$() {
        myPanel = new JPanel();
        myPanel.setLayout(new GridLayoutManager(6, 2, JBUI.emptyInsets(), -1, -1));
        JLabel label1 = new JLabel();
        this.$$$loadLabelText$$$(label1, GitLocalize.commonGitRoot().get());
        myPanel.add(
            label1,
            new GridConstraints(
                0,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myGitRootComboBox = new JComboBox();
        myGitRootComboBox.setToolTipText(GitLocalize.commonGitRootTooltip().get());
        myPanel.add(
            myGitRootComboBox,
            new GridConstraints(
                0,
                1,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        JLabel label2 = new JLabel();
        this.$$$loadLabelText$$$(label2, GitLocalize.commonCurrentBranch().get());
        myPanel.add(
            label2,
            new GridConstraints(
                1,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myCurrentBranch = new JLabel();
        myCurrentBranch.setText("");
        myCurrentBranch.setToolTipText(GitLocalize.commonCurrentBranchTooltip().get());
        myPanel.add(
            myCurrentBranch,
            new GridConstraints(
                1,
                1,
                1,
                1,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        JLabel label3 = new JLabel();
        this.$$$loadLabelText$$$(label3, GitLocalize.tagNameLabel().get());
        myPanel.add(
            label3,
            new GridConstraints(
                2,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myTagNameTextField = new JTextField();
        myTagNameTextField.setToolTipText(GitLocalize.tagNameTooltip().get());
        myPanel.add(
            myTagNameTextField,
            new GridConstraints(
                2,
                1,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                new Dimension(150, -1),
                null,
                0,
                false
            )
        );
        JLabel label4 = new JLabel();
        this.$$$loadLabelText$$$(label4, GitLocalize.tagMessageLabel().get());
        label4.setVerticalAlignment(0);
        myPanel.add(
            label4,
            new GridConstraints(
                5,
                0,
                1,
                1,
                GridConstraints.ANCHOR_NORTHWEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        JBScrollPane jBScrollPane1 = new JBScrollPane();
        myPanel.add(
            jBScrollPane1,
            new GridConstraints(
                5,
                1,
                1,
                1,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null,
                null,
                null,
                0,
                false
            )
        );
        myMessageTextArea = new JTextArea();
        myMessageTextArea.setRows(4);
        myMessageTextArea.setToolTipText(GitLocalize.tagMessageTooltip().get());
        jBScrollPane1.setViewportView(myMessageTextArea);
        JLabel label5 = new JLabel();
        this.$$$loadLabelText$$$(label5, GitLocalize.tagCommitLabel().get());
        myPanel.add(
            label5,
            new GridConstraints(
                4,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myForceCheckBox = new JCheckBox();
        myForceCheckBox.setEnabled(false);
        this.$$$loadButtonText$$$(myForceCheckBox, GitLocalize.tagForce().get());
        myForceCheckBox.setToolTipText(GitLocalize.tagForceTooltip().get());
        myPanel.add(
            myForceCheckBox,
            new GridConstraints(
                3,
                1,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 2, JBUI.emptyInsets(), -1, -1));
        myPanel.add(
            panel1,
            new GridConstraints(
                4,
                1,
                1,
                1,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null,
                null,
                null,
                0,
                false
            )
        );
        myCommitTextField = new JTextField();
        myCommitTextField.setToolTipText(GitLocalize.tagCommitTooltip().get());
        panel1.add(
            myCommitTextField,
            new GridConstraints(
                0,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                new Dimension(150, -1),
                null,
                0,
                false
            )
        );
        myValidateButton = new JButton();
        this.$$$loadButtonText$$$(myValidateButton, GitLocalize.tagValidate().get());
        myValidateButton.setToolTipText(GitLocalize.tagValidateTooltip().get());
        panel1.add(
            myValidateButton,
            new GridConstraints(
                0,
                1,
                1,
                1,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        label1.setLabelFor(myGitRootComboBox);
        label3.setLabelFor(myTagNameTextField);
        label4.setLabelFor(myMessageTextArea);
        label5.setLabelFor(myCommitTextField);
    }

    private void $$$loadLabelText$$$(JLabel component, String text) {
        StringBuilder result = new StringBuilder();
        boolean haveMnemonic = false;
        char mnemonic = '\0';
        int mnemonicIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') {
                i++;
                if (i == text.length()) {
                    break;
                }
                if (!haveMnemonic && text.charAt(i) != '&') {
                    haveMnemonic = true;
                    mnemonic = text.charAt(i);
                    mnemonicIndex = result.length();
                }
            }
            result.append(text.charAt(i));
        }
        component.setText(result.toString());
        if (haveMnemonic) {
            component.setDisplayedMnemonic(mnemonic);
            component.setDisplayedMnemonicIndex(mnemonicIndex);
        }
    }

    private void $$$loadButtonText$$$(AbstractButton component, String text) {
        StringBuilder result = new StringBuilder();
        boolean haveMnemonic = false;
        char mnemonic = '\0';
        int mnemonicIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') {
                i++;
                if (i == text.length()) {
                    break;
                }
                if (!haveMnemonic && text.charAt(i) != '&') {
                    haveMnemonic = true;
                    mnemonic = text.charAt(i);
                    mnemonicIndex = result.length();
                }
            }
            result.append(text.charAt(i));
        }
        component.setText(result.toString());
        if (haveMnemonic) {
            component.setMnemonic(mnemonic);
            component.setDisplayedMnemonicIndex(mnemonicIndex);
        }
    }

    public JComponent $$$getRootComponent$$$() {
        return myPanel;
    }
}
