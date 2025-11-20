/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package git4idea.checkin;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.language.editor.ui.awt.*;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.platform.Platform;
import consulo.platform.base.localize.CommonLocalize;
import consulo.project.Project;
import consulo.ui.Label;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.GridBag;
import consulo.ui.ex.awt.JBCheckBox;
import consulo.ui.ex.awt.JBUI;
import consulo.ui.ex.awt.UIUtil;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.util.io.FileUtil;
import consulo.util.lang.StringUtil;
import consulo.util.lang.function.Functions;
import consulo.util.lang.function.PairConsumer;
import consulo.util.lang.ref.SimpleReference;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.*;
import consulo.versionControlSystem.checkin.CheckinChangeListSpecificComponent;
import consulo.versionControlSystem.checkin.CheckinEnvironment;
import consulo.versionControlSystem.checkin.CheckinProjectPanel;
import consulo.versionControlSystem.distributed.DistributedVersionControlHelper;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.ui.AmendComponent;
import consulo.versionControlSystem.log.VcsFullCommitDetails;
import consulo.versionControlSystem.log.VcsUser;
import consulo.versionControlSystem.log.VcsUserRegistry;
import consulo.versionControlSystem.log.util.VcsUserUtil;
import consulo.versionControlSystem.ui.RefreshableOnComponent;
import consulo.versionControlSystem.ui.awt.LegacyComponentFactory;
import consulo.versionControlSystem.ui.awt.LegacyDialog;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUserRegistry;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchUtil;
import git4idea.changes.GitChangeUtils;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import git4idea.config.GitConfigUtil;
import git4idea.config.GitVcsSettings;
import git4idea.config.GitVersionSpecialty;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitFileUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

import static consulo.util.collection.ContainerUtil.*;
import static consulo.util.lang.ObjectUtil.assertNotNull;
import static consulo.util.lang.StringUtil.escapeXml;
import static consulo.versionControlSystem.change.ChangesUtil.getAfterPath;
import static consulo.versionControlSystem.change.ChangesUtil.getBeforePath;
import static consulo.versionControlSystem.distributed.DvcsUtil.getShortRepositoryName;
import static git4idea.GitUtil.getLogString;
import static git4idea.GitUtil.getRepositoryManager;
import static java.util.Arrays.asList;

