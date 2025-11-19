/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package git4idea;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.progress.Task;
import consulo.configurable.Configurable;
import consulo.disposer.Disposer;
import consulo.execution.ui.console.ConsoleViewContentType;
import consulo.git.icon.GitIconGroup;
import consulo.git.localize.GitLocalize;
import consulo.ide.setting.ShowSettingsUtil;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.platform.Platform;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.NotificationType;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.ui.image.Image;
import consulo.versionControlSystem.*;
import consulo.versionControlSystem.change.ChangeProvider;
import consulo.versionControlSystem.change.CommitExecutor;
import consulo.versionControlSystem.change.VcsDirtyScopeBuilder;
import consulo.versionControlSystem.checkin.CheckinEnvironment;
import consulo.versionControlSystem.diff.DiffProvider;
import consulo.versionControlSystem.diff.RevisionSelector;
import consulo.versionControlSystem.history.VcsHistoryProvider;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.log.VcsUserRegistry;
import consulo.versionControlSystem.merge.MergeProvider;
import consulo.versionControlSystem.rollback.RollbackEnvironment;
import consulo.versionControlSystem.root.VcsRoot;
import consulo.versionControlSystem.root.VcsRootDetector;
import consulo.versionControlSystem.ui.VcsBalloonProblemNotifier;
import consulo.versionControlSystem.update.UpdateEnvironment;
import consulo.versionControlSystem.versionBrowser.CommittedChangeList;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.annotate.GitAnnotationProvider;
import git4idea.annotate.GitRepositoryForAnnotationsListener;
import git4idea.changes.GitCommittedChangeListProvider;
import git4idea.changes.GitOutgoingChangesProvider;
import git4idea.checkin.GitCheckinEnvironment;
import git4idea.checkin.GitCommitAndPushExecutor;
import git4idea.commands.Git;
import git4idea.config.*;
import git4idea.diff.GitDiffProvider;
import git4idea.diff.GitTreeDiffProvider;
import git4idea.history.GitHistoryProvider;
import git4idea.merge.GitMergeProvider;
import git4idea.rollback.GitRollbackEnvironment;
import git4idea.roots.GitIntegrationEnabler;
import git4idea.status.GitChangeProvider;
import git4idea.update.GitUpdateEnvironment;
import git4idea.util.GitVcsConsoleWriter;
import git4idea.vfs.GitVFSListener;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Git VCS implementation
 */
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
@Singleton
public class GitVcs extends AbstractVcs<CommittedChangeList> {
    public static final String NAME = "Git";
    public static final String ID = "git";

    private static final Logger log = Logger.getInstance(GitVcs.class);
    private static final VcsKey ourKey = createKey(NAME);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    @Nullable
    private final ChangeProvider myChangeProvider;
    @Nullable
    private final GitCheckinEnvironment myCheckinEnvironment;
    private final RollbackEnvironment myRollbackEnvironment;
    private final GitUpdateEnvironment myUpdateEnvironment;
    private final GitAnnotationProvider myAnnotationProvider;
    private final DiffProvider myDiffProvider;
    private final VcsHistoryProvider myHistoryProvider;
    private final NotificationService myNotificationService;
    @Nonnull
    private final Git myGit;
    private final GitVcsApplicationSettings myAppSettings;
    private final RevisionSelector myRevSelector;
    private final GitCommittedChangeListProvider myCommittedChangeListProvider;

    private GitVFSListener myVFSListener; // a VFS listener that tracks file addition, deletion, and renaming.

    private final ReadWriteLock myCommandLock = new ReentrantReadWriteLock(true); // The command read/write lock
    private final TreeDiffProvider myTreeDiffProvider;
    @Nullable
    private final GitCommitAndPushExecutor myCommitAndPushExecutor;
    private final GitExecutableValidator myExecutableValidator;

    private GitVersion myVersion = GitVersion.NULL; // version of Git which this plugin uses.
    private GitRepositoryForAnnotationsListener myRepositoryForAnnotationsListener;
    private final GitExecutableManager myGitExecutableManager;

    @Nullable
    public static GitVcs getInstance(Project project) {
        if (project == null || project.isDisposed()) {
            return null;
        }
        return (GitVcs) ProjectLevelVcsManager.getInstance(project).findVcsByName(NAME);
    }

