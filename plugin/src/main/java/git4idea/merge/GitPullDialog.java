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
package git4idea.merge;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.ElementsChooser;
import consulo.ui.ex.awt.JBUI;
import consulo.ui.ex.awt.ListCellRendererWrapper;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitBranch;
import git4idea.GitRemoteBranch;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Git pull dialog
 */
public class GitPullDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(GitPullDialog.class);

    /**
     * root panel
     */
    private JPanel myPanel;
    /**
     * The selected git root
     */
    private JComboBox myGitRoot;
    /**
     * Current branch label
     */
    private JLabel myCurrentBranch;
    /**
     * The merge strategy
     */
    private JComboBox<GitMergeStrategy> myStrategy;
    /**
     * No commit option
     */
    private JCheckBox myNoCommitCheckBox;
    /**
     * Squash commit option
     */
    private JCheckBox mySquashCommitCheckBox;
    /**
     * No fast forward option
     */
    private JCheckBox myNoFastForwardCheckBox;
    /**
     * Add log info to commit option
     */
    private JCheckBox myAddLogInformationCheckBox;
    /**
     * Selected remote option
     */
    private JComboBox<GitRemote> myRemote;
    /**
     * The branch chooser
     */
    private ElementsChooser<String> myBranchChooser;
    /**
     * The context project
     */
    private final Project myProject;
    private final GitRepositoryManager myRepositoryManager;

    /**
     * A constructor
     *
     * @param project     a project to select
     * @param roots       a git repository roots for the project
     * @param defaultRoot a guessed default root
     */
    public GitPullDialog(Project project, List<VirtualFile> roots, VirtualFile defaultRoot) {
        super(project, true);
        $$$setupUI$$$();
        setTitle(GitLocalize.pullTitle());
        myProject = project;
        myRepositoryManager = GitUtil.getRepositoryManager(myProject);
        GitUIUtil.setupRootChooser(myProject, roots, defaultRoot, myGitRoot, myCurrentBranch);
        myGitRoot.addActionListener(e -> updateRemotes());
        setOKButtonText(GitLocalize.pullButton());
        updateRemotes();
        updateBranches();
        myRemote.addActionListener(e -> updateBranches());
        ElementsChooser.ElementsMarkListener<String> listener = (element, isMarked) -> validateDialog();
        myBranchChooser.addElementsMarkListener(listener);
        listener.elementMarkChanged(null, true);
        GitUIUtil.imply(mySquashCommitCheckBox, true, myNoCommitCheckBox, true);
        GitUIUtil.imply(mySquashCommitCheckBox, true, myAddLogInformationCheckBox, false);
        GitUIUtil.exclusive(mySquashCommitCheckBox, true, myNoFastForwardCheckBox, true);
        myStrategy.setRenderer(GitMergeStrategy.LIST_CELL_RENDERER);
        GitMergeUtil.setupStrategies(myBranchChooser, myStrategy);
        init();
    }

    /**
     * Validate dialog and enable buttons
     */
    private void validateDialog() {
        String selectedRemote = getRemote();
        if (StringUtil.isEmptyOrSpaces(selectedRemote)) {
            setOKActionEnabled(false);
            return;
        }
        setOKActionEnabled(myBranchChooser.getMarkedElements().size() != 0);
    }

    /**
     * @return a pull handler configured according to dialog options
     */
    public GitLineHandler makeHandler(@Nonnull String url, @Nullable String puttyKeyFile) {
        GitLineHandler h = new GitLineHandler(myProject, gitRoot(), GitCommand.PULL);
        // ignore merge failure for the pull
        h.ignoreErrorCode(1);
        h.setUrl(url);
        h.setPuttyKey(puttyKeyFile);
        h.addProgressParameter();
        h.addParameters("--no-stat");
        if (myNoCommitCheckBox.isSelected()) {
            h.addParameters("--no-commit");
        }
        else {
            if (myAddLogInformationCheckBox.isSelected()) {
                h.addParameters("--log");
            }
        }
        if (mySquashCommitCheckBox.isSelected()) {
            h.addParameters("--squash");
        }
        if (myNoFastForwardCheckBox.isSelected()) {
            h.addParameters("--no-ff");
        }
        GitMergeStrategy strategy = (GitMergeStrategy) myStrategy.getSelectedItem();
        strategy.addParametersTo(h);
        h.addParameters("-v");
        h.addProgressParameter();

        List<String> markedBranches = myBranchChooser.getMarkedElements();
        String remote = getRemote();
        LOG.assertTrue(remote != null, "Selected remote can't be null here.");
        // git pull origin master (remote branch name in the format local to that remote)
        h.addParameters(remote);
        for (String branch : markedBranches) {
            h.addParameters(removeRemotePrefix(branch, remote));
        }
        return h;
    }

    @Nonnull
    private static String removeRemotePrefix(@Nonnull String branch, @Nonnull String remote) {
        String prefix = remote + "/";
        if (branch.startsWith(prefix)) {
            return branch.substring(prefix.length());
        }
        LOG.error(String.format("Remote branch name seems to be invalid. Branch: %s, remote: %s", branch, remote));
        return branch;
    }

    private void updateBranches() {
        String selectedRemote = getRemote();
        myBranchChooser.removeAllElements();

        if (selectedRemote == null) {
            return;
        }

        GitRepository repository = getRepository();
        if (repository == null) {
            return;
        }

        GitBranchTrackInfo trackInfo = GitUtil.getTrackInfoForCurrentBranch(repository);
        String currentRemoteBranch = trackInfo == null ? null : trackInfo.remoteBranch().getNameForLocalOperations();
        List<GitRemoteBranch> remoteBranches = new ArrayList<>(repository.getBranches().getRemoteBranches());
        Collections.sort(remoteBranches);
        for (GitBranch remoteBranch : remoteBranches) {
            if (belongsToRemote(remoteBranch, selectedRemote)) {
                myBranchChooser.addElement(remoteBranch.getName(), remoteBranch.getName().equals(currentRemoteBranch));
            }
        }

        validateDialog();
    }

    private static boolean belongsToRemote(@Nonnull GitBranch branch, @Nonnull String remote) {
        return branch.getName().startsWith(remote + "/");
    }

    /**
     * Update remotes for the git root
     */
    private void updateRemotes() {
        GitRepository repository = getRepository();
        if (repository == null) {
            return;
        }

        GitRemote currentRemote = getCurrentOrDefaultRemote(repository);
        myRemote.setRenderer(getGitRemoteListCellRenderer(currentRemote != null ? currentRemote.getName() : null));
        myRemote.removeAllItems();
        for (GitRemote remote : repository.getRemotes()) {
            myRemote.addItem(remote);
        }
        myRemote.setSelectedItem(currentRemote);
    }

    /**
     * If the current branch is a tracking branch, returns its remote.
     * Otherwise tries to guess: if there is origin, returns origin, otherwise returns the first remote in the list.
     */
    @Nullable
    private static GitRemote getCurrentOrDefaultRemote(@Nonnull GitRepository repository) {
        Collection<GitRemote> remotes = repository.getRemotes();
        if (remotes.isEmpty()) {
            return null;
        }

        GitBranchTrackInfo trackInfo = GitUtil.getTrackInfoForCurrentBranch(repository);
        if (trackInfo != null) {
            return trackInfo.getRemote();
        }
        else {
            GitRemote origin = getOriginRemote(remotes);
            return origin != null ? origin : remotes.iterator().next();
        }
    }

    @Nullable
    private static GitRemote getOriginRemote(@Nonnull Collection<GitRemote> remotes) {
        for (GitRemote remote : remotes) {
            if (remote.getName().equals(GitRemote.ORIGIN_NAME)) {
                return remote;
            }
        }
        return null;
    }

    @Nullable
    private GitRepository getRepository() {
        VirtualFile root = gitRoot();
        GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
        if (repository == null) {
            LOG.error("Repository is null for " + root);
            return null;
        }
        return repository;
    }

    /**
     * Create list cell renderer for remotes. It shows both name and url and highlights the default
     * remote for the branch with bold.
     *
     * @param defaultRemote a default remote
     * @return a list cell renderer for virtual files (it renders presentable URL
     */
    public ListCellRendererWrapper<GitRemote> getGitRemoteListCellRenderer(final String defaultRemote) {
        return new ListCellRendererWrapper<>() {
            @Override
            public void customize(
                JList list,
                GitRemote remote,
                int index,
                boolean selected,
                boolean hasFocus
            ) {
                LocalizeValue text;
                if (remote == null) {
                    text = GitLocalize.utilRemoteRendererNone();
                }
                else if (".".equals(remote.getName())) {
                    text = GitLocalize.utilRemoteRendererSelf();
                }
                else if (defaultRemote != null && defaultRemote.equals(remote.getName())) {
                    text = GitLocalize.utilRemoteRendererDefault(remote.getName(), remote.getFirstUrl());
                }
                else {
                    text = GitLocalize.utilRemoteRendererNormal(remote.getName(), remote.getFirstUrl());
                }
                setText(text.get());
            }
        };
    }

    /**
     * @return a currently selected git root
     */
    public VirtualFile gitRoot() {
        return (VirtualFile) myGitRoot.getSelectedItem();
    }


    /**
     * Create branch chooser
     */
    private void createUIComponents() {
        myBranchChooser = new ElementsChooser<>(true);
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
        return "reference.VersionControl.Git.Pull";
    }

    @Nullable
    public String getRemote() {
        GitRemote remote = (GitRemote) myRemote.getSelectedItem();
        return remote == null ? null : remote.getName();
    }

    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myBranchChooser.getComponent();
    }

    /**
     * Method generated by Consulo GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        myPanel = new JPanel();
        myPanel.setLayout(new GridLayoutManager(6, 3, JBUI.emptyInsets(), -1, -1));
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
        myGitRoot = new JComboBox();
        myGitRoot.setToolTipText(GitLocalize.commonGitRootTooltip().get());
        myPanel.add(
            myGitRoot,
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
        JLabel label3 = new JLabel();
        this.$$$loadLabelText$$$(label3, GitLocalize.pullRemote().get());
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
        JLabel label4 = new JLabel();
        this.$$$loadLabelText$$$(label4, GitLocalize.mergeBranches().get());
        myPanel.add(
            label4,
            new GridConstraints(
                3,
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
        this.$$$loadLabelText$$$(label5, GitLocalize.mergeStrategy().get());
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
        Spacer spacer1 = new Spacer();
        myPanel.add(
            spacer1,
            new GridConstraints(
                4,
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
        myStrategy = new JComboBox<>();
        myStrategy.setToolTipText(GitLocalize.mergeStrategy().get());
        myPanel.add(
            myStrategy,
            new GridConstraints(
                4,
                1,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
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
        JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(2, 3, JBUI.emptyInsets(), -1, -1));
        myPanel.add(
            panel1,
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
        myNoCommitCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(myNoCommitCheckBox, GitLocalize.mergeNoCommit().get());
        myNoCommitCheckBox.setToolTipText(GitLocalize.mergeNoCommitTooltip().get());
        panel1.add(
            myNoCommitCheckBox,
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
        mySquashCommitCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(mySquashCommitCheckBox, GitLocalize.mergeSquashCommit().get());
        mySquashCommitCheckBox.setToolTipText(GitLocalize.mergeSquashTooltip().get());
        panel1.add(
            mySquashCommitCheckBox,
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
        myNoFastForwardCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(myNoFastForwardCheckBox, GitLocalize.mergeNoFastForward().get());
        myNoFastForwardCheckBox.setToolTipText(GitLocalize.mergeNoFastForwardTooltip().get());
        panel1.add(
            myNoFastForwardCheckBox,
            new GridConstraints(
                1,
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
        myAddLogInformationCheckBox = new JCheckBox();
        this.$$$loadButtonText$$$(myAddLogInformationCheckBox, GitLocalize.mergeAddLogInformation().get());
        myAddLogInformationCheckBox.setToolTipText(GitLocalize.mergeAddLogInformationTooltip().get());
        panel1.add(
            myAddLogInformationCheckBox,
            new GridConstraints(
                1,
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
        panel1.add(
            spacer2,
            new GridConstraints(
                1,
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
        JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 1, JBUI.emptyInsets(), -1, -1));
        myPanel.add(
            panel2,
            new GridConstraints(
                2,
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
        myRemote = new JComboBox<>();
        myRemote.setEditable(false);
        myRemote.setToolTipText(GitLocalize.pullRemoteTooltip().get());
        panel2.add(
            myRemote,
            new GridConstraints(
                0,
                0,
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
        myPanel.add(
            myBranchChooser,
            new GridConstraints(
                3,
                1,
                1,
                2,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null,
                null,
                null,
                0,
                false
            )
        );
        label1.setLabelFor(myGitRoot);
        label3.setLabelFor(myRemote);
        label5.setLabelFor(myStrategy);
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