@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitCheckinEnvironment implements CheckinEnvironment {
    private static final Logger LOG = Logger.getInstance(GitCheckinEnvironment.class);
    private static final String GIT_COMMIT_MSG_FILE_PREFIX = "git-commit-msg-"; // the file name prefix for commit message file
    private static final String GIT_COMMIT_MSG_FILE_EXT = ".txt"; // the file extension for commit message file

    @Nonnull
    private final Project myProject;
    public static final SimpleDateFormat COMMIT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    @Nonnull
    private final VcsDirtyScopeManager myDirtyScopeManager;
    private final GitVcsSettings mySettings;

    private String myNextCommitAuthor = null; // The author for the next commit
    private boolean myNextCommitAmend; // If true, the next commit is amended
    private Boolean myNextCommitIsPushed = null; // The push option of the next commit
    private Date myNextCommitAuthorDate;
    private boolean myNextCommitSignOff;

    @Inject
    public GitCheckinEnvironment(
        @Nonnull Project project,
        @Nonnull VcsDirtyScopeManager dirtyScopeManager,
        GitVcsSettings settings
    ) {
        myProject = project;
        myDirtyScopeManager = dirtyScopeManager;
        mySettings = settings;
    }

    @Override
    public boolean keepChangeListAfterCommit(ChangeList changeList) {
        return false;
    }

    @Override
    public boolean isRefreshAfterCommitNeeded() {
        return true;
    }

    @Nullable
    @Override
    public RefreshableOnComponent createAdditionalOptionsPanel(
        CheckinProjectPanel panel,
        PairConsumer<Object, Object> additionalDataConsumer
    ) {
        return new GitCheckinOptions(myProject, panel);
    }

    @Nullable
    @Override
    public String getDefaultMessageFor(FilePath[] filesToCheckin) {
        Set<String> messages = new LinkedHashSet<>();
        GitRepositoryManager manager = getRepositoryManager(myProject);
        for (VirtualFile root : GitUtil.gitRoots(asList(filesToCheckin))) {
            GitRepository repository = manager.getRepositoryForRoot(root);
            if (repository == null) { // unregistered nested submodule found by GitUtil.getGitRoot
                LOG.warn("Unregistered repository: " + root);
                continue;
            }
            File mergeMsg = repository.getRepositoryFiles().getMergeMessageFile();
            File squashMsg = repository.getRepositoryFiles().getSquashMessageFile();
            try {
                if (!mergeMsg.exists() && !squashMsg.exists()) {
                    continue;
                }
                String encoding = GitConfigUtil.getCommitEncoding(myProject, root);
                if (mergeMsg.exists()) {
                    messages.add(loadMessage(mergeMsg, encoding));
                }
                else {
                    messages.add(loadMessage(squashMsg, encoding));
                }
            }
            catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Unable to load merge message", e);
                }
            }
        }
        return DvcsUtil.joinMessagesOrNull(messages);
    }

    private static String loadMessage(@Nonnull File messageFile, @Nonnull String encoding) throws IOException {
        Charset charset = Charset.forName(encoding);
        return Files.readString(messageFile.toPath(), charset);
    }

    @Override
    public String getHelpId() {
        return null;
    }

    @Nonnull
    @Override
    public LocalizeValue getCheckinOperationName() {
        return GitLocalize.commitActionName();
    }

    @Override
    public List<VcsException> commit(
        @Nonnull List<Change> changes,
        @Nonnull String message,
        @Nonnull Function<Object, Object> parametersHolder,
        Set<String> feedback
    ) {
        List<VcsException> exceptions = new ArrayList<>();
        Map<VirtualFile, Collection<Change>> sortedChanges = sortChangesByGitRoot(changes, exceptions);
        LOG.assertTrue(!sortedChanges.isEmpty(), "Trying to commit an empty list of changes: " + changes);
        for (Map.Entry<VirtualFile, Collection<Change>> entry : sortedChanges.entrySet()) {
            VirtualFile root = entry.getKey();
            File messageFile;
            try {
                messageFile = createMessageFile(root, message);
            }
            catch (IOException ex) {
                //noinspection ThrowableInstanceNeverThrown
                exceptions.add(new VcsException(GitLocalize.errorCommitCantCreateMessageFile(), ex));
                continue;
            }

            Set<FilePath> added = new HashSet<>();
            Set<FilePath> removed = new HashSet<>();
            Set<Change> caseOnlyRenames = new HashSet<>();
            for (Change change : entry.getValue()) {
                switch (change.getType()) {
                    case NEW:
                    case MODIFICATION:
                        added.add(change.getAfterRevision().getFile());
                        break;
                    case DELETED:
                        removed.add(change.getBeforeRevision().getFile());
                        break;
                    case MOVED:
                        FilePath afterPath = change.getAfterRevision().getFile();
                        FilePath beforePath = change.getBeforeRevision().getFile();
                        if (!Platform.current().fs().isCaseSensitive()
                            && GitUtil.isCaseOnlyChange(beforePath.getPath(), afterPath.getPath())) {
                            caseOnlyRenames.add(change);
                        }
                        else {
                            added.add(afterPath);
                            removed.add(beforePath);
                        }
                        break;
                    default:
                        throw new IllegalStateException("Unknown change type: " + change.getType());
                }
            }

            try {
                if (!caseOnlyRenames.isEmpty()) {
                    List<VcsException> exs =
                        commitWithCaseOnlyRename(myProject, root, caseOnlyRenames, added, removed, messageFile, myNextCommitAuthor);
                    exceptions.addAll(map(exs, GitCheckinEnvironment::cleanupExceptionText));
                }
                else {
                    try {
                        Set<FilePath> files = new HashSet<>();
                        files.addAll(added);
                        files.addAll(removed);
                        commit(myProject, root, files, messageFile);
                    }
                    catch (VcsException ex) {
                        PartialOperation partialOperation = isMergeCommit(ex);
                        if (partialOperation == PartialOperation.NONE) {
                            throw ex;
                        }
                        if (!mergeCommit(myProject, root, added, removed, messageFile, myNextCommitAuthor, exceptions, partialOperation)) {
                            throw ex;
                        }
                    }
                }
            }
            catch (VcsException e) {
                exceptions.add(cleanupExceptionText(e));
            }
            finally {
                if (!messageFile.delete()) {
                    LOG.warn("Failed to remove temporary file: " + messageFile);
                }
            }
        }
        if (myNextCommitIsPushed != null && myNextCommitIsPushed && exceptions.isEmpty()) {
            GitRepositoryManager manager = getRepositoryManager(myProject);
            Collection<GitRepository> repositories = GitUtil.getRepositoriesFromRoots(manager, sortedChanges.keySet());
            List<GitRepository> preselectedRepositories = new ArrayList<>(repositories);
            Application application = myProject.getApplication();
            application.invokeLater(
                () -> {
                    DistributedVersionControlHelper helper = application.getInstance(DistributedVersionControlHelper.class);

                    helper.createPushDialog(
                        myProject,
                        preselectedRepositories,
                        GitBranchUtil.getCurrentRepository(myProject)
                    ).show();
                },
                application.getDefaultModalityState()
            );
        }
        return exceptions;
    }

    @Nonnull
    private List<VcsException> commitWithCaseOnlyRename(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull Set<Change> caseOnlyRenames,
        @Nonnull Set<FilePath> added,
        @Nonnull Set<FilePath> removed,
        @Nonnull File messageFile,
        @Nullable String author
    ) {
        String rootPath = root.getPath();
        LOG.info(
            "Committing case only rename: " + getLogString(rootPath, caseOnlyRenames) + " in " +
                getShortRepositoryName(project, root)
        );

        // 1. Check what is staged besides case-only renames
        Collection<Change> stagedChanges;
        try {
            stagedChanges = GitChangeUtils.getStagedChanges(project, root);
            LOG.debug("Found staged changes: " + getLogString(rootPath, stagedChanges));
        }
        catch (VcsException e) {
            return Collections.singletonList(e);
        }

        // 2. Reset staged changes which are not selected for commit
        Collection<Change> excludedStagedChanges = filter(
            stagedChanges,
            change -> !caseOnlyRenames.contains(change)
                && !added.contains(getAfterPath(change))
                && !removed.contains(getBeforePath(change))
        );
        if (!excludedStagedChanges.isEmpty()) {
            LOG.info("Staged changes excluded for commit: " + getLogString(rootPath, excludedStagedChanges));
            try {
                reset(project, root, excludedStagedChanges);
            }
            catch (VcsException e) {
                return Collections.singletonList(e);
            }
        }

        List<VcsException> exceptions = new ArrayList<>();
        try {
            // 3. Stage what else is needed to commit
            List<FilePath> newPathsOfCaseRenames = map(caseOnlyRenames, ChangesUtil::getAfterPath);
            LOG.debug("Updating index for added:" + added + "\n, removed: " + removed + "\n, and case-renames: " + newPathsOfCaseRenames);
            Set<FilePath> toAdd = new HashSet<>(added);
            toAdd.addAll(newPathsOfCaseRenames);
            updateIndex(project, root, toAdd, removed, exceptions);
            if (!exceptions.isEmpty()) {
                return exceptions;
            }

            // 4. Commit the staging area
            LOG.debug("Performing commit...");
            try {
                commitWithoutPaths(project, root, messageFile, author);
            }
            catch (VcsException e) {
                return Collections.singletonList(e);
            }
        }
        finally {
            // 5. Stage back the changes unstaged before commit
            if (!excludedStagedChanges.isEmpty()) {
                LOG.debug("Restoring changes which were unstaged before commit: " + getLogString(rootPath, excludedStagedChanges));
                Set<FilePath> toAdd = map2SetNotNull(excludedStagedChanges, ChangesUtil::getAfterPath);
                Predicate<Change> isMovedOrDeleted =
                    change -> change.getType() == Change.Type.MOVED || change.getType() == Change.Type.DELETED;
                Set<FilePath> toRemove = map2SetNotNull(filter(excludedStagedChanges, isMovedOrDeleted), ChangesUtil::getBeforePath);
                updateIndex(project, root, toAdd, toRemove, exceptions);
            }
        }
        return exceptions;
    }

    private static void reset(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull Collection<Change> changes
    ) throws VcsException {
        Set<FilePath> paths = new HashSet<>();
        paths.addAll(mapNotNull(changes, ChangesUtil::getAfterPath));
        paths.addAll(mapNotNull(changes, ChangesUtil::getBeforePath));

        GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.RESET);
        handler.endOptions();
        handler.addRelativePaths(paths);
        handler.run();
    }

    @Nonnull
    private static VcsException cleanupExceptionText(VcsException original) {
        String msg = original.getMessage();
        msg = GitUtil.cleanupErrorPrefixes(msg);
        String DURING_EXECUTING_SUFFIX = GitSimpleHandler.DURING_EXECUTING_ERROR_MESSAGE;
        int suffix = msg.indexOf(DURING_EXECUTING_SUFFIX);
        if (suffix > 0) {
            msg = msg.substring(0, suffix);
        }
        return new VcsException(msg.trim(), original.getCause());
    }

    @Override
    public List<VcsException> commit(List<Change> changes, String preparedComment) {
        return commit(changes, preparedComment, Functions.constant(null), null);
    }

    /**
     * Preform a merge commit
     *
     * @param project          a project
     * @param root             a vcs root
     * @param added            added files
     * @param removed          removed files
     * @param messageFile      a message file for commit
     * @param author           an author
     * @param exceptions       the list of exceptions to report
     * @param partialOperation
     * @return true if merge commit was successful
     */
    private boolean mergeCommit(
        Project project,
        VirtualFile root,
        Set<FilePath> added,
        Set<FilePath> removed,
        File messageFile,
        String author,
        List<VcsException> exceptions,
        @Nonnull PartialOperation partialOperation
    ) {
        Set<FilePath> realAdded = new HashSet<>();
        Set<FilePath> realRemoved = new HashSet<>();
        // perform diff
        GitSimpleHandler diff = new GitSimpleHandler(project, root, GitCommand.DIFF);
        diff.setSilent(true);
        diff.setStdoutSuppressed(true);
        diff.addParameters("--diff-filter=ADMRUX", "--name-status", "--no-renames", "HEAD");
        diff.endOptions();
        String output;
        try {
            output = diff.run();
        }
        catch (VcsException ex) {
            exceptions.add(ex);
            return false;
        }
        String rootPath = root.getPath();
        for (StringTokenizer lines = new StringTokenizer(output, "\n", false); lines.hasMoreTokens(); ) {
            String line = lines.nextToken().trim();
            if (line.length() == 0) {
                continue;
            }
            String[] tk = line.split("\t");
            switch (tk[0].charAt(0)) {
                case 'M':
                case 'A':
                    realAdded.add(VcsUtil.getFilePath(rootPath + "/" + tk[1]));
                    break;
                case 'D':
                    realRemoved.add(VcsUtil.getFilePathForDeletedFile(rootPath + "/" + tk[1], false));
                    break;
                default:
                    throw new IllegalStateException("Unexpected status: " + line);
            }
        }
        realAdded.removeAll(added);
        realRemoved.removeAll(removed);
        if (realAdded.size() != 0 || realRemoved.size() != 0) {
            List<FilePath> files = new ArrayList<>();
            files.addAll(realAdded);
            files.addAll(realRemoved);
            SimpleReference<Boolean> mergeAll = new SimpleReference<>();
            try {
                LegacyComponentFactory legacyComponentFactory = project.getApplication().getInstance(LegacyComponentFactory.class);

                UIUtil.invokeAndWaitIfNeeded((Runnable) () -> {
                    LocalizeValue message = GitLocalize.commitPartialMergeMessage(partialOperation.getName());
                    LegacyDialog dialog = legacyComponentFactory.createSelectFilePathsDialog(
                        project,
                        files,
                        message.get(),
                        null,
                        GitLocalize.buttonCommitAllFiles(),
                        CommonLocalize.buttonCancel(),
                        false
                    );
                    dialog.setTitle(GitLocalize.commitPartialMergeTitle());
                    dialog.show();
                    mergeAll.set(dialog.isOK());
                });
            }
            catch (RuntimeException ex) {
                throw ex;
            }
            catch (Exception ex) {
                throw new RuntimeException("Unable to invoke a message box on AWT thread", ex);
            }
            if (!mergeAll.get()) {
                return false;
            }
            // update non-indexed files
            if (!updateIndex(project, root, realAdded, realRemoved, exceptions)) {
                return false;
            }
            for (FilePath f : realAdded) {
                VcsDirtyScopeManager.getInstance(project).fileDirty(f);
            }
            for (FilePath f : realRemoved) {
                VcsDirtyScopeManager.getInstance(project).fileDirty(f);
            }
        }
        // perform merge commit
        try {
            commitWithoutPaths(project, root, messageFile, author);
            GitRepositoryManager manager = getRepositoryManager(project);
            manager.updateRepository(root);
        }
        catch (VcsException ex) {
            exceptions.add(ex);
            return false;
        }
        return true;
    }

    private void commitWithoutPaths(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull File messageFile,
        @Nullable String author
    ) throws VcsException {
        GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.COMMIT);
        handler.setStdoutSuppressed(false);
        handler.addParameters("-F", messageFile.getAbsolutePath());
        if (author != null) {
            handler.addParameters("--author=" + author);
        }
        if (myNextCommitSignOff) {
            handler.addParameters("--signoff");
        }
        handler.endOptions();
        handler.run();
    }

    /**
     * Check if commit has failed due to unfinished merge or cherry-pick.
     *
     * @param ex an exception to examine
     * @return true if exception means that there is a partial commit during merge
     */
    private static PartialOperation isMergeCommit(VcsException ex) {
        String message = ex.getMessage();
        if (message.contains("fatal: cannot do a partial commit during a merge")) {
            return PartialOperation.MERGE;
        }
        if (message.contains("fatal: cannot do a partial commit during a cherry-pick")) {
            return PartialOperation.CHERRY_PICK;
        }
        return PartialOperation.NONE;
    }

    /**
     * Update index (delete and remove files)
     *
     * @param project    the project
     * @param root       a vcs root
     * @param added      added/modified files to commit
     * @param removed    removed files to commit
     * @param exceptions a list of exceptions to update
     * @return true if index was updated successfully
     */
    private static boolean updateIndex(
        Project project,
        VirtualFile root,
        Collection<FilePath> added,
        Collection<FilePath> removed,
        List<VcsException> exceptions
    ) {
        boolean rc = true;
        if (!added.isEmpty()) {
            try {
                GitFileUtils.addPaths(project, root, added);
            }
            catch (VcsException ex) {
                exceptions.add(ex);
                rc = false;
            }
        }
        if (!removed.isEmpty()) {
            try {
                GitFileUtils.delete(project, root, removed, "--ignore-unmatch");
            }
            catch (VcsException ex) {
                exceptions.add(ex);
                rc = false;
            }
        }
        return rc;
    }

    /**
     * Create a file that contains the specified message
     *
     * @param root    a git repository root
     * @param message a message to write
     * @return a file reference
     * @throws IOException if file cannot be created
     */
    private File createMessageFile(VirtualFile root, String message) throws IOException {
        // filter comment lines
        File file = FileUtil.createTempFile(GIT_COMMIT_MSG_FILE_PREFIX, GIT_COMMIT_MSG_FILE_EXT);
        file.deleteOnExit();
        String encoding = GitConfigUtil.getCommitEncoding(myProject, root);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), encoding)) {
            out.write(message);
        }
        return file;
    }

    @Override
    public List<VcsException> scheduleMissingFileForDeletion(List<FilePath> files) {
        List<VcsException> rc = new ArrayList<>();
        Map<VirtualFile, List<FilePath>> sortedFiles;
        try {
            sortedFiles = GitUtil.sortFilePathsByGitRoot(files);
        }
        catch (VcsException e) {
            rc.add(e);
            return rc;
        }
        for (Map.Entry<VirtualFile, List<FilePath>> e : sortedFiles.entrySet()) {
            try {
                VirtualFile root = e.getKey();
                GitFileUtils.delete(myProject, root, e.getValue());
                markRootDirty(root);
            }
            catch (VcsException ex) {
                rc.add(ex);
            }
        }
        return rc;
    }

    private void commit(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull Collection<FilePath> files,
        @Nonnull File message
    ) throws VcsException {
        boolean amend = myNextCommitAmend;
        for (List<String> paths : VcsFileUtil.chunkPaths(root, files)) {
            GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.COMMIT);
            handler.setStdoutSuppressed(false);
            if (myNextCommitSignOff) {
                handler.addParameters("--signoff");
            }
            if (amend) {
                handler.addParameters("--amend");
            }
            else {
                amend = true;
            }
            handler.addParameters("--only", "-F", message.getAbsolutePath());
            if (myNextCommitAuthor != null) {
                handler.addParameters("--author=" + myNextCommitAuthor);
            }
            if (myNextCommitAuthorDate != null) {
                handler.addParameters("--date", COMMIT_DATE_FORMAT.format(myNextCommitAuthorDate));
            }
            handler.endOptions();
            handler.addParameters(paths);
            handler.run();
        }
        if (!project.isDisposed()) {
            GitRepositoryManager manager = getRepositoryManager(project);
            manager.updateRepository(root);
        }
    }

    @Override
    public List<VcsException> scheduleUnversionedFilesForAddition(List<VirtualFile> files) {
        List<VcsException> rc = new ArrayList<>();
        Map<VirtualFile, List<VirtualFile>> sortedFiles;
        try {
            sortedFiles = GitUtil.sortFilesByGitRoot(files);
        }
        catch (VcsException e) {
            rc.add(e);
            return rc;
        }
        for (Map.Entry<VirtualFile, List<VirtualFile>> e : sortedFiles.entrySet()) {
            try {
                VirtualFile root = e.getKey();
                GitFileUtils.addFiles(myProject, root, e.getValue());
                markRootDirty(root);
            }
            catch (VcsException ex) {
                rc.add(ex);
            }
        }
        return rc;
    }

    private enum PartialOperation {
        NONE("none"),
        MERGE("merge"),
        CHERRY_PICK("cherry-pick");

        private final String myName;

        PartialOperation(String name) {
            myName = name;
        }

        String getName() {
            return myName;
        }
    }

    private static Map<VirtualFile, Collection<Change>> sortChangesByGitRoot(@Nonnull List<Change> changes, List<VcsException> exceptions) {
        Map<VirtualFile, Collection<Change>> result = new HashMap<>();
        for (Change change : changes) {
            ContentRevision afterRevision = change.getAfterRevision();
            ContentRevision beforeRevision = change.getBeforeRevision();
            // nothing-to-nothing change cannot happen.
            assert beforeRevision != null || afterRevision != null;
            // note that any path will work, because changes could happen within single vcs root
            FilePath filePath = afterRevision != null ? afterRevision.getFile() : beforeRevision.getFile();
            VirtualFile vcsRoot;
            try {
                // the parent paths for calculating roots in order to account for submodules that contribute
                // to the parent change. The path "." is never is valid change, so there should be no problem
                // with it.
                vcsRoot = GitUtil.getGitRoot(filePath.getParentPath());
            }
            catch (VcsException e) {
                exceptions.add(e);
                continue;
            }
            Collection<Change> changeList = result.get(vcsRoot);
            if (changeList == null) {
                changeList = new ArrayList<>();
                result.put(vcsRoot, changeList);
            }
            changeList.add(change);
        }
        return result;
    }

    private void markRootDirty(VirtualFile root) {
        // Note that the root is invalidated because changes are detected per-root anyway.
        // Otherwise it is not possible to detect moves.
        myDirtyScopeManager.dirDirtyRecursively(root);
    }

    public void reset() {
        myNextCommitAmend = false;
        myNextCommitAuthor = null;
        myNextCommitIsPushed = null;
        myNextCommitAuthorDate = null;
    }

    private class GitCheckinOptions implements CheckinChangeListSpecificComponent, RefreshableOnComponent {
        @Nonnull
        private final GitVcs myVcs;
        @Nonnull
        private JPanel myPanel;
        @Nonnull
        private final EditorTextField myAuthorField;
        @Nullable
        private Date myAuthorDate;
        @Nonnull
        private AmendComponent myAmendComponent;
        @Nonnull
        private final JCheckBox mySignOffCheckbox;

        GitCheckinOptions(@Nonnull Project project, @Nonnull CheckinProjectPanel panel) {
            myVcs = assertNotNull(GitVcs.getInstance(project));

            myAuthorField = createTextField(project, getAuthors(project));
            myAuthorField.setToolTipText(GitLocalize.commitAuthorTooltip().get());
            Label authorLabel = Label.create(GitLocalize.commitAuthor());
            authorLabel.setTarget(TargetAWT.wrap(myAuthorField));

            myAmendComponent = new MyAmendComponent(project, getRepositoryManager(project), panel);
            mySignOffCheckbox = new JBCheckBox("Sign-off commit", mySettings.shouldSignOffCommit());
            mySignOffCheckbox.setMnemonic(KeyEvent.VK_G);
            mySignOffCheckbox.setToolTipText(getToolTip(project, panel));

            GridBag gb = new GridBag().
                setDefaultAnchor(GridBagConstraints.WEST).
                setDefaultInsets(JBUI.insets(2));
            myPanel = new JPanel(new GridBagLayout());
            myPanel.add(TargetAWT.to(authorLabel), gb.nextLine().next());
            myPanel.add(myAuthorField, gb.next().fillCellHorizontally().weightx(1));
            myPanel.add(mySignOffCheckbox, gb.nextLine().next().coverLine());
            myPanel.add(myAmendComponent.getComponent(), gb.nextLine().next().coverLine());
        }

        @Nonnull
        private String getToolTip(@Nonnull Project project, @Nonnull CheckinProjectPanel panel) {
            VcsUser user = getFirstItem(mapNotNull(panel.getRoots(), it -> GitUserRegistry.getInstance(project).getUser(it)));
            String signature = user != null ? escapeXml(VcsUserUtil.toExactString(user)) : "";
            return "<html>Adds the following line at the end of the commit message:<br/>" +
                "Signed-off by: " + signature + "</html>";
        }

        @Nonnull
        private List<String> getAuthors(@Nonnull Project project) {
            Set<String> authors = new HashSet<>(getUsersList(project));
            addAll(authors, mySettings.getCommitAuthors());
            List<String> list = new ArrayList<>(authors);
            Collections.sort(list);
            return list;
        }

        @Nonnull
        private EditorTextField createTextField(@Nonnull Project project, @Nonnull List<String> list) {
            TextCompletionProvider completionProvider = new ValuesCompletionProvider.ValuesCompletionProviderDumbAware<>(
                new DefaultTextCompletionValueDescriptor.StringValueDescriptor(),
                list
            );
            return new TextFieldWithCompletion(project, completionProvider, "", true, true, true);
        }

        private class MyAmendComponent extends AmendComponent {
            public MyAmendComponent(@Nonnull Project project, @Nonnull GitRepositoryManager manager, @Nonnull CheckinProjectPanel panel) {
                super(project, manager, panel);
            }

            @Nonnull
            @Override
            protected Set<VirtualFile> getVcsRoots(@Nonnull Collection<FilePath> files) {
                return GitUtil.gitRoots(files);
            }

            @Nullable
            @Override
            protected String getLastCommitMessage(@Nonnull VirtualFile root) throws VcsException {
                GitSimpleHandler h = new GitSimpleHandler(myProject, root, GitCommand.LOG);
                h.addParameters("--max-count=1");
                String formatPattern;
                if (GitVersionSpecialty.STARTED_USING_RAW_BODY_IN_FORMAT.existsIn(myVcs.getVersion())) {
                    formatPattern = "%B";
                }
                else {
                    // only message: subject + body; "%-b" means that preceding line-feeds will be deleted if the body is empty
                    // %s strips newlines from subject; there is no way to work around it before 1.7.2 with %B (unless parsing some fixed format)
                    formatPattern = "%s%n%n%-b";
                }
                h.addParameters("--pretty=format:" + formatPattern);
                return h.run();
            }
        }

        @Nonnull
        private List<String> getUsersList(@Nonnull Project project) {
            VcsUserRegistry userRegistry = ServiceManager.getService(project, VcsUserRegistry.class);
            return map(userRegistry.getUsers(), VcsUserUtil::toExactString);
        }

        @Override
        @RequiredUIAccess
        public void refresh() {
            myAmendComponent.refresh();
            myAuthorField.setText(null);
            myAuthorDate = null;
            reset();
        }

        @Override
        public void saveState() {
            String author = myAuthorField.getText();
            if (StringUtil.isEmptyOrSpaces(author)) {
                myNextCommitAuthor = null;
            }
            else {
                myNextCommitAuthor = GitCommitAuthorCorrector.correct(author);
                mySettings.saveCommitAuthor(myNextCommitAuthor);
            }
            myNextCommitAmend = myAmendComponent.isAmend();
            myNextCommitAuthorDate = myAuthorDate;
            mySettings.setSignOffCommit(mySignOffCheckbox.isSelected());
            myNextCommitSignOff = mySignOffCheckbox.isSelected();
        }

        @Override
        @RequiredUIAccess
        public void restoreState() {
            refresh();
        }

        @Override
        @RequiredUIAccess
        public void onChangeListSelected(LocalChangeList list) {
            if (list.getData() instanceof VcsFullCommitDetails commit) {
                String author = VcsUserUtil.toExactString(commit.getAuthor());
                myAuthorField.setText(author);
                myAuthorDate = new Date(commit.getAuthorTime());
            }
            else {
                myAuthorField.setText(null);
                myAuthorDate = null;
            }
        }

        @Override
        public JComponent getComponent() {
            return myPanel;
        }
    }

    public void setNextCommitIsPushed(Boolean nextCommitIsPushed) {
        myNextCommitIsPushed = nextCommitIsPushed;
    }
}
