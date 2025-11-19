/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.history;

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.dataContext.DataContext;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationType;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.RelativePoint;
import consulo.ui.ex.action.*;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.ui.ex.popup.ListPopup;
import consulo.util.collection.ArrayUtil;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.history.BaseDiffFromHistoryHandler;
import consulo.versionControlSystem.history.DiffFromHistoryHandler;
import consulo.versionControlSystem.history.VcsFileRevision;
import consulo.versionControlSystem.ui.VcsBalloonProblemNotifier;
import git4idea.GitFileRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.changes.GitChangeUtils;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link DiffFromHistoryHandler#showDiffForTwo(Project, FilePath, VcsFileRevision,
 * VcsFileRevision) "Show Diff" for 2 revision} calls the common code.
 * {@link DiffFromHistoryHandler#showDiffForOne(AnActionEvent, FilePath, VcsFileRevision,
 * VcsFileRevision) "Show diff" for 1 revision}
 * behaves differently for merge commits: for them it shown a popup displaying the parents of the selected commit. Selecting a parent
 * from the popup shows the difference with this parent.
 * If an ordinary (not merge) revision with 1 parent, it is the same as usual: just compare with the parent;
 *
 * @author Kirill Likhodedov
 */
public class GitDiffFromHistoryHandler extends BaseDiffFromHistoryHandler<GitFileRevision> {

    private static final Logger LOG = Logger.getInstance(GitDiffFromHistoryHandler.class);

    @Nonnull
    private final Git myGit;
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;

    public GitDiffFromHistoryHandler(@Nonnull Project project) {
        super(project);
        myGit = Git.getInstance();
        myRepositoryManager = GitUtil.getRepositoryManager(project);
    }

    @Override
    public void showDiffForOne(
        @Nonnull AnActionEvent e,
        @Nonnull Project project,
        @Nonnull FilePath filePath,
        @Nonnull VcsFileRevision previousRevision,
        @Nonnull VcsFileRevision revision
    ) {
        GitFileRevision rev = (GitFileRevision) revision;
        Collection<String> parents = rev.getParents();
        if (parents.size() < 2) {
            super.showDiffForOne(e, project, filePath, previousRevision, revision);
        }
        else { // merge
            showDiffForMergeCommit(e, filePath, rev, parents);
        }
    }

    @Nonnull
    @Override
    protected List<Change> getChangesBetweenRevisions(
        @Nonnull FilePath path,
        @Nonnull GitFileRevision rev1,
        @Nullable GitFileRevision rev2
    ) throws VcsException {
        GitRepository repository = getRepository(path);
        String hash1 = rev1.getHash();
        String hash2 = rev2 != null ? rev2.getHash() : null;

        return new ArrayList<>(GitChangeUtils.getDiff(
            repository.getProject(),
            repository.getRoot(),
            hash1,
            hash2,
            Collections.singletonList(path)
        ));
    }

    @Nonnull
    @Override
    protected List<Change> getAffectedChanges(@Nonnull FilePath path, @Nonnull GitFileRevision rev) throws VcsException {
        GitRepository repository = getRepository(path);

        return new ArrayList<>(GitChangeUtils.getRevisionChanges(
            repository.getProject(),
            repository.getRoot(),
            rev.getHash(),
            false,
            true,
            true
        ).getChanges());
    }

    @Nonnull
    @Override
    protected String getPresentableName(@Nonnull GitFileRevision revision) {
        return DvcsUtil.getShortHash(revision.getHash());
    }

    @Nonnull
    private GitRepository getRepository(@Nonnull FilePath path) {
        GitRepository repository = myRepositoryManager.getRepositoryForFile(path);
        LOG.assertTrue(repository != null, "Repository is null for " + path);
        return repository;
    }

