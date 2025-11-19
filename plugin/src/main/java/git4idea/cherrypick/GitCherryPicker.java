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
package git4idea.cherrypick;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.AccessToken;
import consulo.application.Application;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.AbstractVcsHelper;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.*;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.VcsCherryPicker;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.VcsFullCommitDetails;
import consulo.versionControlSystem.log.VcsLog;
import consulo.versionControlSystem.log.util.VcsUserUtil;
import consulo.versionControlSystem.merge.MergeDialogCustomizer;
import consulo.versionControlSystem.update.RefreshVFsSynchronously;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitSimpleEventDetector;
import git4idea.commands.GitUntrackedFilesOverwrittenByOperationDetector;
import git4idea.config.GitVcsSettings;
import git4idea.merge.GitConflictResolver;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitUntrackedFilesHelper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static consulo.util.lang.StringUtil.pluralize;
import static consulo.versionControlSystem.distributed.DvcsUtil.getShortRepositoryName;
import static git4idea.commands.GitSimpleEventDetector.Event.CHERRY_PICK_CONFLICT;
import static git4idea.commands.GitSimpleEventDetector.Event.LOCAL_CHANGES_OVERWRITTEN_BY_CHERRY_PICK;

@ExtensionImpl
public class GitCherryPicker extends VcsCherryPicker {
    private static final Logger LOG = Logger.getInstance(GitCherryPicker.class);

    @Nonnull
    private final Project myProject;
    @Nonnull
    protected final NotificationService myNotificationService;
    @Nonnull
    private final Git myGit;
    @Nonnull
    private final ChangeListManager myChangeListManager;
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;

    @Inject
    public GitCherryPicker(@Nonnull Project project, @Nonnull Git git) {
        myProject = project;
        myNotificationService = NotificationService.getInstance();
        myGit = git;
        myChangeListManager = ChangeListManager.getInstance(myProject);
        myRepositoryManager = GitUtil.getRepositoryManager(myProject);
    }

    @Override
    @RequiredUIAccess
    public void cherryPick(@Nonnull List<VcsFullCommitDetails> commits) {
        Map<GitRepository, List<VcsFullCommitDetails>> commitsInRoots = DvcsUtil.groupCommitsByRoots(myRepositoryManager, commits);
        LOG.info("Cherry-picking commits: " + toString(commitsInRoots));
        List<GitCommitWrapper> successfulCommits = new ArrayList<>();
        List<GitCommitWrapper> alreadyPicked = new ArrayList<>();
        try (AccessToken token = DvcsUtil.workingTreeChangeStarted(myProject, getActionTitle())) {
            for (Map.Entry<GitRepository, List<VcsFullCommitDetails>> entry : commitsInRoots.entrySet()) {
                GitRepository repository = entry.getKey();
                boolean result = cherryPick(repository, entry.getValue(), successfulCommits, alreadyPicked);
                repository.update();
                if (!result) {
                    return;
                }
            }
            notifyResult(successfulCommits, alreadyPicked);
        }
    }

    @Nonnull
    private static String toString(@Nonnull Map<GitRepository, List<VcsFullCommitDetails>> commitsInRoots) {
        return StringUtil.join(
            commitsInRoots.keySet(),
            repository -> {
                String commits = StringUtil.join(
                    commitsInRoots.get(repository),
                    details -> details.getId().asString(),
                    ", "
                );
                return getShortRepositoryName(repository) + ": [" + commits + "]";
            },
            "; "
        );
    }

