/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package git4idea.stash;

import consulo.application.progress.ProgressIndicator;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.merge.MergeDialogCustomizer;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitHandlerUtil;
import git4idea.commands.GitSimpleEventDetector;
import git4idea.merge.GitConflictResolver;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.ui.GitUnstashDialog;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class GitStashChangesSaver extends GitChangesSaver {
    private static final Logger LOG = Logger.getInstance(GitStashChangesSaver.class);
    private static final String NO_LOCAL_CHANGES_TO_SAVE = "No local changes to save";

    @Nonnull
    private final GitRepositoryManager myRepositoryManager;
    @Nonnull
    private final Set<VirtualFile> myStashedRoots = new HashSet<>(); // save stashed roots to unstash only them

    public GitStashChangesSaver(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull ProgressIndicator progressIndicator,
        @Nonnull String stashMessage
    ) {
        super(project, git, progressIndicator, stashMessage);
        myRepositoryManager = GitUtil.getRepositoryManager(project);
    }

    @Override
    protected void save(@Nonnull Collection<VirtualFile> rootsToSave) throws VcsException {
        LOG.info("saving " + rootsToSave);

        for (VirtualFile root : rootsToSave) {
            String message = GitHandlerUtil.formatOperationName("Stashing changes from", root);
            LOG.info(message);
            LocalizeValue oldProgressTitle = myProgressIndicator.getTextValue();
            myProgressIndicator.setText(message);
            GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
            if (repository == null) {
                LOG.error("Repository is null for root " + root);
            }
            else {
                GitCommandResult result = myGit.stashSave(repository, myStashMessage);
                if (result.success() && somethingWasStashed(result)) {
                    myStashedRoots.add(root);
                }
                else {
                    String error = "stash " + repository.getRoot() + ": " + result.getErrorOutputAsJoinedString();
                    if (!result.success()) {
                        throw new VcsException(error);
                    }
                    else {
                        LOG.warn(error);
                    }
                }
            }
            myProgressIndicator.setTextValue(oldProgressTitle);
        }
    }

    private static boolean somethingWasStashed(@Nonnull GitCommandResult result) {
        return !StringUtil.containsIgnoreCase(result.getErrorOutputAsJoinedString(), NO_LOCAL_CHANGES_TO_SAVE)
            && !StringUtil.containsIgnoreCase(result.getOutputAsJoinedString(), NO_LOCAL_CHANGES_TO_SAVE);
    }

    @Override
    @RequiredUIAccess
    public void load() {
        for (VirtualFile root : myStashedRoots) {
            loadRoot(root);
        }

        boolean conflictsResolved = new UnstashConflictResolver(myProject, myGit, myStashedRoots, myParams).merge();
        LOG.info("load: conflicts resolved status is " + conflictsResolved + " in roots " + myStashedRoots);
    }

    @Override
    public boolean wereChangesSaved() {
        return !myStashedRoots.isEmpty();
    }

    @Override
    public String getSaverName() {
        return "stash";
    }

    @Nonnull
    @Override
    public String getOperationName() {
        return "stash";
    }

    @Override
    @RequiredUIAccess
    public void showSavedChanges() {
        GitUnstashDialog.showUnstashDialog(myProject, new ArrayList<>(myStashedRoots), myStashedRoots.iterator().next());
    }

    /**
     * Returns true if the root was loaded with conflict.
     * False is returned in all other cases: in the case of success and in case of some other error.
     */
    private boolean loadRoot(VirtualFile root) {
        LOG.info("loadRoot " + root);
        myProgressIndicator.setText(GitHandlerUtil.formatOperationName("Unstashing changes to", root));

        GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
        if (repository == null) {
            LOG.error("Repository is null for root " + root);
            return false;
        }

        GitSimpleEventDetector conflictDetector = new GitSimpleEventDetector(GitSimpleEventDetector.Event.MERGE_CONFLICT_ON_UNSTASH);
        GitCommandResult result = myGit.stashPop(repository, conflictDetector);
        VirtualFileUtil.markDirtyAndRefresh(false, true, false, root);
        if (result.success()) {
            return false;
        }
        else if (conflictDetector.hasHappened()) {
            return true;
        }
        else {
            LOG.info("unstash failed " + result.getErrorOutputAsJoinedString());
            GitUIUtil.notifyImportantError(
                myProject,
                LocalizeValue.localizeTODO("Couldn't unstash"),
                LocalizeValue.localizeTODO("<br/>" + result.getErrorOutputAsHtmlValue())
            );
            return false;
        }
    }

    @Override
    public String toString() {
        return "StashChangesSaver. Roots: " + myStashedRoots;
    }

    private static class UnstashConflictResolver extends GitConflictResolver {
        private final Set<VirtualFile> myStashedRoots;

        public UnstashConflictResolver(
            @Nonnull Project project,
            @Nonnull Git git,
            @Nonnull Set<VirtualFile> stashedRoots,
            @Nullable Params params
        ) {
            super(project, git, stashedRoots, makeParamsOrUse(params));
            myStashedRoots = stashedRoots;
        }

        private static Params makeParamsOrUse(@Nullable Params givenParams) {
            if (givenParams != null) {
                return givenParams;
            }
            return new Params()
                .setErrorNotificationTitle(LocalizeValue.localizeTODO("Local changes were not restored"))
                .setMergeDialogCustomizer(new UnstashMergeDialogCustomizer())
                .setReverse(true);
        }

        @Override
        protected void notifyUnresolvedRemain() {
            NotificationService.getInstance().newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Local changes were restored with conflicts"))
                .content(LocalizeValue.localizeTODO(
                    "Your uncommitted changes were saved to <a href='saver'>stash</a>.<br/>" +
                        "Unstash is not complete, you have unresolved merges in your working tree<br/>" +
                        "<a href='resolve'>Resolve</a> conflicts and drop the stash."
                ))
                .hyperlinkListener((notification, event) -> {
                    if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                        if (event.getDescription().equals("saver")) {
                            // we don't use #showSavedChanges to specify unmerged root first
                            GitUnstashDialog.showUnstashDialog(
                                myProject,
                                new ArrayList<>(myStashedRoots),
                                myStashedRoots.iterator().next()
                            );
                        }
                        else if (event.getDescription().equals("resolve")) {
                            mergeNoProceed();
                        }
                    }
                })
                .notify(myProject);
        }
    }

    private static class UnstashMergeDialogCustomizer extends MergeDialogCustomizer {
        @Override
        public String getMultipleFileMergeDescription(@Nonnull Collection<VirtualFile> files) {
            return "Uncommitted changes that were stashed before update have conflicts with updated files.";
        }

        @Nonnull
        @Override
        public String getLeftPanelTitle(@Nonnull VirtualFile file) {
            return getConflictLeftPanelTitle().get();
        }

        @Nonnull
        @Override
        public String getRightPanelTitle(@Nonnull VirtualFile file, VcsRevisionNumber revisionNumber) {
            return getConflictRightPanelTitle().get();
        }
    }
}
