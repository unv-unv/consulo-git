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
package git4idea.update;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import consulo.application.Application;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.*;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import git4idea.rollback.GitRollbackEnvironment;
import git4idea.util.GitUIUtil;
import git4idea.util.StringScanner;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dialog that displays locally modified files during update process
 */
public class GitUpdateLocallyModifiedDialog extends DialogWrapper {
    /**
     * The rescan button
     */
    private JButton myRescanButton;
    /**
     * The list of files to revert
     */
    private JList<String> myFilesList;

    private JLabel myDescriptionLabel;
    /**
     * The git root label
     */
    private JLabel myGitRoot;
    /**
     * The root panel
     */
    private JPanel myRootPanel;
    /**
     * The collection with locally modified files
     */
    private final List<String> myLocallyModifiedFiles;

    /**
     * The constructor
     *
     * @param project              the current project
     * @param root                 the vcs root
     * @param locallyModifiedFiles the collection of locally modified files to use
     */
    @RequiredUIAccess
    protected GitUpdateLocallyModifiedDialog(Project project, VirtualFile root, List<String> locallyModifiedFiles) {
        super(project, true);
        myLocallyModifiedFiles = locallyModifiedFiles;
        setTitle(GitLocalize.updateLocallyModifiedTitle());
        myGitRoot.setText(root.getPresentableUrl());
        myFilesList.setModel(new DefaultListModel<>());
        setOKButtonText(GitLocalize.updateLocallyModifiedRevert());
        syncListModel();
        myRescanButton.addActionListener(e -> {
            myLocallyModifiedFiles.clear();
            try {
                scanFiles(project, root, myLocallyModifiedFiles);
            }
            catch (VcsException ex) {
                GitUIUtil.showOperationError(project, ex, "Checking for locally modified files");
            }
        });
        myDescriptionLabel.setText(GitLocalize.updateLocallyModifiedMessage(Application.get().getName()).get());
        init();
    }

    /**
     * Refresh list model according to the current content of the collection
     */
    @SuppressWarnings("unchecked")
    private void syncListModel() {
        DefaultListModel<String> listModel = (DefaultListModel) myFilesList.getModel();
        listModel.removeAllElements();
        for (String p : myLocallyModifiedFiles) {
            listModel.addElement(p);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected JComponent createCenterPanel() {
        return myRootPanel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDimensionServiceKey() {
        return getClass().getName();
    }

    /**
     * Scan working tree and detect locally modified files
     *
     * @param project the project to scan
     * @param root    the root to scan
     * @param files   the collection with files
     * @throws VcsException if there problem with running git or working tree is dirty in unsupported way
     */
    private static void scanFiles(Project project, VirtualFile root, List<String> files) throws VcsException {
        String rootPath = root.getPath();
        GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.DIFF);
        h.addParameters("--name-status");
        h.setSilent(true);
        h.setStdoutSuppressed(true);
        StringScanner s = new StringScanner(h.run());
        while (s.hasMoreData()) {
            if (s.isEol()) {
                s.line();
                continue;
            }
            if (s.tryConsume("M\t")) {
                String path = rootPath + "/" + GitUtil.unescapePath(s.line());
                files.add(path);
            }
            else {
                throw new VcsException("Working tree is dirty in unsupported way: " + s.line());
            }
        }
    }

    /**
     * Show the dialog if needed
     *
     * @param project the project
     * @param root    the vcs root
     * @return true if showing is not needed or operation completed successfully
     */
    public static boolean showIfNeeded(Project project, VirtualFile root) {
        List<String> files = new ArrayList<>();
        try {
            scanFiles(project, root, files);
            AtomicBoolean rc = new AtomicBoolean(true);
            if (!files.isEmpty()) {
                UIUtil.invokeAndWaitIfNeeded((Runnable) () -> {
                    GitUpdateLocallyModifiedDialog d = new GitUpdateLocallyModifiedDialog(project, root, files);
                    d.show();
                    rc.set(d.isOK());
                });
                if (rc.get() && !files.isEmpty()) {
                    revertFiles(project, root, files);
                }
            }
            return rc.get();
        }
        catch (VcsException e) {
            UIUtil.invokeAndWaitIfNeeded((Runnable) () -> GitUIUtil.showOperationError(project, e, "Checking for locally modified files"));
            return false;
        }
    }

    /**
     * Revert files from the list
     *
     * @param project the project
     * @param root    the vcs root
     * @param files   the files to revert
     */
    private static void revertFiles(Project project, VirtualFile root, List<String> files) throws VcsException {
        // TODO consider deleted files
        GitRollbackEnvironment rollback = GitRollbackEnvironment.getInstance(project);
        List<FilePath> list = new ArrayList<>(files.size());
        for (String p : files) {
            list.add(VcsUtil.getFilePath(p));
        }
        rollback.revert(root, list);
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
        myRootPanel = new JPanel();
        myRootPanel.setLayout(new GridLayoutManager(3, 3, JBUI.emptyInsets(), -1, -1));
        JLabel label1 = new JLabel();
        this.$$$loadLabelText$$$(label1, GitLocalize.updateLocallyModifiedGitRoot().get());
        myRootPanel.add(
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
        myGitRoot = new JLabel();
        myGitRoot.setText("");
        myRootPanel.add(
            myGitRoot,
            new GridConstraints(
                0,
                1,
                1,
                2,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myDescriptionLabel = new JLabel();
        myDescriptionLabel.setText("");
        myRootPanel.add(
            myDescriptionLabel,
            new GridConstraints(
                1,
                0,
                1,
                3,
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
        JBScrollPane jBScrollPane1 = new JBScrollPane();
        myRootPanel.add(
            jBScrollPane1,
            new GridConstraints(
                2,
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
        myFilesList = new JBList<>();
        myFilesList.setToolTipText(GitLocalize.updateLocallyModifiedFilesTooltip().get());
        jBScrollPane1.setViewportView(myFilesList);
        myRescanButton = new JButton();
        this.$$$loadButtonText$$$(myRescanButton, GitLocalize.updateLocallyModifiedRescan().get());
        myRescanButton.setToolTipText(GitLocalize.updateLocallyModifiedRescanTooltip().get());
        myRootPanel.add(
            myRescanButton,
            new GridConstraints(
                2,
                2,
                1,
                1,
                GridConstraints.ANCHOR_NORTH,
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
        JLabel label2 = new JLabel();
        this.$$$loadLabelText$$$(label2, GitLocalize.updateLocallyModifiedFiles().get());
        label2.setVerticalAlignment(0);
        myRootPanel.add(
            label2,
            new GridConstraints(
                2,
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
        label2.setLabelFor(jBScrollPane1);
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
        return myRootPanel;
    }
}