    // return true to continue with other roots, false to break execution
    @RequiredUIAccess
    private boolean cherryPick(
        @Nonnull GitRepository repository,
        @Nonnull List<VcsFullCommitDetails> commits,
        @Nonnull List<GitCommitWrapper> successfulCommits,
        @Nonnull List<GitCommitWrapper> alreadyPicked
    ) {
        for (VcsFullCommitDetails commit : commits) {
            GitSimpleEventDetector conflictDetector = new GitSimpleEventDetector(CHERRY_PICK_CONFLICT);
            GitSimpleEventDetector localChangesOverwrittenDetector = new GitSimpleEventDetector(LOCAL_CHANGES_OVERWRITTEN_BY_CHERRY_PICK);
            GitUntrackedFilesOverwrittenByOperationDetector untrackedFilesDetector =
                new GitUntrackedFilesOverwrittenByOperationDetector(repository.getRoot());
            boolean autoCommit = isAutoCommit();
            GitCommandResult result = myGit.cherryPick(
                repository,
                commit.getId().asString(),
                autoCommit,
                conflictDetector,
                localChangesOverwrittenDetector,
                untrackedFilesDetector
            );
            GitCommitWrapper commitWrapper = new GitCommitWrapper(commit);
            if (result.success()) {
                if (autoCommit) {
                    successfulCommits.add(commitWrapper);
                }
                else {
                    boolean committed = updateChangeListManagerShowCommitDialogAndRemoveChangeListOnSuccess(
                        repository,
                        commitWrapper,
                        successfulCommits,
                        alreadyPicked
                    );
                    if (!committed) {
                        notifyCommitCancelled(commitWrapper, successfulCommits);
                        return false;
                    }
                }
            }
            else if (conflictDetector.hasHappened()) {
                boolean mergeCompleted = new CherryPickConflictResolver(
                    myProject,
                    myGit,
                    repository.getRoot(),
                    commit.getId().asString(),
                    VcsUserUtil.getShortPresentation(commit.getAuthor()),
                    commit.getSubject()
                ).merge();

                if (mergeCompleted) {
                    boolean committed = updateChangeListManagerShowCommitDialogAndRemoveChangeListOnSuccess(
                        repository,
                        commitWrapper,
                        successfulCommits,
                        alreadyPicked
                    );
                    if (!committed) {
                        notifyCommitCancelled(commitWrapper, successfulCommits);
                        return false;
                    }
                }
                else {
                    updateChangeListManager(commit);
                    notifyConflictWarning(repository, commitWrapper, successfulCommits);
                    return false;
                }
            }
            else if (untrackedFilesDetector.wasMessageDetected()) {
                String description = commitDetails(commitWrapper) + "<br/>" +
                    "Some untracked working tree files would be overwritten by cherry-pick.<br/>" +
                    "Please move, remove or add them before you can cherry-pick. <a href='view'>View them</a>";
                description += getSuccessfulCommitDetailsIfAny(successfulCommits);

                GitUntrackedFilesHelper.notifyUntrackedFilesOverwrittenBy(
                    myProject,
                    repository.getRoot(),
                    untrackedFilesDetector.getRelativeFilePaths(),
                    LocalizeValue.localizeTODO("cherry-pick"),
                    LocalizeValue.localizeTODO(description)
                );
                return false;
            }
            else if (localChangesOverwrittenDetector.hasHappened()) {
                notifyError(
                    LocalizeValue.localizeTODO(
                        "Your local changes would be overwritten by cherry-pick.<br/>Commit your changes or stash them to proceed."
                    ),
                    commitWrapper,
                    successfulCommits
                );
                return false;
            }
            else if (isNothingToCommitMessage(result)) {
                alreadyPicked.add(commitWrapper);
                return true;
            }
            else {
                notifyError(result.getErrorOutputAsHtmlValue(), commitWrapper, successfulCommits);
                return false;
            }
        }
        return true;
    }

    private static boolean isNothingToCommitMessage(@Nonnull GitCommandResult result) {
        if (!result.getErrorOutputAsJoinedString().isEmpty()) {
            return false;
        }
        String stdout = result.getOutputAsJoinedString();
        return stdout.contains("nothing to commit") || stdout.contains("previous cherry-pick is now empty");
    }