    @Inject
    public GitVcs(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull GitAnnotationProvider gitAnnotationProvider,
        @Nonnull GitDiffProvider gitDiffProvider,
        @Nonnull GitHistoryProvider gitHistoryProvider,
        @Nonnull GitRollbackEnvironment gitRollbackEnvironment,
        @Nonnull GitVcsApplicationSettings gitSettings,
        @Nonnull GitVcsSettings gitProjectSettings,
        @Nonnull GitExecutableManager gitExecutableManager,
        @Nonnull NotificationService notificationService
    ) {
        super(project, NAME);
        myGit = git;
        myAppSettings = gitSettings;
        myChangeProvider = project.isDefault() ? null : project.getInstance(GitChangeProvider.class);
        myCheckinEnvironment = project.isDefault() ? null : project.getInstance(GitCheckinEnvironment.class);
        myAnnotationProvider = gitAnnotationProvider;
        myDiffProvider = gitDiffProvider;
        myHistoryProvider = gitHistoryProvider;
        myRollbackEnvironment = gitRollbackEnvironment;
        myGitExecutableManager = gitExecutableManager;
        myRevSelector = new GitRevisionSelector();
        myUpdateEnvironment = new GitUpdateEnvironment(myProject, gitProjectSettings);
        myCommittedChangeListProvider = new GitCommittedChangeListProvider(myProject);
        myOutgoingChangesProvider = new GitOutgoingChangesProvider(myProject);
        myTreeDiffProvider = new GitTreeDiffProvider(myProject);
        myCommitAndPushExecutor = myCheckinEnvironment != null ? new GitCommitAndPushExecutor(myCheckinEnvironment) : null;
        myExecutableValidator = new GitExecutableValidator(myProject);
        myNotificationService = notificationService;
    }


    public ReadWriteLock getCommandLock() {
        return myCommandLock;
    }

    /**
     * Run task in background using the common queue (per project)
     *
     * @param task the task to run
     */
    public static void runInBackground(Task.Backgroundable task) {
        task.queue();
    }

    @Override
    public CommittedChangesProvider getCommittedChangesProvider() {
        return myCommittedChangeListProvider;
    }

    @Override
    public String getRevisionPattern() {
        // return the full commit hash pattern, possibly other revision formats should be supported as well
        return "[0-9a-fA-F]+";
    }

    @Override
    @Nullable
    public CheckinEnvironment createCheckinEnvironment() {
        return myCheckinEnvironment;
    }

    @Nonnull
    @Override
    public MergeProvider getMergeProvider() {
        return GitMergeProvider.detect(myProject);
    }

    @Override
    @Nonnull
    public RollbackEnvironment createRollbackEnvironment() {
        return myRollbackEnvironment;
    }

    @Override
    @Nonnull
    public VcsHistoryProvider getVcsHistoryProvider() {
        return myHistoryProvider;
    }

    @Override
    public VcsHistoryProvider getVcsBlockHistoryProvider() {
        return myHistoryProvider;
    }

    @Override
    @Nonnull
    public LocalizeValue getDisplayName() {
        return GitLocalize.git4ideaVcsName();
    }

    @Override
    @Nullable
    public UpdateEnvironment createUpdateEnvironment() {
        return myUpdateEnvironment;
    }

    @Override
    @Nonnull
    public GitAnnotationProvider getAnnotationProvider() {
        return myAnnotationProvider;
    }

    @Override
    @Nonnull
    public DiffProvider getDiffProvider() {
        return myDiffProvider;
    }

    @Override
    @Nullable
    public RevisionSelector getRevisionSelector() {
        return myRevSelector;
    }

    @Override
    @Nullable
    public VcsRevisionNumber parseRevisionNumber(@Nullable String revision, @Nullable FilePath path) throws VcsException {
        if (revision == null || revision.length() == 0) {
            return null;
        }
        if (revision.length() > 40) {    // date & revision-id encoded string
            String dateString = revision.substring(0, revision.indexOf("["));
            String rev = revision.substring(revision.indexOf("[") + 1, 40);
            Date d = new Date(Date.parse(dateString));
            return new GitRevisionNumber(rev, d);
        }
        if (path != null) {
            try {
                VirtualFile root = GitUtil.getGitRoot(path);
                return GitRevisionNumber.resolve(myProject, root, revision);
            }
            catch (VcsException e) {
                log.info("Unexpected problem with resolving the git revision number: ", e);
                throw e;
            }
        }
        return new GitRevisionNumber(revision);

    }

    @Override
    @Nullable
    public VcsRevisionNumber parseRevisionNumber(@Nullable String revision) throws VcsException {
        return parseRevisionNumber(revision, null);
    }

    @Override
    public boolean isVersionedDirectory(VirtualFile dir) {
        return dir.isDirectory() && GitUtil.gitRootOrNull(dir) != null;
    }

    @Override
    protected void start() throws VcsException {
    }

    @Override
    protected void shutdown() throws VcsException {
    }

