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

import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.ide.impl.idea.openapi.vfs.VfsUtil;
import consulo.logging.Logger;
import consulo.platform.base.localize.CommonLocalize;
import consulo.process.cmd.GeneralCommandLine;
import consulo.project.Project;
import consulo.ui.ModalityState;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.ui.ex.awt.event.DocumentAdapter;
import consulo.util.dataholder.Key;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.merge.MergeDialogCustomizer;
import consulo.virtualFileSystem.VirtualFile;
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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
    private JList myStashList;
    /**
     * If this checkbox is selected, the index is reinstated as well as working tree
     */
    private JCheckBox myReinstateIndexCheckBox;
    /**
     * Set of branches for the current root
     */
    private final HashSet<String> myBranches = new HashSet<String>();

    /**
     * The project
     */
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
    public GitUnstashDialog(final Project project, final List<VirtualFile> roots, final VirtualFile defaultRoot) {
        super(project, true);
        setModal(false);
        myProject = project;
        myVcs = GitVcs.getInstance(project);
        setTitle(GitLocalize.unstashTitle());
        setOKButtonText(GitLocalize.unstashButtonApply().get());
        setCancelButtonText(CommonLocalize.buttonClose().get());
        GitUIUtil.setupRootChooser(project, roots, defaultRoot, myGitRootComboBox, myCurrentBranch);
        myStashList.setModel(new DefaultListModel());
        refreshStashList();
        myGitRootComboBox.addActionListener(e -> {
            refreshStashList();
            updateDialogState();
        });
        myStashList.addListSelectionListener(e -> updateDialogState());
        myBranchTextField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(final DocumentEvent e) {
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
                GitHandlerUtil.doSynchronously(h, GitLocalize.unstashClearingStashes(), h.printableCommandLine());
                refreshStashList();
                updateDialogState();
            }
        });
        myDropButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                final StashInfo stash = getSelectedStash();
                if (Messages.YES == Messages.showYesNoDialog(
                    GitUnstashDialog.this.getContentPane(),
                    GitLocalize.gitUnstashDropConfirmationMessage(stash.getStash(), stash.getMessage()).get(),
                    GitLocalize.gitUnstashDropConfirmationTitle(stash.getStash()).get(),
                    UIUtil.getQuestionIcon()
                )) {
                    final ModalityState current = Application.get().getCurrentModalityState();
                    ProgressManager.getInstance().run(new Task.Modal(myProject, "Removing stash " + stash.getStash(), false) {
                        @Override
                        public void run(@Nonnull ProgressIndicator indicator) {
                            final GitSimpleHandler h = dropHandler(stash.getStash());
                            try {
                                h.run();
                                h.unsilence();
                            }
                            catch (final VcsException ex) {
                                project.getApplication().invokeLater(
                                    () -> GitUIUtil.showOperationError((Project)myProject, ex, h.printableCommandLine()),
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
            final VirtualFile root = getGitRoot();
            String resolvedStash;
            String selectedStash = getSelectedStash().getStash();
            try {
                GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.REV_LIST);
                h.setSilent(true);
                h.addParameters("--timestamp", "--max-count=1");
                addStashParameter(h, selectedStash);
                h.endOptions();
                final String output = h.run();
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
            setOKButtonText(GitLocalize.unstashButtonBranch().get());
            myPopStashCheckBox.setEnabled(false);
            myPopStashCheckBox.setSelected(true);
            myReinstateIndexCheckBox.setEnabled(false);
            myReinstateIndexCheckBox.setSelected(true);
            if (!GitBranchNameValidator.INSTANCE.checkInput(branch)) {
                setErrorText(GitLocalize.unstashErrorInvalidBranchName().get());
                setOKActionEnabled(false);
                return;
            }
            if (myBranches.contains(branch)) {
                setErrorText(GitLocalize.unstashErrorBranchExists().get());
                setOKActionEnabled(false);
                return;
            }
        }
        else {
            if (!myPopStashCheckBox.isEnabled()) {
                myPopStashCheckBox.setSelected(false);
            }
            myPopStashCheckBox.setEnabled(true);
            setOKButtonText(
                myPopStashCheckBox.isSelected()
                    ? GitLocalize.unstashButtonPop().get()
                    : GitLocalize.unstashButtonApply().get()
            );
            if (!myReinstateIndexCheckBox.isEnabled()) {
                myReinstateIndexCheckBox.setSelected(false);
            }
            myReinstateIndexCheckBox.setEnabled(true);
        }
        if (myStashList.getModel().getSize() == 0) {
            myViewButton.setEnabled(false);
            myDropButton.setEnabled(false);
            myClearButton.setEnabled(false);
            setErrorText(null);
            setOKActionEnabled(false);
            return;
        }
        else {
            myClearButton.setEnabled(true);
        }
        if (myStashList.getSelectedIndex() == -1) {
            myViewButton.setEnabled(false);
            myDropButton.setEnabled(false);
            setErrorText(null);
            setOKActionEnabled(false);
            return;
        }
        else {
            myViewButton.setEnabled(true);
            myDropButton.setEnabled(true);
        }
        setErrorText(null);
        setOKActionEnabled(true);
    }

    /**
     * Refresh stash list
     */
    private void refreshStashList() {
        final DefaultListModel listModel = (DefaultListModel)myStashList.getModel();
        listModel.clear();
        VirtualFile root = getGitRoot();
        GitStashUtils.loadStashStack(myProject, root, stashInfo -> listModel.addElement(stashInfo));
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
        return (VirtualFile)myGitRootComboBox.getSelectedItem();
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
        return (StashInfo)myStashList.getSelectedValue();
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
        int rc = GitHandlerUtil.doSynchronously(h, GitLocalize.unstashUnstashing(), h.printableCommandLine(), false);

        VfsUtil.markDirtyAndRefresh(true, true, false, root);

        if (conflict.get()) {
            boolean conflictsResolved = new UnstashConflictResolver(myProject, root, getSelectedStash()).merge();
            LOG.info("loadRoot " + root + ", conflictsResolved: " + conflictsResolved);
        }
        else if (rc != 0) {
            GitUIUtil.showOperationErrors(myProject, h.errors(), h.printableCommandLine());
        }
        super.doOKAction();
    }

    @RequiredUIAccess
    public static void showUnstashDialog(Project project, List<VirtualFile> gitRoots, VirtualFile defaultRoot) {
        new GitUnstashDialog(project, gitRoots, defaultRoot).show();
        // d is not modal=> everything else in doOKAction.
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
            Params params = new Params();
            params.setErrorNotificationTitle("Unstashed with conflicts");
            params.setMergeDialogCustomizer(new UnstashMergeDialogCustomizer(stashInfo));
            return params;
        }

        @Override
        protected void notifyUnresolvedRemain() {
            VcsNotifier.getInstance(myProject)
                .notifyImportantWarning(
                    "Conflicts were not resolved during unstash",
                    "Unstash is not complete, you have unresolved merges in your working tree<br/>" +
                        "<a href='resolve'>Resolve</a> conflicts.",
                    (notification, event) -> {
                        if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                            if (event.getDescription().equals("resolve")) {
                                new UnstashConflictResolver(myProject, myRoot, myStashInfo).mergeNoProceed();
                            }
                        }
                    }
                );
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
