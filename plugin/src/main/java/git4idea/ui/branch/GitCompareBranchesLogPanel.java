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
package git4idea.ui.branch;

import consulo.application.Application;
import consulo.project.Project;
import consulo.ui.ex.awt.JBLabel;
import consulo.ui.ex.awt.Splitter;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.collection.ArrayUtil;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangesBrowser;
import consulo.versionControlSystem.change.ChangesBrowserFactory;
import git4idea.GitCommit;
import git4idea.repo.GitRepository;
import git4idea.ui.GitCommitListPanel;
import git4idea.ui.GitRepositoryComboboxListCellRenderer;
import git4idea.util.GitCommitCompareInfo;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;

/**
 * @author Kirill Likhodedov
 */
class GitCompareBranchesLogPanel extends JPanel {
    @Nonnull
    private final Project myProject;
    @Nonnull
    private final String myBranchName;
    @Nonnull
    private final String myCurrentBranchName;
    @Nonnull
    private final GitCommitCompareInfo myCompareInfo;
    @Nonnull
    private final GitRepository myInitialRepo;

    private GitCommitListPanel myHeadToBranchListPanel;
    private GitCommitListPanel myBranchToHeadListPanel;

    GitCompareBranchesLogPanel(
        @Nonnull Project project,
        @Nonnull String branchName,
        @Nonnull String currentBranchName,
        @Nonnull GitCommitCompareInfo compareInfo,
        @Nonnull GitRepository initialRepo
    ) {
        super(new BorderLayout(UIUtil.DEFAULT_HGAP, UIUtil.DEFAULT_VGAP));
        myProject = project;
        myBranchName = branchName;
        myCurrentBranchName = currentBranchName;
        myCompareInfo = compareInfo;
        myInitialRepo = initialRepo;

        add(createNorthPanel(), BorderLayout.NORTH);
        add(createCenterPanel());
    }

    private JComponent createCenterPanel() {
        ChangesBrowserFactory browserFactory = Application.get().getInstance(ChangesBrowserFactory.class);

        ChangesBrowser changesBrowser = browserFactory.createChangeBrowser(
            myProject,
            null,
            Collections.<Change>emptyList(),
            null,
            false,
            true,
            null,
            ChangesBrowser.MyUseCase.COMMITTED_CHANGES,
            null
        );

        myHeadToBranchListPanel = new GitCommitListPanel(
            getHeadToBranchCommits(myInitialRepo),
            String.format("Branch %s is fully merged to %s", myBranchName, myCurrentBranchName)
        );
        myBranchToHeadListPanel = new GitCommitListPanel(
            getBranchToHeadCommits(myInitialRepo),
            String.format("Branch %s is fully merged to %s", myCurrentBranchName, myBranchName)
        );

        addSelectionListener(myHeadToBranchListPanel, myBranchToHeadListPanel, changesBrowser);
        addSelectionListener(myBranchToHeadListPanel, myHeadToBranchListPanel, changesBrowser);

        myHeadToBranchListPanel.registerDiffAction(changesBrowser.getDiffAction());
        myBranchToHeadListPanel.registerDiffAction(changesBrowser.getDiffAction());

        JPanel htb = layoutCommitListPanel(myCurrentBranchName, true);
        JPanel bth = layoutCommitListPanel(myCurrentBranchName, false);

        JPanel listPanel = switch (getInfoType()) {
            case HEAD_TO_BRANCH -> htb;
            case BRANCH_TO_HEAD -> bth;
            case BOTH -> {
                Splitter lists = new Splitter(true, 0.5f);
                lists.setFirstComponent(htb);
                lists.setSecondComponent(bth);
                yield lists;
            }
        };

        Splitter rootPanel = new Splitter(false, 0.7f);
        rootPanel.setSecondComponent(changesBrowser.getComponent());
        rootPanel.setFirstComponent(listPanel);
        return rootPanel;
    }

    private JComponent createNorthPanel() {
        JComboBox<GitRepository> repoSelector =
            new JComboBox<>(ArrayUtil.toObjectArray(myCompareInfo.getRepositories(), GitRepository.class));
        repoSelector.setRenderer(new GitRepositoryComboboxListCellRenderer(repoSelector));
        repoSelector.setSelectedItem(myInitialRepo);

        repoSelector.addActionListener(e -> {
            GitRepository selectedRepo = (GitRepository)repoSelector.getSelectedItem();
            myHeadToBranchListPanel.setCommits(getHeadToBranchCommits(selectedRepo));
            myBranchToHeadListPanel.setCommits(getBranchToHeadCommits(selectedRepo));
        });

        JPanel repoSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JBLabel label = new JBLabel("Repository: ");
        label.setLabelFor(repoSelectorPanel);
        label.setDisplayedMnemonic(KeyEvent.VK_R);
        repoSelectorPanel.add(label);
        repoSelectorPanel.add(repoSelector);

        if (myCompareInfo.getRepositories().size() < 2) {
            repoSelectorPanel.setVisible(false);
        }
        return repoSelectorPanel;
    }

    private ArrayList<GitCommit> getBranchToHeadCommits(GitRepository selectedRepo) {
        return new ArrayList<>(myCompareInfo.getBranchToHeadCommits(selectedRepo));
    }

    private ArrayList<GitCommit> getHeadToBranchCommits(GitRepository selectedRepo) {
        return new ArrayList<>(myCompareInfo.getHeadToBranchCommits(selectedRepo));
    }

    private GitCommitCompareInfo.InfoType getInfoType() {
        return myCompareInfo.getInfoType();
    }

    private static void addSelectionListener(
        @Nonnull GitCommitListPanel sourcePanel,
        @Nonnull GitCommitListPanel otherPanel,
        @Nonnull ChangesBrowser changesBrowser
    ) {
        sourcePanel.addListMultipleSelectionListener(changes -> {
            changesBrowser.setChangesToDisplay(changes);
            otherPanel.clearSelection();
        });
    }

    private JPanel layoutCommitListPanel(@Nonnull String currentBranch, boolean forward) {
        String desc = makeDescription(currentBranch, forward);

        JPanel bth = new JPanel(new BorderLayout());
        JBLabel descriptionLabel = new JBLabel(desc, UIUtil.ComponentStyle.SMALL);
        descriptionLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        bth.add(descriptionLabel, BorderLayout.NORTH);
        bth.add(forward ? myHeadToBranchListPanel : myBranchToHeadListPanel);
        return bth;
    }

    private String makeDescription(@Nonnull String currentBranch, boolean forward) {
        String firstBranch = forward ? currentBranch : myBranchName;
        String secondBranch = forward ? myBranchName : currentBranch;
        return String.format(
            "<html>Commits that exist in <code><b>%s</b></code> but don't exist in <code><b>%s</b></code>" +
                " (<code>git log %s..%s</code>):</html>",
            secondBranch,
            firstBranch,
            firstBranch,
            secondBranch
        );
    }
}