    @Override
    protected void activate() {
        Task.Backgroundable.queue(myProject, "Checking Git Version...", (i) -> checkExecutableAndVersion());

        if (myVFSListener == null) {
            myVFSListener = new GitVFSListener(myProject, this, myGit);
        }

        // make sure to read the registry before opening commit dialog
        myProject.getInstance(VcsUserRegistry.class);

        if (myRepositoryForAnnotationsListener == null) {
            myRepositoryForAnnotationsListener = new GitRepositoryForAnnotationsListener(myProject);
        }
        GitUserRegistry.getInstance(myProject).activate();
    }

    private void checkExecutableAndVersion() {
        boolean executableIsAlreadyCheckedAndFine = false;
        String pathToGit = myGitExecutableManager.getPathToGit(myProject);
        if (!pathToGit.contains(File.separator)) { // no path, just sole executable, with a hope that it is in path
            // subject to redetect the path if executable validator fails
            if (!myExecutableValidator.isExecutableValid()) {
                myAppSettings.setPathToGit(new GitExecutableDetector().detect());
            }
            else {
                executableIsAlreadyCheckedAndFine = true; // not to check it twice
            }
        }

        if (executableIsAlreadyCheckedAndFine || myExecutableValidator.checkExecutableAndNotifyIfNeeded()) {
            checkVersion();
        }
    }

    @Override
    protected void deactivate() {
        if (myVFSListener != null) {
            Disposer.dispose(myVFSListener);
            myVFSListener = null;
        }
    }

    @Nullable
    @Override
    public Configurable getConfigurable() {
        return null;
    }

    @Nullable
    @Override
    public ChangeProvider getChangeProvider() {
        return myChangeProvider;
    }

    /**
     * Show errors as popup and as messages in vcs view.
     *
     * @param list   a list of errors
     * @param action an action
     */
    public void showErrors(@Nonnull List<VcsException> list, @Nonnull LocalizeValue action) {
        if (list.size() > 0) {
            StringBuilder buffer = new StringBuilder();
            buffer.append("\n");
            buffer.append(GitLocalize.errorListTitle(action));
            for (VcsException exception : list) {
                buffer.append("\n");
                buffer.append(exception.getMessage());
            }
            String msg = buffer.toString();
            UIUtil.invokeLaterIfNeeded(() -> Messages.showErrorDialog(myProject, msg, GitLocalize.errorDialogTitle().get()));
        }
    }

