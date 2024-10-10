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

import consulo.git.localize.GitLocalize;
import consulo.localHistory.Label;
import consulo.localHistory.LocalHistory;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.ex.awt.ElementsChooser;
import consulo.ui.ex.awt.UIUtil;
import consulo.versionControlSystem.AbstractVcsHelper;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.ui.UpdateInfoTree;
import consulo.versionControlSystem.ui.ViewUpdateInfoNotification;
import consulo.versionControlSystem.update.ActionInfo;
import consulo.versionControlSystem.update.FileGroup;
import consulo.versionControlSystem.update.UpdatedFiles;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitRevisionNumber;
import git4idea.GitVcs;
import git4idea.actions.GitRepositoryAction;
import git4idea.i18n.GitBundle;
import org.jetbrains.annotations.NonNls;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Utilities for merge
 */
public class GitMergeUtil {
    /**
     * The item representing default strategy
     */
    public static final String DEFAULT_STRATEGY = GitBundle.message("merge.default.strategy");

    /**
     * A private constructor for utility class
     */
    private GitMergeUtil() {
    }

    /**
     * Get a list of merge strategies for the specified branch count
     *
     * @param branchCount a number of branches to merge
     * @return an array of strategy names
     */
    @NonNls
    public static String[] getMergeStrategies(int branchCount) {
        if (branchCount < 0) {
            throw new IllegalArgumentException("Branch count must be non-negative: " + branchCount);
        }
        switch (branchCount) {
            case 0:
                return new String[]{GitLocalize.mergeDefaultStrategy().get()};
            case 1:
                return new String[]{
                    GitLocalize.mergeDefaultStrategy().get(),
                    "resolve",
                    "recursive",
                    "octopus",
                    "ours",
                    "subtree"
                };
            default:
                return new String[]{
                    GitLocalize.mergeDefaultStrategy().get(),
                    "octopus",
                    "ours"
                };
        }
    }

    /**
     * Setup strategies combobox. The set of strategies changes according to amount of selected elements in branchChooser.
     *
     * @param branchChooser a branch chooser
     * @param strategy      a strategy selector
     */
    public static void setupStrategies(final ElementsChooser<String> branchChooser, final JComboBox strategy) {
        final ElementsChooser.ElementsMarkListener<String> listener = new ElementsChooser.ElementsMarkListener<>() {
            private void updateStrategies(final List<String> elements) {
                strategy.removeAllItems();
                for (String s : getMergeStrategies(elements.size())) {
                    strategy.addItem(s);
                }
                strategy.setSelectedItem(GitLocalize.mergeDefaultStrategy().get());
            }

            @Override
            public void elementMarkChanged(final String element, final boolean isMarked) {
                final List<String> elements = branchChooser.getMarkedElements();
                if (elements.size() == 0) {
                    strategy.setEnabled(false);
                    updateStrategies(elements);
                }
                else {
                    strategy.setEnabled(true);
                    updateStrategies(elements);
                }
            }
        };
        listener.elementMarkChanged(null, true);
        branchChooser.addElementsMarkListener(listener);
    }

    /**
     * Show updates caused by git operation
     *
     * @param project     the context project
     * @param exceptions  the exception list
     * @param root        the git root
     * @param currentRev  the revision before update
     * @param beforeLabel the local history label before update
     * @param actionName  the action name
     * @param actionInfo  the information about the action
     */
    public static void showUpdates(
        GitRepositoryAction action,
        final Project project,
        final List<VcsException> exceptions,
        final VirtualFile root,
        final GitRevisionNumber currentRev,
        final Label beforeLabel,
        final LocalizeValue actionName,
        final ActionInfo actionInfo
    ) {
        final UpdatedFiles files = UpdatedFiles.create();
        MergeChangeCollector collector = new MergeChangeCollector(project, root, currentRev);
        collector.collect(files, exceptions);
        if (exceptions.size() != 0) {
            return;
        }
        action.delayTask(exceptionList -> UIUtil.invokeLaterIfNeeded(() ->
        {
            ProjectLevelVcsManager manager = ProjectLevelVcsManager.getInstance(project);
            UpdateInfoTree tree = manager.showUpdateProjectInfo(files, actionName.get(), actionInfo, false);
            tree.setBefore(beforeLabel);
            tree.setAfter(LocalHistory.getInstance().putSystemLabel(project, "After update"));
            ViewUpdateInfoNotification.focusUpdateInfoTree(project, tree);
        }));
        final Collection<String> unmergedNames = files.getGroupById(FileGroup.MERGED_WITH_CONFLICT_ID).getFiles();
        if (!unmergedNames.isEmpty()) {
            action.delayTask(exceptionList -> {
                LocalFileSystem lfs = LocalFileSystem.getInstance();
                final ArrayList<VirtualFile> unmerged = new ArrayList<>();
                for (String fileName : unmergedNames) {
                    VirtualFile f = lfs.findFileByPath(fileName);
                    if (f != null) {
                        unmerged.add(f);
                    }
                }
                UIUtil.invokeLaterIfNeeded(() -> {
                    GitVcs vcs = GitVcs.getInstance(project);
                    if (vcs != null) {
                        AbstractVcsHelper.getInstance(project).showMergeDialog(unmerged, vcs.getMergeProvider());
                    }
                });
            });
        }
    }
}