    @RequiredUIAccess
    private boolean updateChangeListManagerShowCommitDialogAndRemoveChangeListOnSuccess(
        @Nonnull GitRepository repository,
        @Nonnull GitCommitWrapper commit,
        @Nonnull List<GitCommitWrapper> successfulCommits,
        @Nonnull List<GitCommitWrapper> alreadyPicked
    ) {
        CherryPickData data = updateChangeListManager(commit.getCommit());
        if (data == null) {
            alreadyPicked.add(commit);
            return true;
        }
        boolean committed = showCommitDialogAndWaitForCommit(repository, commit, data.myChangeList, data.myCommitMessage);
        if (committed) {
            myChangeListManager.removeChangeList(data.myChangeList);
            successfulCommits.add(commit);
            return true;
        }
        return false;
    }

    private void notifyConflictWarning(
        @Nonnull GitRepository repository,
        @Nonnull GitCommitWrapper commit,
        @Nonnull List<GitCommitWrapper> successfulCommits
    ) {
        NotificationListener resolveLinkListener = new ResolveLinkListener(
            myProject,
            myGit,
            repository.getRoot(),
            commit.getCommit().getId().toShortString(),
            VcsUserUtil.getShortPresentation(commit.getCommit().getAuthor()),
            commit.getSubject()
        );
        String description =
            commitDetails(commit) + "<br/>Unresolved conflicts remain in the working tree. <a href='resolve'>Resolve them.<a/>";
        description += getSuccessfulCommitDetailsIfAny(successfulCommits);
        myNotificationService.newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Cherry-picked with conflicts"))
            .content(LocalizeValue.localizeTODO(description))
            .hyperlinkListener(resolveLinkListener)
            .notify(myProject);
    }

