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
package git4idea.update;

import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.component.ProcessCanceledException;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.dataholder.Key;
import consulo.util.io.FileUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.change.LocalChangeList;
import consulo.versionControlSystem.ui.awt.LegacyComponentFactory;
import consulo.versionControlSystem.ui.awt.LegacyDialog;
import consulo.versionControlSystem.update.UpdatedFiles;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.branch.GitBranchPair;
import git4idea.commands.*;
import git4idea.merge.GitConflictResolver;
import git4idea.merge.GitMerger;
import git4idea.repo.GitRepository;
import git4idea.util.GitUIUtil;
import git4idea.util.GitUntrackedFilesHelper;
import jakarta.annotation.Nonnull;

import java.io.File;
import java.util.*;

import static consulo.util.lang.ObjectUtil.assertNotNull;
import static java.util.Arrays.asList;

/**
 * Handles {@code git pull} via merge.
 */
public class GitMergeUpdater extends GitUpdater {
    private static final Logger LOG = Logger.getInstance(GitMergeUpdater.class);

    @Nonnull
    private final ChangeListManager myChangeListManager;

    public GitMergeUpdater(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull GitRepository repository,
        @Nonnull GitBranchPair branchAndTracked,
        @Nonnull ProgressIndicator progressIndicator,
        @Nonnull UpdatedFiles updatedFiles
    ) {
        super(project, git, repository, branchAndTracked, progressIndicator, updatedFiles);
        myChangeListManager = ChangeListManager.getInstance(myProject);
    }

    @Nonnull
    @Override
    @RequiredUIAccess
    protected GitUpdateResult doUpdate() {
        LOG.info("doUpdate ");
        GitMerger merger = new GitMerger(myProject);

        MergeLineListener mergeLineListener = new MergeLineListener();
        GitUntrackedFilesOverwrittenByOperationDetector untrackedFilesDetector =
            new GitUntrackedFilesOverwrittenByOperationDetector(myRoot);

        String originalText = myProgressIndicator.getText();
        myProgressIndicator.setText("Merging" + GitUtil.mention(myRepository) + "...");
        try {
            GitCommandResult result = myGit.merge(
                myRepository,
                assertNotNull(myBranchPair.getDest()).getName(),
                asList("--no-stat", "-v"),
                mergeLineListener,
                untrackedFilesDetector,
                GitStandardProgressAnalyzer.createListener(myProgressIndicator)
            );
            myProgressIndicator.setText(originalText);
            return result.success()
                ? GitUpdateResult.SUCCESS
                : handleMergeFailure(mergeLineListener, untrackedFilesDetector, merger, result.getErrorOutputAsJoinedValue());
        }
        catch (ProcessCanceledException pce) {
            cancel();
            return GitUpdateResult.CANCEL;
        }
    }

    @Nonnull
    @RequiredUIAccess
    private GitUpdateResult handleMergeFailure(
        MergeLineListener mergeLineListener,
        GitMessageWithFilesDetector untrackedFilesWouldBeOverwrittenByMergeDetector,
        GitMerger merger,
        @Nonnull LocalizeValue errorMessage
    ) {
        MergeError error = mergeLineListener.getMergeError();
        LOG.info("merge error: " + error);
        if (error == MergeError.CONFLICT) {
            LOG.info("Conflict detected");
            boolean allMerged = new MyConflictResolver(myProject, myGit, merger, myRoot).merge();
            return allMerged ? GitUpdateResult.SUCCESS_WITH_RESOLVED_CONFLICTS : GitUpdateResult.INCOMPLETE;
        }
        else if (error == MergeError.LOCAL_CHANGES) {
            LOG.info("Local changes would be overwritten by merge");
            List<FilePath> paths = getFilesOverwrittenByMerge(mergeLineListener.getOutput());
            Collection<Change> changes = getLocalChangesFilteredByFiles(paths);

            LegacyComponentFactory componentFactory = Application.get().getInstance(LegacyComponentFactory.class);
            UIUtil.invokeAndWaitIfNeeded((Runnable) () -> {
                String description = "Your local changes to the following files would be overwritten by merge.<br/>" +
                    "Please, commit your changes or stash them before you can merge.";

                LegacyDialog viewerDialog = componentFactory.createChangeListViewerDialog(myProject, changes, false, description);

                viewerDialog.show();
            });
            return GitUpdateResult.ERROR;
        }
        else if (untrackedFilesWouldBeOverwrittenByMergeDetector.wasMessageDetected()) {
            LOG.info("handleMergeFailure: untracked files would be overwritten by merge");
            GitUntrackedFilesHelper.notifyUntrackedFilesOverwrittenBy(
                myProject,
                myRoot,
                untrackedFilesWouldBeOverwrittenByMergeDetector.getRelativeFilePaths(),
                "merge",
                null
            );
            return GitUpdateResult.ERROR;
        }
        else {
            LOG.info("Unknown error: " + errorMessage);
            GitUIUtil.notifyImportantError(myProject, LocalizeValue.localizeTODO("Error merging"), errorMessage);
            return GitUpdateResult.ERROR;
        }
    }

