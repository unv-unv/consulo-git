/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
import com.intellij.uiDesigner.core.Spacer;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.platform.base.localize.CommonLocalize;
import consulo.process.cmd.GeneralCommandLine;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.ModalityState;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.*;
import consulo.ui.ex.awt.event.DocumentAdapter;
import consulo.util.dataholder.Key;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.merge.MergeDialogCustomizer;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchUtil;
import git4idea.commands.*;
import git4idea.config.GitVersionSpecialty;
import git4idea.merge.GitConflictResolver;
import git4idea.repo.GitRepository;
import git4idea.stash.GitStashUtils;
import git4idea.util.GitUIUtil;
import git4idea.validators.GitBranchNameValidator;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The unstash dialog
 */
public class GitUnstashDialog extends DialogWrapper {
    /**
     * Git root selector
     */
    private JComboBox myGitRootComboBox;
    /**
     * The current branch label
     */
    private JLabel myCurrentBranch;
    /**
     * The view stash button
     */
    private JButton myViewButton;
    /**
     * The drop stash button
     */
    private JButton myDropButton;
    /**
     * The clear stashes button
     */
    private JButton myClearButton;
    /**
     * The pop stash checkbox
     */
    private JCheckBox myPopStashCheckBox;
    /**
     * The branch text field
     */
    private JTextField myBranchTextField;
    /**
     * The root panel of the dialog
     */
    private JPanel myPanel;
    /**
     * The stash list
     */
    private JList<StashInfo> myStashList;
    /**
     * If this checkbox is selected, the index is reinstated as well as working tree
     */
    private JCheckBox myReinstateIndexCheckBox;
    /**
     * Set of branches for the current root
     */
    private final Set<String> myBranches = new HashSet<>();

    @Nonnull
    private final Project myProject;
    private GitVcs myVcs;
    private static final Logger LOG = Logger.getInstance(GitUnstashDialog.class);