    private void notifyCommitCancelled(@Nonnull GitCommitWrapper commit, @Nonnull List<GitCommitWrapper> successfulCommits) {
        if (successfulCommits.isEmpty()) {
            // don't notify about cancelled commit. Notify just in the case when there were already successful commits in the queue.
            return;
        }
        String description = commitDetails(commit);
        description += getSuccessfulCommitDetailsIfAny(successfulCommits);
        myNotificationService.newWarn(VcsNotifier.STANDARD_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Cherry-pick cancelled"))
            .content(LocalizeValue.localizeTODO(description))
            .notify(myProject);
    }

    @Nullable
    @RequiredUIAccess
    private CherryPickData updateChangeListManager(@Nonnull VcsFullCommitDetails commit) {
        Collection<Change> changes = commit.getChanges();
        RefreshVFsSynchronously.updateChanges(changes);
        String commitMessage = createCommitMessage(commit);
        Collection<FilePath> paths = ChangesUtil.getPaths(changes);
        LocalChangeList changeList = createChangeListAfterUpdate(commit, paths, commitMessage);
        return changeList == null ? null : new CherryPickData(changeList, commitMessage);
    }

    @Nullable
    @RequiredUIAccess
    private LocalChangeList createChangeListAfterUpdate(
        @Nonnull VcsFullCommitDetails commit,
        @Nonnull Collection<FilePath> paths,
        @Nonnull String commitMessage
    ) {
        CountDownLatch waiter = new CountDownLatch(1);
        AtomicReference<LocalChangeList> changeList = new AtomicReference<>();
        Application application = myProject.getApplication();
        application.invokeAndWait(
            () -> myChangeListManager.invokeAfterUpdate(
                () -> {
                    changeList.set(createChangeListIfThereAreChanges(commit, commitMessage));
                    waiter.countDown();
                },
                InvokeAfterUpdateMode.SILENT_CALLBACK_POOLED,
                "Cherry-pick",
                vcsDirtyScopeManager -> vcsDirtyScopeManager.filePathsDirty(paths, null),
                application.getNoneModalityState()
            ),
            application.getNoneModalityState()
        );
        try {
            boolean success = waiter.await(100, TimeUnit.SECONDS);
            if (!success) {
                LOG.error("Couldn't await for changelist manager refresh");
            }
        }
        catch (InterruptedException e) {
            LOG.error(e);
            return null;
        }

        return changeList.get();
    }

    @Nonnull
    private static String createCommitMessage(@Nonnull VcsFullCommitDetails commit) {
        return commit.getFullMessage() + "\n\n(cherry picked from commit " + commit.getId().toShortString() + ")";
    }

    @RequiredUIAccess
    private boolean showCommitDialogAndWaitForCommit(
        @Nonnull GitRepository repository,
        @Nonnull final GitCommitWrapper commit,
        @Nonnull LocalChangeList changeList,
        @Nonnull String commitMessage
    ) {
        final AtomicBoolean commitSucceeded = new AtomicBoolean();
        final Semaphore sem = new Semaphore(0);
        myProject.getApplication().invokeAndWait(
            () -> {
                try {
                    cancelCherryPick(repository);
                    Collection<Change> changes = commit.getCommit().getChanges();
                    boolean commitNotCancelled = AbstractVcsHelper.getInstance(myProject).commitChanges(
                        changes,
                        changeList,
                        commitMessage,
                        new CommitResultHandler() {
                            @Override
                            public void onSuccess(@Nonnull String commitMessage1) {
                                commit.setActualSubject(commitMessage1);
                                commitSucceeded.set(true);
                                sem.release();
                            }

                            @Override
                            public void onFailure() {
                                commitSucceeded.set(false);
                                sem.release();
                            }
                        }
                    );

                    if (!commitNotCancelled) {
                        commitSucceeded.set(false);
                        sem.release();
                    }
                }
                catch (Throwable t) {
                    LOG.error(t);
                    commitSucceeded.set(false);
                    sem.release();
                }
            },
            myProject.getApplication().getNoneModalityState()
        );

        // need additional waiting, because commitChanges is asynchronous
        try {
            sem.acquire();
        }
        catch (InterruptedException e) {
            LOG.error(e);
            return false;
        }
        return commitSucceeded.get();
    }

    /**
     * We control the cherry-pick workflow ourselves + we want to use partial commits ('git commit --only'), which is prohibited during
     * cherry-pick, i.e. until the CHERRY_PICK_HEAD exists.
     */
    @RequiredUIAccess
    private void cancelCherryPick(@Nonnull GitRepository repository) {
        if (isAutoCommit()) {
            removeCherryPickHead(repository);
        }
    }

    @RequiredUIAccess
    private void removeCherryPickHead(@Nonnull GitRepository repository) {
        File cherryPickHeadFile = repository.getRepositoryFiles().getCherryPickHead();
        final VirtualFile cherryPickHead = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(cherryPickHeadFile);

        if (cherryPickHead != null && cherryPickHead.exists()) {
            myProject.getApplication().runWriteAction(new Runnable() {
                @Override
                public void run() {
                    try {
                        cherryPickHead.delete(this);
                    }
                    catch (IOException e) {
                        // if CHERRY_PICK_HEAD is not deleted, the partial commit will fail, and the user will be notified anyway.
                        // So here we just log the fact. It is happens relatively often, maybe some additional solution will follow.
                        LOG.error(e);
                    }
                }
            });
        }
        else {
            LOG.info("Cancel cherry-pick in " + repository.getPresentableUrl() + ": no CHERRY_PICK_HEAD found");
        }
    }

    private void notifyError(
        @Nonnull LocalizeValue content,
        @Nonnull GitCommitWrapper failedCommit,
        @Nonnull List<GitCommitWrapper> successfulCommits
    ) {
        String description = commitDetails(failedCommit) + "<br/>" + content + getSuccessfulCommitDetailsIfAny(successfulCommits);
        myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Cherry-pick failed"))
            .content(LocalizeValue.localizeTODO(description))
            .notify(myProject);
    }

    @Nonnull
    private static String getSuccessfulCommitDetailsIfAny(@Nonnull List<GitCommitWrapper> successfulCommits) {
        String description = "";
        if (!successfulCommits.isEmpty()) {
            description +=
                "<hr/>However cherry-pick succeeded for the following " + pluralize("commit", successfulCommits.size()) + ":<br/>";
            description += getCommitsDetails(successfulCommits);
        }
        return description;
    }

    private void notifyResult(@Nonnull List<GitCommitWrapper> successfulCommits, @Nonnull List<GitCommitWrapper> alreadyPicked) {
        if (alreadyPicked.isEmpty()) {
            myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                .title(LocalizeValue.localizeTODO("Cherry-pick successful"))
                .content(LocalizeValue.localizeTODO(getCommitsDetails(successfulCommits)))
                .notify(myProject);
        }
        else if (!successfulCommits.isEmpty()) {
            String title = String.format(
                "Cherry-picked %d commits from %d",
                successfulCommits.size(),
                successfulCommits.size() + alreadyPicked.size()
            );
            String description = getCommitsDetails(successfulCommits) + "<hr/>" + formAlreadyPickedDescription(alreadyPicked, true);
            myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                .title(LocalizeValue.localizeTODO(title))
                .content(LocalizeValue.localizeTODO(description))
                .notify(myProject);
        }
        else {
            myNotificationService.newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Nothing to cherry-pick"))
                .content(LocalizeValue.localizeTODO(formAlreadyPickedDescription(alreadyPicked, false)))
                .notify(myProject);
        }
    }

    @Nonnull
    private static String formAlreadyPickedDescription(@Nonnull List<GitCommitWrapper> alreadyPicked, boolean but) {
        String hashes = StringUtil.join(alreadyPicked, commit -> commit.getCommit().getId().toShortString(), ", ");
        if (but) {
            String wasnt = alreadyPicked.size() == 1 ? "wasn't" : "weren't";
            String it = alreadyPicked.size() == 1 ? "it" : "them";
            return String.format("%s %s picked, because all changes from %s have already been applied.", hashes, wasnt, it);
        }
        return String.format("All changes from %s have already been applied", hashes);
    }

    @Nonnull
    private static String getCommitsDetails(@Nonnull List<GitCommitWrapper> successfulCommits) {
        String description = "";
        for (GitCommitWrapper commit : successfulCommits) {
            description += commitDetails(commit) + "<br/>";
        }
        return description.substring(0, description.length() - "<br/>".length());
    }

    @Nonnull
    private static String commitDetails(@Nonnull GitCommitWrapper commit) {
        return commit.getCommit().getId().toShortString() + " " + commit.getOriginalSubject();
    }

    @Nullable
    private LocalChangeList createChangeListIfThereAreChanges(@Nonnull VcsFullCommitDetails commit, @Nonnull String commitMessage) {
        Collection<Change> originalChanges = commit.getChanges();
        if (originalChanges.isEmpty()) {
            LOG.info("Empty commit " + commit.getId());
            return null;
        }
        if (noChangesAfterCherryPick(originalChanges)) {
            LOG.info("No changes after cherry-picking " + commit.getId());
            return null;
        }

        String changeListName = createNameForChangeList(commitMessage, 0).replace('\n', ' ');
        LocalChangeList createdChangeList = myChangeListManager.addChangeList(changeListName, commitMessage, commit);
        LocalChangeList actualChangeList = moveChanges(originalChanges, createdChangeList);
        if (actualChangeList != null && !actualChangeList.getChanges().isEmpty()) {
            return createdChangeList;
        }
        LOG.warn(
            "No changes were moved to the changelist. Changes from commit: " + originalChanges + "\n" +
                "All changes: " + myChangeListManager.getAllChanges()
        );
        myChangeListManager.removeChangeList(createdChangeList);
        return null;
    }

    private boolean noChangesAfterCherryPick(@Nonnull Collection<Change> originalChanges) {
        Collection<Change> allChanges = myChangeListManager.getAllChanges();
        return !ContainerUtil.exists(originalChanges, allChanges::contains);
    }

    @Nullable
    private LocalChangeList moveChanges(@Nonnull Collection<Change> originalChanges, @Nonnull final LocalChangeList targetChangeList) {
        Collection<Change> localChanges = GitUtil.findCorrespondentLocalChanges(myChangeListManager, originalChanges);

        // 1. We have to listen to CLM changes, because moveChangesTo is asynchronous
        // 2. We have to collect the real target change list,
        // because the original target list (passed to moveChangesTo) is not updated in time.
        final CountDownLatch moveChangesWaiter = new CountDownLatch(1);
        final AtomicReference<LocalChangeList> resultingChangeList = new AtomicReference<>();
        ChangeListAdapter listener = new ChangeListAdapter() {
            @Override
            public void changesMoved(Collection<Change> changes, ChangeList fromList, ChangeList toList) {
                if (toList instanceof LocalChangeList localChangeList && targetChangeList.getId().equals(localChangeList.getId())) {
                    resultingChangeList.set(localChangeList);
                    moveChangesWaiter.countDown();
                }
            }
        };
        try {
            myChangeListManager.addChangeListListener(listener);
            myChangeListManager.moveChangesTo(targetChangeList, ArrayUtil.toObjectArray(localChanges, Change.class));
            boolean success = moveChangesWaiter.await(100, TimeUnit.SECONDS);
            if (!success) {
                LOG.error("Couldn't await for changes move.");
            }
            return resultingChangeList.get();
        }
        catch (InterruptedException e) {
            LOG.error(e);
            return null;
        }
        finally {
            myChangeListManager.removeChangeListListener(listener);
        }
    }

    @Nonnull
    private String createNameForChangeList(@Nonnull String proposedName, int step) {
        for (LocalChangeList list : myChangeListManager.getChangeLists()) {
            if (list.getName().equals(nameWithStep(proposedName, step))) {
                return createNameForChangeList(proposedName, step + 1);
            }
        }
        return nameWithStep(proposedName, step);
    }

    private static String nameWithStep(String name, int step) {
        return step == 0 ? name : name + "-" + step;
    }

    @Nonnull
    @Override
    public VcsKey getSupportedVcs() {
        return GitVcs.getKey();
    }

    @Nonnull
    @Override
    public String getActionTitle() {
        return "Cherry-Pick";
    }

    private boolean isAutoCommit() {
        return GitVcsSettings.getInstance(myProject).isAutoCommitOnCherryPick();
    }

    @Override
    public boolean canHandleForRoots(@Nonnull Collection<VirtualFile> roots) {
        return roots.stream().allMatch(r -> myRepositoryManager.getRepositoryForRoot(r) != null);
    }

    @Override
    public String getInfo(@Nonnull VcsLog log, @Nonnull Map<VirtualFile, List<Hash>> commits) {
        int commitsNum = commits.values().size();
        for (VirtualFile root : commits.keySet()) {
            // all these roots already related to this cherry-picker
            GitRepository repository = ObjectUtil.assertNotNull(myRepositoryManager.getRepositoryForRoot(root));
            for (Hash commit : commits.get(root)) {
                GitLocalBranch currentBranch = repository.getCurrentBranch();
                Collection<String> containingBranches = log.getContainingBranches(commit, root);
                if (currentBranch != null && containingBranches != null && containingBranches.contains(currentBranch.getName())) {
                    // already in the current branch
                    return String.format(
                        "The current branch already contains %s the selected %s",
                        commitsNum > 1 ? "one of" : "",
                        pluralize("commit", commitsNum)
                    );
                }
            }
        }
        return null;
    }

    private static class CherryPickData {
        @Nonnull
        private final LocalChangeList myChangeList;
        @Nonnull
        private final String myCommitMessage;

        private CherryPickData(@Nonnull LocalChangeList list, @Nonnull String message) {
            myChangeList = list;
            myCommitMessage = message;
        }
    }

    private static class CherryPickConflictResolver extends GitConflictResolver {
        public CherryPickConflictResolver(
            @Nonnull Project project,
            @Nonnull Git git,
            @Nonnull VirtualFile root,
            @Nonnull String commitHash,
            @Nonnull String commitAuthor,
            @Nonnull String commitMessage
        ) {
            super(project, git, Collections.singleton(root), makeParams(commitHash, commitAuthor, commitMessage));
        }

        private static Params makeParams(String commitHash, String commitAuthor, String commitMessage) {
            return new Params()
                .setErrorNotificationTitle(LocalizeValue.localizeTODO("Cherry-picked with conflicts"))
                .setMergeDialogCustomizer(new CherryPickMergeDialogCustomizer(commitHash, commitAuthor, commitMessage));
        }

        @Override
        protected void notifyUnresolvedRemain() {
            // we show a [possibly] compound notification after cherry-picking all commits.
        }
    }

    private static class ResolveLinkListener implements NotificationListener {
        @Nonnull
        private final Project myProject;
        @Nonnull
        private final Git myGit;
        @Nonnull
        private final VirtualFile myRoot;
        @Nonnull
        private final String myHash;
        @Nonnull
        private final String myAuthor;
        @Nonnull
        private final String myMessage;

        public ResolveLinkListener(
            @Nonnull Project project,
            @Nonnull Git git,
            @Nonnull VirtualFile root,
            @Nonnull String commitHash,
            @Nonnull String commitAuthor,
            @Nonnull String commitMessage
        ) {
            myProject = project;
            myGit = git;
            myRoot = root;
            myHash = commitHash;
            myAuthor = commitAuthor;
            myMessage = commitMessage;
        }

        @Override
        @RequiredUIAccess
        public void hyperlinkUpdate(@Nonnull Notification notification, @Nonnull HyperlinkEvent event) {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && "resolve".equals(event.getDescription())) {
                new CherryPickConflictResolver(myProject, myGit, myRoot, myHash, myAuthor, myMessage).mergeNoProceed();
            }
        }
    }

