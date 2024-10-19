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

import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.ComboBox;
import consulo.ui.ex.awt.DialogWrapper;
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
        final Runnable validateRunnable = this::validateFields;
        myOntoValidator = new GitReferenceValidator(myProject,
            myGitRootComboBox,
            GitUIUtil.getTextField(myOntoComboBox),
            myOntoValidateButton,
            validateRunnable
        );
        myFromValidator = new GitReferenceValidator(myProject,
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
            setErrorText(null);
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
        setErrorText(null);
        setOKActionEnabled(true);
    }

    /**
     * Setup branch drop down.
     */
    private void setupBranches() {
        GitUIUtil.getTextField(myOntoComboBox).getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(final DocumentEvent e) {
                validateFields();
            }
        });
        final ActionListener rootListener = e -> {
            loadRefs();
            updateBranches();
        };
        final ActionListener showListener = e -> updateOntoFrom();
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
     * Update onto and from comboboxes.
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
            final VirtualFile root = gitRoot();
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
            final VirtualFile root = gitRoot();
            String currentBranch = (String)myBranchComboBox.getSelectedItem();
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
        return (VirtualFile)myGitRootComboBox.getSelectedItem();
    }

    @Nonnull
    public GitRepository getSelectedRepository() {
        return assertNotNull(myRepositoryManager.getRepositoryForRoot(gitRoot()));
    }

    @Nonnull
    public GitRebaseParams getSelectedParams() {
        String selectedBranch = (String)myBranchComboBox.getSelectedItem();
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
}