    @Override
    public boolean isSaveNeeded() {
        try {
            if (GitUtil.hasLocalChanges(true, myProject, myRoot)) {
                return true;
            }
        }
        catch (VcsException e) {
            LOG.info("isSaveNeeded failed to check staging area", e);
            return true;
        }

        // git log --name-status master..origin/master
        String currentBranch = myBranchPair.getBranch().getName();
        String remoteBranch = myBranchPair.getDest().getName();
        try {
            GitRepository repository = GitUtil.getRepositoryManager(myProject).getRepositoryForRoot(myRoot);
            if (repository == null) {
                LOG.error("Repository is null for root " + myRoot);
                return true; // fail safe
            }
            final Collection<String> remotelyChanged =
                GitUtil.getPathsDiffBetweenRefs(Git.getInstance(), repository, currentBranch, remoteBranch);
            final List<File> locallyChanged = myChangeListManager.getAffectedPaths();
            for (final File localPath : locallyChanged) {
                if (ContainerUtil.exists(
                    remotelyChanged,
                    remotelyChangedPath -> FileUtil.pathsEqual(localPath.getPath(), remotelyChangedPath)
                )) {
                    // found a file which was changed locally and remotely => need to save
                    return true;
                }
            }
            return false;
        }
        catch (VcsException e) {
            LOG.info("failed to get remotely changed files for " + currentBranch + ".." + remoteBranch, e);
            return true; // fail safe
        }
    }

    private void cancel() {
        GitLineHandler h = new GitLineHandler(myProject, myRoot, GitCommand.RESET);
        h.addParameters("--merge");
        GitCommandResult result = Git.getInstance().runCommand(h);
        if (!result.success()) {
            LOG.info("cancel git reset --merge: " + result.getErrorOutputAsJoinedString());
            GitUIUtil.notifyImportantError(
                myProject,
                LocalizeValue.localizeTODO("Couldn't reset merge"),
                result.getErrorOutputAsHtmlValue()
            );
        }
    }

    // parses the output of merge conflict returning files which would be overwritten by merge. These files will be stashed.
    private List<FilePath> getFilesOverwrittenByMerge(@Nonnull List<String> mergeOutput) {
        List<FilePath> paths = new ArrayList<>();
        for (String line : mergeOutput) {
            if (StringUtil.isEmptyOrSpaces(line)) {
                continue;
            }
            if (line.contains("Please, commit your changes or stash them before you can merge")) {
                break;
            }
            line = line.trim();

            String path;
            try {
                path = myRoot.getPath() + "/" + GitUtil.unescapePath(line);
                File file = new File(path);
                if (file.exists()) {
                    paths.add(VcsUtil.getFilePath(file, false));
                }
            }
            catch (VcsException e) { // just continue
            }
        }
        return paths;
    }

    private Collection<Change> getLocalChangesFilteredByFiles(List<FilePath> paths) {
        Collection<Change> changes = new HashSet<>();
        for (LocalChangeList list : myChangeListManager.getChangeLists()) {
            for (Change change : list.getChanges()) {
                ContentRevision afterRevision = change.getAfterRevision();
                ContentRevision beforeRevision = change.getBeforeRevision();
                if ((afterRevision != null && paths.contains(afterRevision.getFile())) || (beforeRevision != null && paths.contains(
                    beforeRevision.getFile()))) {
                    changes.add(change);
                }
            }
        }
        return changes;
    }

    @Override
    public String toString() {
        return "Merge updater";
    }

    private enum MergeError {
        CONFLICT,
        LOCAL_CHANGES,
        OTHER
    }

    private static class MergeLineListener extends GitLineHandlerAdapter {
        private MergeError myMergeError;
        private List<String> myOutput = new ArrayList<>();
        private boolean myLocalChangesError = false;

        @Override
        public void onLineAvailable(String line, Key outputType) {
            if (myLocalChangesError) {
                myOutput.add(line);
            }
            else if (line.contains("Automatic merge failed; fix conflicts and then commit the result")) {
                myMergeError = MergeError.CONFLICT;
            }
            else if (line.contains("Your local changes to the following files would be overwritten by merge")) {
                myMergeError = MergeError.LOCAL_CHANGES;
                myLocalChangesError = true;
            }
        }

        public MergeError getMergeError() {
            return myMergeError;
        }

        public List<String> getOutput() {
            return myOutput;
        }
    }

    private static class MyConflictResolver extends GitConflictResolver {
        private final GitMerger myMerger;
        private final VirtualFile myRoot;

        public MyConflictResolver(Project project, @Nonnull Git git, GitMerger merger, VirtualFile root) {
            super(project, git, Collections.singleton(root), makeParams());
            myMerger = merger;
            myRoot = root;
        }

        private static Params makeParams() {
            Params params = new Params();
            params.setErrorNotificationTitle("Can't complete update");
            params.setMergeDescription("Merge conflicts detected. Resolve them before continuing update.");
            return params;
        }

        @Override
        protected boolean proceedIfNothingToMerge() throws VcsException {
            myMerger.mergeCommit(myRoot);
            return true;
        }

        @Override
        protected boolean proceedAfterAllMerged() throws VcsException {
            myMerger.mergeCommit(myRoot);
            return true;
        }
    }
}