    private static class CherryPickMergeDialogCustomizer extends MergeDialogCustomizer {
        private String myCommitHash;
        private String myCommitAuthor;
        private String myCommitMessage;

        public CherryPickMergeDialogCustomizer(String commitHash, String commitAuthor, String commitMessage) {
            myCommitHash = commitHash;
            myCommitAuthor = commitAuthor;
            myCommitMessage = commitMessage;
        }

        @Override
        public String getMultipleFileMergeDescription(@Nonnull Collection<VirtualFile> files) {
            return "<html>Conflicts during cherry-picking commit <code>" + myCommitHash + "</code> made by " + myCommitAuthor + "<br/>" +
                "<code>\"" + myCommitMessage + "\"</code></html>";
        }

        @Override
        public String getLeftPanelTitle(@Nonnull VirtualFile file) {
            return "Local changes";
        }

        @Override
        public String getRightPanelTitle(@Nonnull VirtualFile file, VcsRevisionNumber revisionNumber) {
            return "<html>Changes from cherry-pick <code>" + myCommitHash + "</code>";
        }
    }

    /**
     * This class is needed to hold both the original GitCommit, and the commit message which could be changed by the user.
     * Only the subject of the commit message is needed.
     */
    private static class GitCommitWrapper {
        @Nonnull
        private final VcsFullCommitDetails myOriginalCommit;
        @Nonnull
        private String myActualSubject;

        private GitCommitWrapper(@Nonnull VcsFullCommitDetails commit) {
            myOriginalCommit = commit;
            myActualSubject = commit.getSubject();
        }

        @Nonnull
        public String getSubject() {
            return myActualSubject;
        }

        public void setActualSubject(@Nonnull String actualSubject) {
            myActualSubject = actualSubject;
        }

        @Nonnull
        public VcsFullCommitDetails getCommit() {
            return myOriginalCommit;
        }

        public String getOriginalSubject() {
            return myOriginalCommit.getSubject();
        }
    }
}