    /**
     * A constructor
     *
     * @param project     the project
     * @param roots       the list of the roots
     * @param defaultRoot the default root to select
     */
    @RequiredUIAccess
    public GitUnstashDialog(@Nonnull Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
        super(project, true);
        setModal(false);
        myProject = project;
        myVcs = GitVcs.getInstance(project);
        setTitle(GitLocalize.unstashTitle());
        setOKButtonText(GitLocalize.unstashButtonApply());
        setCancelButtonText(CommonLocalize.buttonClose());
        GitUIUtil.setupRootChooser(project, roots, defaultRoot, myGitRootComboBox, myCurrentBranch);
        myStashList.setModel(new DefaultListModel<>());
        refreshStashList();
        myGitRootComboBox.addActionListener(e -> {
            refreshStashList();
            updateDialogState();
        });
        myStashList.addListSelectionListener(e -> updateDialogState());
        myBranchTextField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                updateDialogState();
            }
        });
        myPopStashCheckBox.addActionListener(e -> updateDialogState());
        myClearButton.addActionListener(e -> {
            if (Messages.YES == Messages.showYesNoDialog(
                GitUnstashDialog.this.getContentPane(),
                GitLocalize.gitUnstashClearConfirmationMessage().get(),
                GitLocalize.gitUnstashClearConfirmationTitle().get(),
                UIUtil.getWarningIcon()
            )) {
                GitLineHandler h = new GitLineHandler(myProject, getGitRoot(), GitCommand.STASH);
                h.addParameters("clear");
                GitHandlerUtil.doSynchronously(h, GitLocalize.unstashClearingStashes(), LocalizeValue.ofNullable(h.printableCommandLine()));
                refreshStashList();
                updateDialogState();
            }
        });
        myDropButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                final StashInfo stash = getSelectedStash();
                if (Messages.YES == Messages.showYesNoDialog(
                    GitUnstashDialog.this.getContentPane(),
                    GitLocalize.gitUnstashDropConfirmationMessage(stash.getStash(), stash.getMessage()).get(),
                    GitLocalize.gitUnstashDropConfirmationTitle(stash.getStash()).get(),
                    UIUtil.getQuestionIcon()
                )) {
                    final ModalityState current = myProject.getApplication().getCurrentModalityState();
                    ProgressManager.getInstance()
                        .run(new Task.Modal(myProject, LocalizeValue.localizeTODO("Removing stash " + stash.getStash()), false) {
                            @Override
                            public void run(@Nonnull ProgressIndicator indicator) {
                                GitSimpleHandler h = dropHandler(stash.getStash());
                                try {
                                    h.run();
                                    h.unsilence();
                                }
                                catch (VcsException ex) {
                                    project.getApplication().invokeLater(
                                        () -> GitUIUtil.showOperationError((Project) myProject, ex, h.printableCommandLine()),
                                        current
                                    );
                                }
                            }
                        });
                    refreshStashList();
                    updateDialogState();
                }
            }

            private GitSimpleHandler dropHandler(String stash) {
                GitSimpleHandler h = new GitSimpleHandler(myProject, getGitRoot(), GitCommand.STASH);
                h.addParameters("drop");
                addStashParameter(h, stash);
                return h;
            }
        });
        myViewButton.addActionListener(e -> {
            VirtualFile root = getGitRoot();
            String resolvedStash;
            String selectedStash = getSelectedStash().getStash();
            try {
                GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.REV_LIST);
                h.setSilent(true);
                h.addParameters("--timestamp", "--max-count=1");
                addStashParameter(h, selectedStash);
                h.endOptions();
                String output = h.run();
                resolvedStash = GitRevisionNumber.parseRevlistOutputAsRevisionNumber(h, output).asString();
            }
            catch (VcsException ex) {
                GitUIUtil.showOperationError(myProject, ex, "resolving revision");
                return;
            }
            GitUtil.showSubmittedFiles(myProject, resolvedStash, root, true, false);
        });
        init();
        updateDialogState();
    }

    /**
     * Adds {@code stash@{x}} parameter to the handler, quotes it if needed.
     */
    private void addStashParameter(@Nonnull GitHandler handler, @Nonnull String stash) {
        if (GitVersionSpecialty.NEEDS_QUOTES_IN_STASH_NAME.existsIn(myVcs.getVersion())) {
            handler.addParameters(GeneralCommandLine.inescapableQuote(stash));
        }
        else {
            handler.addParameters(stash);
        }
    }

    /**
     * Update state dialog depending on the current state of the fields
     */
    private void updateDialogState() {
        String branch = myBranchTextField.getText();
        if (branch.length() != 0) {
            setOKButtonText(GitLocalize.unstashButtonBranch());
            myPopStashCheckBox.setEnabled(false);
            myPopStashCheckBox.setSelected(true);
            myReinstateIndexCheckBox.setEnabled(false);
            myReinstateIndexCheckBox.setSelected(true);
            if (!GitBranchNameValidator.INSTANCE.checkInput(branch)) {
                setErrorText(GitLocalize.unstashErrorInvalidBranchName());
                setOKActionEnabled(false);
                return;
            }
            if (myBranches.contains(branch)) {
                setErrorText(GitLocalize.unstashErrorBranchExists());
                setOKActionEnabled(false);
                return;
            }
        }
        else {
            if (!myPopStashCheckBox.isEnabled()) {
                myPopStashCheckBox.setSelected(false);
            }
            myPopStashCheckBox.setEnabled(true);
            setOKButtonText(myPopStashCheckBox.isSelected() ? GitLocalize.unstashButtonPop() : GitLocalize.unstashButtonApply());
            if (!myReinstateIndexCheckBox.isEnabled()) {
                myReinstateIndexCheckBox.setSelected(false);
            }
            myReinstateIndexCheckBox.setEnabled(true);
        }

        clearErrorText();

        if (myStashList.getModel().getSize() == 0) {
            myViewButton.setEnabled(false);
            myDropButton.setEnabled(false);
            myClearButton.setEnabled(false);
            setOKActionEnabled(false);
            return;
        }
        else {
            myClearButton.setEnabled(true);
        }

        boolean enableButtons = myStashList.getSelectedIndex() != -1;
        myViewButton.setEnabled(enableButtons);
        myDropButton.setEnabled(enableButtons);
        setOKActionEnabled(enableButtons);
    }

    /**
     * Refresh stash list
     */
    @RequiredUIAccess
    private void refreshStashList() {
        DefaultListModel listModel = (DefaultListModel) myStashList.getModel();
        listModel.clear();
        VirtualFile root = getGitRoot();
        GitStashUtils.loadStashStack(myProject, root, listModel::addElement);
        myBranches.clear();
        GitRepository repository = GitUtil.getRepositoryManager(myProject).getRepositoryForRoot(root);
        if (repository != null) {
            myBranches.addAll(GitBranchUtil.convertBranchesToNames(repository.getBranches().getLocalBranches()));
        }
        else {
            LOG.error("Repository is null for root " + root);
        }
        myStashList.setSelectedIndex(0);
    }

    /**
     * @return the selected git root
     */
    private VirtualFile getGitRoot() {
        return (VirtualFile) myGitRootComboBox.getSelectedItem();
    }

    /**
     * @return unstash handler
     */
    private GitLineHandler handler() {
        GitLineHandler h = new GitLineHandler(myProject, getGitRoot(), GitCommand.STASH);
        String branch = myBranchTextField.getText();
        if (branch.length() == 0) {
            h.addParameters(myPopStashCheckBox.isSelected() ? "pop" : "apply");
            if (myReinstateIndexCheckBox.isSelected()) {
                h.addParameters("--index");
            }
        }
        else {
            h.addParameters("branch", branch);
        }
        String selectedStash = getSelectedStash().getStash();
        addStashParameter(h, selectedStash);
        return h;
    }

    /**
     * @return selected stash
     * @throws NullPointerException if no stash is selected
     */
    private StashInfo getSelectedStash() {
        return myStashList.getSelectedValue();
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
        return "reference.VersionControl.Git.Unstash";
    }

    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myStashList;
    }

    @Override
    @RequiredUIAccess
    protected void doOKAction() {
        VirtualFile root = getGitRoot();
        GitLineHandler h = handler();
        final AtomicBoolean conflict = new AtomicBoolean();

        h.addLineListener(new GitLineHandlerAdapter() {
            @Override
            public void onLineAvailable(String line, Key outputType) {
                if (line.contains("Merge conflict")) {
                    conflict.set(true);
                }
            }
        });
        int rc = GitHandlerUtil.doSynchronously(h, GitLocalize.unstashUnstashing(), LocalizeValue.of(h.printableCommandLine()), false);

        VirtualFileUtil.markDirtyAndRefresh(true, true, false, root);

        if (conflict.get()) {
            boolean conflictsResolved = new UnstashConflictResolver(myProject, root, getSelectedStash()).merge();
            LOG.info("loadRoot " + root + ", conflictsResolved: " + conflictsResolved);
        }
        else if (rc != 0) {
            GitUIUtil.showOperationErrors(myProject, h.errors(), LocalizeValue.of(h.printableCommandLine()));
        }
        super.doOKAction();
    }

    @RequiredUIAccess
    public static void showUnstashDialog(Project project, List<VirtualFile> gitRoots, VirtualFile defaultRoot) {
        new GitUnstashDialog(project, gitRoots, defaultRoot).show();
        // d is not modal=> everything else in doOKAction.
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
        myPanel.setLayout(new GridLayoutManager(5, 3, JBUI.emptyInsets(), -1, -1));
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
                2,
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
        myCurrentBranch.setText("  ");
        myCurrentBranch.setToolTipText(GitLocalize.commonCurrentBranchTooltip().get());
        myPanel.add(
            myCurrentBranch,
            new GridConstraints(
                1,
                1,
                1,
                2,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        JLabel label3 = new JLabel();
        this.$$$loadLabelText$$$(label3, GitLocalize.unstashStashes().get());
        myPanel.add(
            label3,
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
        JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(4, 1, JBUI.emptyInsets(), -1, -1));
        myPanel.add(
            panel1,
            new GridConstraints(
                2,
                2,
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
        myViewButton = new JButton();
        this.$$$loadButtonText$$$(myViewButton, GitLocalize.unstashView().get());
        myViewButton.setToolTipText(GitLocalize.unstashViewTooltip().get());
        panel1.add(
            myViewButton,
            new GridConstraints(
                0,
                0,
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
        Spacer spacer1 = new Spacer();
        panel1.add(
            spacer1,
            new GridConstraints(
                3,
                0,
                1,
                1,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_VERTICAL,
                1,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                null,
                null,
                null,
                0,
                false
            )
        );
        myDropButton = new JButton();
        this.$$$loadButtonText$$$(myDropButton, GitLocalize.unstashDrop().get());
        myDropButton.setToolTipText(GitLocalize.unstashDropTooltip().get());
        panel1.add(
            myDropButton,
            new GridConstraints(
                1,
                0,
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
        myClearButton = new JButton();
        this.$$$loadButtonText$$$(myClearButton, GitLocalize.unstashClear().get());
        myClearButton.setToolTipText(GitLocalize.unstashClearTooltip().get());
        panel1.add(
            myClearButton,
            new GridConstraints(
                2,
                0,
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
        JLabel label4 = new JLabel();
        this.$$$loadLabelText$$$(label4, GitLocalize.unstashBranchLabel().get());
        myPanel.add(
            label4,
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
        myBranchTextField = new JTextField();
        myBranchTextField.setToolTipText(GitLocalize.unstashBranchTooltip().get());
        myPanel.add(
            myBranchTextField,
            new GridConstraints(
                4,
                1,
                1,
                2,
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
        JBScrollPane jBScrollPane1 = new JBScrollPane();
        myPanel.add(
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
        myStashList = new JBList<>();
        myStashList.setSelectionMode(0);
        jBScrollPane1.setViewportView(myStashList);
        JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 3, JBUI.emptyInsets(), -1, -1));
        myPanel.add(
            panel2,
            new GridConstraints(
                3,
                1,
                1,
                2,
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
        myPopStashCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(myPopStashCheckBox, GitLocalize.unstashPopStash().get());
        myPopStashCheckBox.setToolTipText(GitLocalize.unstashPopStashTooltip().get());
        panel2.add(
            myPopStashCheckBox,
            new GridConstraints(
                0,
                0,
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
        myReinstateIndexCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(myReinstateIndexCheckBox, GitLocalize.unstashReinstateIndex().get());
        myReinstateIndexCheckBox.setToolTipText(GitLocalize.unstashReinstateIndexTooltip().get());
        panel2.add(
            myReinstateIndexCheckBox,
            new GridConstraints(
                0,
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
        Spacer spacer2 = new Spacer();
        panel2.add(
            spacer2,
            new GridConstraints(
                0,
                2,
                1,
                1,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                1,
                null,
                null,
                null,
                0,
                false
            )
        );
        label1.setLabelFor(myGitRootComboBox);
        label3.setLabelFor(jBScrollPane1);
        label4.setLabelFor(myBranchTextField);
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

    private static class UnstashConflictResolver extends GitConflictResolver {
        private final VirtualFile myRoot;
        private final StashInfo myStashInfo;

        public UnstashConflictResolver(Project project, VirtualFile root, StashInfo stashInfo) {
            super(project, ServiceManager.getService(Git.class), Collections.singleton(root), makeParams(stashInfo));
            myRoot = root;
            myStashInfo = stashInfo;
        }

        private static Params makeParams(StashInfo stashInfo) {
            return new Params()
                .setErrorNotificationTitle(LocalizeValue.localizeTODO("Unstashed with conflicts"))
                .setMergeDialogCustomizer(new UnstashMergeDialogCustomizer(stashInfo));
        }

        @Override
        protected void notifyUnresolvedRemain() {
            NotificationService.getInstance().newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Conflicts were not resolved during unstash"))
                .content(LocalizeValue.localizeTODO(
                    "Unstash is not complete, you have unresolved merges in your working tree<br/>" +
                        "<a href='resolve'>Resolve</a> conflicts."
                ))
                .hyperlinkListener((notification, event) -> {
                    if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && "resolve".equals(event.getDescription())) {
                        new UnstashConflictResolver(myProject, myRoot, myStashInfo).mergeNoProceed();
                    }
                })
                .notify(myProject);
        }
    }

    private static class UnstashMergeDialogCustomizer extends MergeDialogCustomizer {
        private final StashInfo myStashInfo;

        public UnstashMergeDialogCustomizer(StashInfo stashInfo) {
            myStashInfo = stashInfo;
        }

        @Override
        public String getMultipleFileMergeDescription(Collection<VirtualFile> files) {
            return "<html>Conflicts during unstashing <code>" + myStashInfo.getStash() + "\"" + myStashInfo.getMessage() + "\"</code></html>";
        }

        @Override
        public String getLeftPanelTitle(VirtualFile file) {
            return "Local changes";
        }

        @Override
        public String getRightPanelTitle(VirtualFile file, VcsRevisionNumber lastRevisionNumber) {
            return "Changes from stash";
        }
    }
}
