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
package git4idea.branch;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import consulo.application.progress.ProgressIndicator;
import consulo.localize.LocalizeValue;
import org.intellij.lang.annotations.MagicConstant;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import consulo.project.Project;
import consulo.versionControlSystem.change.Change;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitCommit;
import git4idea.repo.GitRepository;

/**
 * <p>Handles UI interaction during various operations on branches: shows notifications, proposes to rollback, shows dialogs, messages, etc.
 * Some methods return the choice selected by user to the calling code, if it is needed.</p>
 * <p>The purpose of this class is to separate UI interaction from the main code, which would in particular simplify testing.</p>
 */
public interface GitBranchUiHandler {
    @Nonnull
    ProgressIndicator getProgressIndicator();

    boolean notifyErrorWithRollbackProposal(@Nonnull LocalizeValue title, @Nonnull LocalizeValue message, @Nonnull LocalizeValue rollbackProposal);

    /**
     * Shows notification about unmerged files preventing checkout, merge, etc.
     *
     * @param operationName
     * @param repositories
     */
    void showUnmergedFilesNotification(@Nonnull LocalizeValue operationName, @Nonnull Collection<GitRepository> repositories);

    /**
     * Shows a modal notification about unmerged files preventing an operation, with "Rollback" button.
     * Pressing "Rollback" would should the operation which has already successfully executed on other repositories.
     *
     * @param operationName
     * @param rollbackProposal
     * @return true if user has agreed to rollback, false if user denied the rollback proposal.
     */
    boolean showUnmergedFilesMessageWithRollback(@Nonnull LocalizeValue operationName, @Nonnull LocalizeValue rollbackProposal);

    /**
     * Show notification about "untracked files would be overwritten by merge/checkout".
     */
    void showUntrackedFilesNotification(
        @Nonnull LocalizeValue operationName,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> relativePaths
    );

    boolean showUntrackedFilesDialogWithRollback(
        @Nonnull LocalizeValue operationName,
        @Nonnull LocalizeValue rollbackProposal,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> relativePaths
    );

    /**
     * Shows the dialog proposing to execute the operation (checkout or merge) smartly, i.e. stash-execute-unstash.
     *
     * @param project
     * @param changes          local changes that would be overwritten by checkout or merge.
     * @param paths            paths reported by Git (in most cases this is covered by {@code changes}.
     * @param operation        operation name: checkout or merge
     * @param forceButtonTitle if the operation can be executed force (force checkout is possible),
     *                         specify the title of the force button; otherwise (force merge is not possible) pass null.
     * @return the code of the decision.
     */
    @MagicConstant(valuesFromClass = GitSmartOperationDialog.class)
    int showSmartOperationDialog(
        @Nonnull Project project,
        @Nonnull List<Change> changes,
        @Nonnull Collection<String> paths,
        @Nonnull String operation,
        @Nullable String forceButtonTitle
    );

    /**
     * @return true if user decided to restore the branch.
     */
    boolean showBranchIsNotFullyMergedDialog(
        @Nonnull Project project,
        @Nonnull Map<GitRepository, List<GitCommit>> history,
        @Nonnull Map<GitRepository, String> baseBranches,
        @Nonnull String removedBranch
    );
}