    private void showDiffForMergeCommit(
        @Nonnull AnActionEvent event,
        @Nonnull FilePath filePath,
        @Nonnull GitFileRevision rev,
        @Nonnull Collection<String> parents
    ) {
        checkIfFileWasTouchedAndFindParentsInBackground(
            filePath,
            rev,
            parents,
            info -> {
                if (!info.wasFileTouched()) {
                    String message = String.format(
                        "There were no changes in %s in this merge commit, besides those which were made in both branches",
                        filePath.getName()
                    );
                    VcsBalloonProblemNotifier.showOverVersionControlView(
                        GitDiffFromHistoryHandler.this.myProject,
                        message,
                        NotificationType.INFORMATION
                    );
                }
                showPopup(event, rev, filePath, info.getParents());
            }
        );
    }

    private static class MergeCommitPreCheckInfo {
        private final boolean myWasFileTouched;
        private final Collection<GitFileRevision> myParents;

        private MergeCommitPreCheckInfo(boolean touched, Collection<GitFileRevision> parents) {
            myWasFileTouched = touched;
            myParents = parents;
        }

        public boolean wasFileTouched() {
            return myWasFileTouched;
        }

        public Collection<GitFileRevision> getParents() {
            return myParents;
        }
    }

    private void checkIfFileWasTouchedAndFindParentsInBackground(
        @Nonnull final FilePath filePath,
        @Nonnull final GitFileRevision rev,
        @Nonnull final Collection<String> parentHashes,
        @Nonnull final Consumer<MergeCommitPreCheckInfo> resultHandler
    ) {
        new Task.Backgroundable(myProject, LocalizeValue.localizeTODO("Loading changes..."), true) {
            private MergeCommitPreCheckInfo myInfo;

            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                try {
                    GitRepository repository = getRepository(filePath);
                    boolean fileTouched = wasFileTouched(repository, rev);
                    Collection<GitFileRevision> parents = findParentRevisions(repository, rev, parentHashes);
                    myInfo = new MergeCommitPreCheckInfo(fileTouched, parents);
                }
                catch (VcsException e) {
                    String logMessage = "Error happened while executing git show " + rev + ":" + filePath;
                    showError(e, logMessage);
                }
            }

            @RequiredUIAccess
            @Override
            public void onSuccess() {
                if (myInfo != null) { // if info == null => an exception happened
                    resultHandler.accept(myInfo);
                }
            }
        }.queue();
    }

    @Nonnull
    private Collection<GitFileRevision> findParentRevisions(
        @Nonnull GitRepository repository,
        @Nonnull GitFileRevision currentRevision,
        @Nonnull Collection<String> parentHashes
    ) throws VcsException {
        // currentRevision is a merge revision.
        // the file could be renamed in one of the branches, i.e. the name in one of the parent revisions may be different from the name
        // in currentRevision. It can be different even in both parents, but it would a rename-rename conflict, and we don't handle such anyway.

        Collection<GitFileRevision> parents = new ArrayList<>(parentHashes.size());
        for (String parentHash : parentHashes) {
            parents.add(createParentRevision(repository, currentRevision, parentHash));
        }
        return parents;
    }

    @Nonnull
    private GitFileRevision createParentRevision(
        @Nonnull GitRepository repository,
        @Nonnull GitFileRevision currentRevision,
        @Nonnull String parentHash
    ) throws VcsException {
        FilePath currentRevisionPath = currentRevision.getPath();
        if (currentRevisionPath.isDirectory()) {
            // for directories the history doesn't follow renames
            return makeRevisionFromHash(currentRevisionPath, parentHash);
        }

        // can't limit by the path: in that case rename information will be missed
        Collection<Change> changes = GitChangeUtils.getDiff(myProject, repository.getRoot(), parentHash, currentRevision.getHash(), null);
        for (Change change : changes) {
            ContentRevision afterRevision = change.getAfterRevision();
            ContentRevision beforeRevision = change.getBeforeRevision();
            if (afterRevision != null && afterRevision.getFile().equals(currentRevisionPath)) {
                // if the file was renamed, taking the path how it was in the parent; otherwise the path didn't change
                FilePath path = (beforeRevision != null ? beforeRevision.getFile() : afterRevision.getFile());
                return new GitFileRevision(myProject, path, new GitRevisionNumber(parentHash));
            }
        }
        LOG.error(String.format(
            "Could not find parent revision. Will use the path from parent revision. Current revision: %s, parent hash: %s",
            currentRevision,
            parentHash
        ));
        return makeRevisionFromHash(currentRevisionPath, parentHash);
    }

    private void showPopup(
        @Nonnull AnActionEvent event,
        @Nonnull GitFileRevision rev,
        @Nonnull FilePath filePath,
        @Nonnull Collection<GitFileRevision> parents
    ) {
        ActionGroup parentActions = createActionGroup(rev, filePath, parents);
        DataContext dataContext = DataContext.builder().add(Project.KEY, myProject).build();
        ListPopup popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "Choose parent to compare",
                parentActions,
                dataContext,
                JBPopupFactory.ActionSelectionAid.NUMBERING,
                true
            );
        showPopupInBestPosition(popup, event, dataContext);
    }

    private static void showPopupInBestPosition(@Nonnull ListPopup popup, @Nonnull AnActionEvent event, @Nonnull DataContext dataContext) {
        if (event.getInputEvent() instanceof MouseEvent mouseEvent) {
            if (!event.getPlace().equals(ActionPlaces.UPDATE_POPUP)) {
                popup.show(new RelativePoint(mouseEvent));
            }
            else { // quick fix for invoking from the context menu: coordinates are calculated incorrectly there.
                popup.showInBestPositionFor(dataContext);
            }
        }
        else {
            popup.showInBestPositionFor(dataContext);
        }
    }

    @Nonnull
    private ActionGroup createActionGroup(
        @Nonnull GitFileRevision rev,
        @Nonnull FilePath filePath,
        @Nonnull Collection<GitFileRevision> parents
    ) {
        Collection<AnAction> actions = new ArrayList<>(2);
        for (GitFileRevision parent : parents) {
            actions.add(createParentAction(rev, filePath, parent));
        }
        return new DefaultActionGroup(ArrayUtil.toObjectArray(actions, AnAction.class));
    }

    @Nonnull
    private AnAction createParentAction(@Nonnull GitFileRevision rev, @Nonnull FilePath filePath, @Nonnull GitFileRevision parent) {
        return new ShowDiffWithParentAction(filePath, rev, parent);
    }

    @Nonnull
    private GitFileRevision makeRevisionFromHash(@Nonnull FilePath filePath, @Nonnull String hash) {
        return new GitFileRevision(myProject, filePath, new GitRevisionNumber(hash));
    }

    private boolean wasFileTouched(@Nonnull GitRepository repository, @Nonnull GitFileRevision rev) throws VcsException {
        GitCommandResult result = myGit.show(repository, rev.getHash());
        if (result.success()) {
            return isFilePresentInOutput(repository, rev.getPath(), result.getOutput());
        }
        throw new VcsException(result.getErrorOutputAsJoinedValue());
    }

    private static boolean isFilePresentInOutput(@Nonnull GitRepository repository, @Nonnull FilePath path, @Nonnull List<String> output) {
        String relativePath = getRelativePath(repository, path);
        for (String line : output) {
            if (line.startsWith("---") || line.startsWith("+++")) {
                if (line.contains(relativePath)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static String getRelativePath(@Nonnull GitRepository repository, @Nonnull FilePath path) {
        return FileUtil.getRelativePath(repository.getRoot().getPath(), path.getPath(), '/');
    }

    private class ShowDiffWithParentAction extends AnAction {

        @Nonnull
        private final FilePath myFilePath;
        @Nonnull
        private final GitFileRevision myRevision;
        @Nonnull
        private final GitFileRevision myParentRevision;

        public ShowDiffWithParentAction(@Nonnull FilePath filePath, @Nonnull GitFileRevision rev, @Nonnull GitFileRevision parent) {
            super(DvcsUtil.getShortHash(parent.getHash()));
            myFilePath = filePath;
            myRevision = rev;
            myParentRevision = parent;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            doShowDiff(myFilePath, myParentRevision, myRevision);
        }
    }
}
