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
package git4idea.rebase;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.ComboBox;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.JBUI;
import consulo.ui.ex.awt.event.DocumentAdapter;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.*;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitRebaseParams;
import git4idea.config.GitConfigUtil;
import git4idea.config.GitRebaseSettings;
import git4idea.merge.GitMergeStrategy;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.ui.GitReferenceValidator;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import static consulo.util.lang.ObjectUtil.assertNotNull;
import static consulo.util.lang.StringUtil.isEmptyOrSpaces;

/**
 * The dialog that allows initiating git rebase activity
 */
public class GitRebaseDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(GitRebaseDialog.class);

    @Nonnull
    private final GitRepositoryManager myRepositoryManager;

    /**
     * Git root selector
     */
    protected ComboBox myGitRootComboBox;
    /**
     * The selector for branch to rebase
     */
    protected ComboBox<String> myBranchComboBox;
    /**
     * The from branch combo box. This is used as base branch if different from onto branch
     */
    protected ComboBox<GitReference> myFromComboBox;
    /**
     * The validation button for from branch
     */
    private JButton myFromValidateButton;
    /**
     * The onto branch combobox.
     */
    protected ComboBox<GitReference> myOntoComboBox;
    /**
     * The validate button for onto branch
     */
    private JButton myOntoValidateButton;
    /**
     * Show tags in drop down
     */
    private JCheckBox myShowTagsCheckBox;
    /**
     * Merge strategy drop down
     */
    private ComboBox<GitMergeStrategy> myMergeStrategyComboBox;
    /**
     * If selected, rebase is interactive
     */
    protected JCheckBox myInteractiveCheckBox;
    /**
     * No merges are performed if selected.
     */
    private JCheckBox myDoNotUseMergeCheckBox;
    /**
     * The root panel of the dialog
     */
    private JPanel myPanel;
    /**
     * If selected, remote branches are shown as well
     */
    protected JCheckBox myShowRemoteBranchesCheckBox;
    /**
     * Preserve merges checkbox
     */
    private JCheckBox myPreserveMergesCheckBox;
    /**
     * The current project
     */
    protected final Project myProject;
    /**
     * The list of local branches
     */
    protected final List<GitBranch> myLocalBranches = new ArrayList<>();
    /**
     * The list of remote branches
     */
    protected final List<GitBranch> myRemoteBranches = new ArrayList<>();
    /**
     * The current branch
     */
    @Nullable
    protected GitBranch myCurrentBranch;
    /**
     * The tags
     */
    protected final List<GitTag> myTags = new ArrayList<>();
    /**
     * The validator for onto field
     */
    private final GitReferenceValidator myOntoValidator;
    /**
     * The validator for from field
     */
    private final GitReferenceValidator myFromValidator;
    @Nonnull
    private final GitRebaseSettings mySettings;

    @Nullable
    private final String myOriginalOntoBranch;

    /**
     * A constructor
     *
     * @param project     a project to select
     * @param roots       a git repository roots for the project
     * @param defaultRoot a guessed default root
     */
    public GitRebaseDialog(Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
        super(project, true);
        setTitle(GitLocalize.rebaseTitle());
        setOKButtonText(GitLocalize.rebaseButton());
        init();
        myProject = project;
        mySettings = ServiceManager.getService(myProject, GitRebaseSettings.class);
        myRepositoryManager = GitUtil.getRepositoryManager(myProject);
        Runnable validateRunnable = this::validateFields;
        myOntoValidator = new GitReferenceValidator(
            myProject,
            myGitRootComboBox,
            GitUIUtil.getTextField(myOntoComboBox),
            myOntoValidateButton,
            validateRunnable
        );
        myFromValidator = new GitReferenceValidator(
            myProject,
            myGitRootComboBox,
            GitUIUtil.getTextField(myFromComboBox),
            myFromValidateButton,
            validateRunnable
        );
        GitUIUtil.setupRootChooser(myProject, roots, defaultRoot, myGitRootComboBox, null);
        myGitRootComboBox.addActionListener(e -> validateFields());

        setupBranches();
        setupStrategy();

        myInteractiveCheckBox.setSelected(mySettings.isInteractive());
        myPreserveMergesCheckBox.setSelected(mySettings.isPreserveMerges());
        myShowTagsCheckBox.setSelected(mySettings.showTags());
        myShowRemoteBranchesCheckBox.setSelected(mySettings.showRemoteBranches());
        overwriteOntoForCurrentBranch(mySettings);

        myOriginalOntoBranch = GitUIUtil.getTextField(myOntoComboBox).getText();

        validateFields();
    }

    @Nullable
    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myOntoComboBox;
    }

    private void overwriteOntoForCurrentBranch(@Nonnull GitRebaseSettings settings) {
        String onto = settings.getOnto();
        if (onto != null && !onto.equals(myBranchComboBox.getSelectedItem())) {
            if (!isValidRevision(onto)) {
                mySettings.setOnto(null);
            }
            else {
                myOntoComboBox.setSelectedItem(onto);
            }
        }
    }

    private boolean isValidRevision(@Nonnull String revisionExpression) {
        try {
            GitRevisionNumber.resolve(myProject, gitRoot(), revisionExpression);
            return true;
        }
        catch (VcsException e) {
            LOG.debug(e);
            return false;
        }
    }

    @Override
    protected void doOKAction() {
        try {
            rememberFields();
        }
        finally {
            super.doOKAction();
        }
    }

    private void rememberFields() {
        mySettings.setInteractive(myInteractiveCheckBox.isSelected());
        mySettings.setPreserveMerges(myPreserveMergesCheckBox.isSelected());
        mySettings.setShowTags(myShowTagsCheckBox.isSelected());
        mySettings.setShowRemoteBranches(myShowRemoteBranchesCheckBox.isSelected());
        String onto = StringUtil.nullize(GitUIUtil.getTextField(myOntoComboBox).getText(), true);
        if (onto != null && !onto.equals(myOriginalOntoBranch)) {
            mySettings.setOnto(onto);
        }
    }

    /**
     * Setup strategy
     */
    private void setupStrategy() {
        myMergeStrategyComboBox.setRenderer(GitMergeStrategy.LIST_CELL_RENDERER);
        for (GitMergeStrategy s : GitMergeStrategy.getMergeStrategies(1)) {
            myMergeStrategyComboBox.addItem(s);
        }
        myMergeStrategyComboBox.setSelectedItem(GitMergeStrategy.DEFAULT);
        myDoNotUseMergeCheckBox.addActionListener(e -> myMergeStrategyComboBox.setEnabled(!myDoNotUseMergeCheckBox.isSelected()));
    }


    /**
     * Validate fields
     */
    private void validateFields() {
        if (GitUIUtil.getTextField(myOntoComboBox).getText().length() == 0) {
            clearErrorText();
            setOKActionEnabled(false);
            return;
        }
        else if (myOntoValidator.isInvalid()) {
            setErrorText(GitLocalize.rebaseInvalidOnto());
            setOKActionEnabled(false);
            return;
        }
        if (GitUIUtil.getTextField(myFromComboBox).getText().length() != 0 && myFromValidator.isInvalid()) {
            setErrorText(GitLocalize.rebaseInvalidFrom());
            setOKActionEnabled(false);
            return;
        }
        if (GitRebaseUtils.isRebaseInTheProgress(myProject, gitRoot())) {
            setErrorText(GitLocalize.rebaseInProgress());
            setOKActionEnabled(false);
            return;
        }
        clearErrorText();
        setOKActionEnabled(true);
    }

    /**
     * Setup branch drop down.
     */
    private void setupBranches() {
        GitUIUtil.getTextField(myOntoComboBox).getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                validateFields();
            }
        });
        @RequiredUIAccess
        ActionListener rootListener = e -> {
            loadRefs();
            updateBranches();
        };
        ActionListener showListener = e -> updateOntoFrom();
        myShowRemoteBranchesCheckBox.addActionListener(showListener);
        myShowTagsCheckBox.addActionListener(showListener);
        rootListener.actionPerformed(null);
        myGitRootComboBox.addActionListener(rootListener);
        myBranchComboBox.addActionListener(e -> updateTrackedBranch());
    }

    /**
     * Update branches when git root changed
     */
    @RequiredUIAccess
    private void updateBranches() {
        myBranchComboBox.removeAllItems();
        for (GitBranch b : myLocalBranches) {
            myBranchComboBox.addItem(b.getName());
        }
        if (myCurrentBranch != null) {
            myBranchComboBox.setSelectedItem(myCurrentBranch.getName());
        }
        else {
            myBranchComboBox.setSelectedItem(0);
        }
        updateOntoFrom();
        updateTrackedBranch();
    }

    /**
     * Update onto and from combo-boxes.
     */
    protected void updateOntoFrom() {
        String onto = GitUIUtil.getTextField(myOntoComboBox).getText();
        String from = GitUIUtil.getTextField(myFromComboBox).getText();
        myFromComboBox.removeAllItems();
        myOntoComboBox.removeAllItems();
        for (GitBranch b : myLocalBranches) {
            myFromComboBox.addItem(b);
            myOntoComboBox.addItem(b);
        }
        if (myShowRemoteBranchesCheckBox.isSelected()) {
            for (GitBranch b : myRemoteBranches) {
                myFromComboBox.addItem(b);
                myOntoComboBox.addItem(b);
            }
        }
        if (myShowTagsCheckBox.isSelected()) {
            for (GitTag t : myTags) {
                myFromComboBox.addItem(t);
                myOntoComboBox.addItem(t);
            }
        }
        GitUIUtil.getTextField(myOntoComboBox).setText(onto);
        GitUIUtil.getTextField(myFromComboBox).setText(from);
    }

    /**
     * Load tags and branches
     */
    @RequiredUIAccess
    protected void loadRefs() {
        try {
            myLocalBranches.clear();
            myRemoteBranches.clear();
            myTags.clear();
            VirtualFile root = gitRoot();
            GitRepository repository = GitUtil.getRepositoryManager(myProject).getRepositoryForRoot(root);
            if (repository != null) {
                myLocalBranches.addAll(repository.getBranches().getLocalBranches());
                myRemoteBranches.addAll(repository.getBranches().getRemoteBranches());
                myCurrentBranch = repository.getCurrentBranch();
            }
            else {
                LOG.error("Repository is null for root " + root);
            }
            GitTag.list(myProject, root, myTags);
        }
        catch (VcsException e) {
            GitUIUtil.showOperationError(myProject, e, "git branch -a");
        }
    }

    /**
     * Update tracked branch basing on the currently selected branch
     */
    @RequiredUIAccess
    private void updateTrackedBranch() {
        try {
            VirtualFile root = gitRoot();
            String currentBranch = (String) myBranchComboBox.getSelectedItem();
            GitBranch trackedBranch = null;
            if (currentBranch != null) {
                String remote = GitConfigUtil.getValue(myProject, root, "branch." + currentBranch + ".remote");
                String mergeBranch = GitConfigUtil.getValue(myProject, root, "branch." + currentBranch + ".merge");
                if (remote != null && mergeBranch != null) {
                    mergeBranch = GitBranchUtil.stripRefsPrefix(mergeBranch);
                    if (remote.equals(".")) {
                        trackedBranch = new GitSvnRemoteBranch(mergeBranch);
                    }
                    else {
                        GitRemote r = GitBranchUtil.findRemoteByNameOrLogError(myProject, root, remote);
                        if (r != null) {
                            trackedBranch = new GitStandardRemoteBranch(r, mergeBranch);
                        }
                    }
                }
            }
            if (trackedBranch != null) {
                myOntoComboBox.setSelectedItem(trackedBranch);
            }
            else {
                GitUIUtil.getTextField(myOntoComboBox).setText("");
            }
            GitUIUtil.getTextField(myFromComboBox).setText("");
        }
        catch (VcsException e) {
            GitUIUtil.showOperationError(myProject, e, "git config");
        }
    }

    /**
     * @return the currently selected git root
     */
    public VirtualFile gitRoot() {
        return (VirtualFile) myGitRootComboBox.getSelectedItem();
    }

    @Nonnull
    public GitRepository getSelectedRepository() {
        return assertNotNull(myRepositoryManager.getRepositoryForRoot(gitRoot()));
    }

    @Nonnull
    public GitRebaseParams getSelectedParams() {
        String selectedBranch = (String) myBranchComboBox.getSelectedItem();
        String branch = myCurrentBranch != null && !myCurrentBranch.getName().equals(selectedBranch) ? selectedBranch : null;

        String from = GitUIUtil.getTextField(myFromComboBox).getText();
        String onto = GitUIUtil.getTextField(myOntoComboBox).getText();
        String upstream;
        String newBase;
        if (isEmptyOrSpaces(from)) {
            upstream = onto;
            newBase = null;
        }
        else {
            upstream = from;
            newBase = onto;
        }

        return new GitRebaseParams(branch, newBase, upstream, myInteractiveCheckBox.isSelected(), myPreserveMergesCheckBox.isSelected());
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
    protected JComponent createCenterPanel() {
        return myPanel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getHelpId() {
        return "reference.VersionControl.Git.Rebase";
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
        myPanel.setLayout(new GridLayoutManager(8, 3, JBUI.emptyInsets(), -1, -1));
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
        Spacer spacer1 = new Spacer();
        myPanel.add(
            spacer1,
            new GridConstraints(
                7,
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
        myGitRootComboBox = new ComboBox();
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
        this.$$$loadLabelText$$$(label2, GitLocalize.rebaseBranch().get());
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
        myBranchComboBox = new ComboBox<>();
        myBranchComboBox.setToolTipText(GitLocalize.rebaseBranchTooltip().get());
        myPanel.add(
            myBranchComboBox,
            new GridConstraints(
                1,
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
        JLabel label3 = new JLabel();
        this.$$$loadLabelText$$$(label3, GitLocalize.rebaseOnto().get());
        myPanel.add(
            label3,
            new GridConstraints(
                3,
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
        myOntoComboBox = new ComboBox<>();
        myOntoComboBox.setEditable(true);
        myOntoComboBox.setToolTipText(GitLocalize.rebaseOntoTooltip().get());
        myPanel.add(
            myOntoComboBox,
            new GridConstraints(
                3,
                1,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
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
        myOntoValidateButton = new JButton();
        this.$$$loadButtonText$$$(myOntoValidateButton, GitLocalize.rebaseOntoValidate().get());
        myOntoValidateButton.setToolTipText(GitLocalize.rebaseValdateOntoTooltip().get());
        myPanel.add(
            myOntoValidateButton,
            new GridConstraints(
                3,
                2,
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
        this.$$$loadLabelText$$$(label4, GitLocalize.rebaseMergeStrategy().get());
        myPanel.add(
            label4,
            new GridConstraints(
                6,
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
        JLabel label5 = new JLabel();
        this.$$$loadLabelText$$$(label5, GitLocalize.rebaseFrom().get());
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
        myFromComboBox = new ComboBox<>();
        myFromComboBox.setEditable(true);
        myFromComboBox.setToolTipText(GitLocalize.rebaseFromTooltip().get());
        myPanel.add(
            myFromComboBox,
            new GridConstraints(
                4,
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
        myFromValidateButton = new JButton();
        this.$$$loadButtonText$$$(myFromValidateButton, GitLocalize.rebaseValidateFrom().get());
        myFromValidateButton.setToolTipText(GitLocalize.rebaseValidateFromTooltip().get());
        myPanel.add(
            myFromValidateButton,
            new GridConstraints(
                4,
                2,
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
        JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(2, 2, JBUI.emptyInsets(), -1, -1));
        myPanel.add(
            panel1,
            new GridConstraints(
                6,
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
        myMergeStrategyComboBox = new ComboBox<>();
        myMergeStrategyComboBox.setToolTipText(GitLocalize.rebaseMergeStrategyTooltip().get());
        panel1.add(
            myMergeStrategyComboBox,
            new GridConstraints(
                0,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        Spacer spacer2 = new Spacer();
        panel1.add(
            spacer2,
            new GridConstraints(
                0,
                1,
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
        myDoNotUseMergeCheckBox = new JCheckBox();
        myDoNotUseMergeCheckBox.setSelected(false);
        this.$$$loadButtonText$$$(myDoNotUseMergeCheckBox, GitLocalize.rebaseNoMerge().get());
        myDoNotUseMergeCheckBox.setToolTipText(GitLocalize.rebaseNoMergeTooltip().get());
        panel1.add(
            myDoNotUseMergeCheckBox,
            new GridConstraints(
                1,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                1,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 3, JBUI.emptyInsets(), -1, -1));
        myPanel.add(
            panel2,
            new GridConstraints(
                2,
                1,
                1,
                2,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null,
                null,
                null,
                0,
                false
            )
        );
        Spacer spacer3 = new Spacer();
        panel2.add(
            spacer3,
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
        myPreserveMergesCheckBox = new JCheckBox();
        myPreserveMergesCheckBox.setSelected(false);
        this.$$$loadButtonText$$$(myPreserveMergesCheckBox, GitLocalize.rebasePreserveMerges().get());
        myPreserveMergesCheckBox.setToolTipText(GitLocalize.rebasePreserveMergesTooltip().get());
        panel2.add(
            myPreserveMergesCheckBox,
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
        myInteractiveCheckBox = new JCheckBox();
        myInteractiveCheckBox.setEnabled(true);
        myInteractiveCheckBox.setSelected(true);
        this.$$$loadButtonText$$$(myInteractiveCheckBox, GitLocalize.rebaseInteractive().get());
        myInteractiveCheckBox.setToolTipText(GitLocalize.rebaseInteractiveTooltip().get());
        panel2.add(
            myInteractiveCheckBox,
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
        JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 3, JBUI.emptyInsets(), -1, -1));
        myPanel.add(
            panel3,
            new GridConstraints(
                5,
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
        myShowTagsCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(myShowTagsCheckBox, GitLocalize.regaseShowTags().get());
        myShowTagsCheckBox.setToolTipText(GitLocalize.rebaseShowTagsTooltip().get());
        panel3.add(
            myShowTagsCheckBox,
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
        myShowRemoteBranchesCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(myShowRemoteBranchesCheckBox, GitLocalize.rebaseShowRemoteBranches().get());
        myShowRemoteBranchesCheckBox.setToolTipText(GitLocalize.rebaseShowRemoteBranchesTooltip().get());
        panel3.add(
            myShowRemoteBranchesCheckBox,
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
        Spacer spacer4 = new Spacer();
        panel3.add(
            spacer4,
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
        label2.setLabelFor(myBranchComboBox);
        label3.setLabelFor(myOntoComboBox);
        label4.setLabelFor(myMergeStrategyComboBox);
        label5.setLabelFor(myFromComboBox);
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