    /**
     * Shows a plain message in the Version Control Console.
     */
    public void showMessages(@Nonnull String message) {
        if (message.isEmpty()) {
            return;
        }
        showMessage(LocalizeValue.of(message), ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /**
     * Show message in the Version Control Console
     *
     * @param message     a message to show
     * @param contentType a style to use
     */
    private void showMessage(@Nonnull LocalizeValue message, @Nonnull ConsoleViewContentType contentType) {
        GitVcsConsoleWriter.getInstance(myProject).showMessage(message, contentType);
    }

    /**
     * Checks Git version and updates the myVersion variable.
     * In the case of exception or unsupported version reports the problem.
     * Note that unsupported version is also applied - some functionality might not work (we warn about that), but no need to disable at all.
     */
    public void checkVersion() {
        String executable = myGitExecutableManager.getPathToGit(myProject);
        try {
            myVersion = GitVersion.identifyVersion(executable);
            if (!myVersion.isSupported()) {
                log.info("Unsupported Git version: " + myVersion);
                final String SETTINGS_LINK = "settings";
                final String UPDATE_LINK = "update";
                String message = String.format(
                    "The <a href='" + SETTINGS_LINK + "'>configured</a> version of Git is not supported: %s.<br/> " +
                        "The minimal supported version is %s. Please <a href='" + UPDATE_LINK + "'>update</a>.",
                    myVersion,
                    GitVersion.MIN
                );
                myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                    .title(LocalizeValue.localizeTODO("Unsupported Git version"))
                    .content(LocalizeValue.localizeTODO(message))
                    .optionalHyperlinkListener(new NotificationListener.Adapter() {
                        @Override
                        protected void hyperlinkActivated(@Nonnull Notification notification, @Nonnull HyperlinkEvent e) {
                            if (SETTINGS_LINK.equals(e.getDescription())) {
                                ShowSettingsUtil.getInstance().showSettingsDialog(myProject, getConfigurable().getDisplayName().get());
                            }
                            else if (UPDATE_LINK.equals(e.getDescription())) {
                                Platform.current().openInBrowser("http://git-scm.com");
                            }
                        }
                    })
                    .notify(myProject);
            }
        }
        catch (Exception e) {
            if (getExecutableValidator().checkExecutableAndNotifyIfNeeded()) { // check executable before notifying error
                String reason = (e.getCause() != null ? e.getCause() : e).getMessage();
                LocalizeValue message = GitLocalize.vcsUnableToRunGit(executable, reason);
                if (!myProject.isDefault()) {
                    showMessage(message, ConsoleViewContentType.SYSTEM_OUTPUT);
                }
                VcsBalloonProblemNotifier.showOverVersionControlView(myProject, message.get(), NotificationType.ERROR);
            }
        }
    }

    @Nonnull
    @Override
    public Image getIcon() {
        return GitIconGroup.git();
    }

    /**
     * @return the version number of Git, which is used by IDEA. Or {@link GitVersion#NULL} if version info is unavailable yet.
     */
    @Nonnull
    public GitVersion getVersion() {
        return myVersion;
    }

    /**
     * Shows a command line message in the Version Control Console
     */
    public void showCommandLine(String cmdLine) {
        showMessage(LocalizeValue.of(DATE_FORMAT.format(new Date()) + ": " + cmdLine), ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /**
     * Shows error message in the Version Control Console
     */
    public void showErrorMessages(String line) {
        showMessage(LocalizeValue.of(line), ConsoleViewContentType.ERROR_OUTPUT);
    }

    @Override
    public boolean allowsNestedRoots() {
        return true;
    }

    @Nonnull
    @Override
    public <S> List<S> filterUniqueRoots(@Nonnull List<S> in, @Nonnull Function<S, VirtualFile> converter) {
        Collections.sort(in, Comparator.comparing(converter, FilePathComparator.getInstance()));

        for (int i = 1; i < in.size(); i++) {
            S sChild = in.get(i);
            VirtualFile child = converter.apply(sChild);
            VirtualFile childRoot = GitUtil.gitRootOrNull(child);
            if (childRoot == null) {
                // non-git file actually, skip it
                continue;
            }
            for (int j = i - 1; j >= 0; --j) {
                S sParent = in.get(j);
                VirtualFile parent = converter.apply(sParent);
                // the method check both that parent is an ancestor of the child and that they share common git root
                if (VirtualFileUtil.isAncestor(parent, child, false) && VirtualFileUtil.isAncestor(childRoot, parent, false)) {
                    in.remove(i);
                    //noinspection AssignmentToForLoopParameter
                    --i;
                    break;
                }
            }
        }
        return in;
    }

    @Override
    public RootsConvertor getCustomConvertor() {
        return GitRootConverter.INSTANCE;
    }

    public static VcsKey getKey() {
        return ourKey;
    }

    @Override
    public VcsType getType() {
        return VcsType.distributed;
    }

    private final VcsOutgoingChangesProvider<CommittedChangeList> myOutgoingChangesProvider;

    @Override
    protected VcsOutgoingChangesProvider<CommittedChangeList> getOutgoingProviderImpl() {
        return myOutgoingChangesProvider;
    }

    @Override
    public RemoteDifferenceStrategy getRemoteDifferenceStrategy() {
        return RemoteDifferenceStrategy.ASK_TREE_PROVIDER;
    }

    @Override
    protected TreeDiffProvider getTreeDiffProviderImpl() {
        return myTreeDiffProvider;
    }

    @Override
    public List<CommitExecutor> getCommitExecutors() {
        return myCommitAndPushExecutor != null
            ? Collections.<CommitExecutor>singletonList(myCommitAndPushExecutor)
            : Collections.<CommitExecutor>emptyList();
    }

    @Nonnull
    public GitExecutableValidator getExecutableValidator() {
        return myExecutableValidator;
    }

    @Override
    public boolean fileListenerIsSynchronous() {
        return false;
    }

    @Override
    @RequiredUIAccess
    public void enableIntegration() {
        myProject.getApplication().executeOnPooledThread((Runnable) () -> {
            Collection<VcsRoot> roots = myProject.getInstance(VcsRootDetector.class).detect();
            new GitIntegrationEnabler(GitVcs.this, myGit).enable(roots);
        });
    }

    @Nonnull
    @Override
    public LocalizeValue getShortNameWithMnemonic() {
        return GitLocalize.vcsNameWithMnemonic();
    }

    @Override
    public boolean isWithCustomMenu() {
        return true;
    }

    @Override
    public boolean needsCaseSensitiveDirtyScope() {
        return true;
    }

    @Nonnull
    @Override
    public VcsDirtyScopeBuilder createDirtyScope() {
        return new GitVcsDirtyScope(this, myProject);
    }
}
