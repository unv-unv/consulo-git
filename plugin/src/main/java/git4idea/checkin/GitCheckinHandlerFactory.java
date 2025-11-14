/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

import consulo.annotation.component.ExtensionImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.Couple;
import consulo.util.lang.StringUtil;
import consulo.util.lang.xml.XmlStringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.ChangesUtil;
import consulo.versionControlSystem.change.CommitExecutor;
import consulo.versionControlSystem.checkin.CheckinHandler;
import consulo.versionControlSystem.checkin.CheckinProjectPanel;
import consulo.versionControlSystem.checkin.VcsCheckinHandlerFactory;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.config.GitConfigUtil;
import git4idea.config.GitVcsSettings;
import git4idea.config.GitVersion;
import git4idea.config.GitVersionSpecialty;
import git4idea.crlf.GitCrlfDialog;
import git4idea.crlf.GitCrlfProblemsDetector;
import git4idea.crlf.GitCrlfUtil;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Prohibits committing with an empty message, warns if committing into detached HEAD, checks if user name and correct CRLF attributes
 * are set.
 *
 * @author Kirill Likhodedov
 */
@ExtensionImpl
public class GitCheckinHandlerFactory extends VcsCheckinHandlerFactory {
    private static final Logger LOG = Logger.getInstance(GitCheckinHandlerFactory.class);

    public GitCheckinHandlerFactory() {
        super(GitVcs.getKey());
    }

    @Nonnull
    @Override
    protected CheckinHandler createVcsHandler(CheckinProjectPanel panel) {
        return new MyCheckinHandler(panel);
    }

    private class MyCheckinHandler extends CheckinHandler {
        @Nonnull
        private final CheckinProjectPanel myPanel;
        @Nonnull
        private final Project myProject;


        public MyCheckinHandler(@Nonnull CheckinProjectPanel panel) {
            myPanel = panel;
            myProject = myPanel.getProject();
        }

        @Override
        @RequiredUIAccess
        public ReturnResult beforeCheckin(@Nullable CommitExecutor executor, BiConsumer<Object, Object> additionalDataConsumer) {
            if (emptyCommitMessage()) {
                return ReturnResult.CANCEL;
            }

            if (commitOrCommitAndPush(executor)) {
                ReturnResult result = checkUserName();
                if (result != ReturnResult.COMMIT) {
                    return result;
                }
                result = warnAboutCrlfIfNeeded();
                if (result != ReturnResult.COMMIT) {
                    return result;
                }
                return warnAboutDetachedHeadIfNeeded();
            }
            return ReturnResult.COMMIT;
        }

        @Nonnull
        private ReturnResult warnAboutCrlfIfNeeded() {
            GitVcsSettings settings = GitVcsSettings.getInstance(myProject);
            if (!settings.warnAboutCrlf()) {
                return ReturnResult.COMMIT;
            }

            final Git git = ServiceManager.getService(Git.class);

            final Collection<VirtualFile> files =
                myPanel.getVirtualFiles(); // deleted files aren't included, but for them we don't care about CRLFs.
            final AtomicReference<GitCrlfProblemsDetector> crlfHelper = new AtomicReference<>();
            ProgressManager.getInstance().run(
                new Task.Modal(myProject, "Checking for line separator issues...", true) {
                    @Override
                    public void run(@Nonnull ProgressIndicator indicator) {
                        crlfHelper.set(GitCrlfProblemsDetector.detect(
                            GitCheckinHandlerFactory.MyCheckinHandler.this.myProject,
                            git,
                            files
                        ));
                    }
                });

            if (crlfHelper.get() == null) { // detection cancelled
                return ReturnResult.CANCEL;
            }

            if (crlfHelper.get().shouldWarn()) {
                GitCrlfDialog dialog = new GitCrlfDialog(myProject);
                UIUtil.invokeAndWaitIfNeeded((Runnable)dialog::show);
                int decision = dialog.getExitCode();
                if (decision == GitCrlfDialog.CANCEL) {
                    return ReturnResult.CANCEL;
                }
                else {
                    if (decision == GitCrlfDialog.SET) {
                        VirtualFile anyRoot = myPanel.getRoots().iterator().next(); // config will be set globally => any root will do.
                        setCoreAutoCrlfAttribute(anyRoot);
                    }
                    else {
                        if (dialog.dontWarnAgain()) {
                            settings.setWarnAboutCrlf(false);
                        }
                    }
                    return ReturnResult.COMMIT;
                }
            }
            return ReturnResult.COMMIT;
        }

        private void setCoreAutoCrlfAttribute(@Nonnull VirtualFile aRoot) {
            try {
                GitConfigUtil.setValue(myProject, aRoot, GitConfigUtil.CORE_AUTOCRLF, GitCrlfUtil.RECOMMENDED_VALUE, "--global");
            }
            catch (VcsException e) {
                // it is not critical: the user just will get the dialog again next time
                LOG.warn("Couldn't globally set core.autocrlf in " + aRoot, e);
            }
        }

        @RequiredUIAccess
        private ReturnResult checkUserName() {
            Project project = myPanel.getProject();
            GitVcs vcs = GitVcs.getInstance(project);
            assert vcs != null;

            Collection<VirtualFile> notDefined = new ArrayList<>();
            Map<VirtualFile, Couple<String>> defined = new HashMap<>();
            Collection<VirtualFile> allRoots = new ArrayList<>(
                Arrays.asList(ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs))
            );

            Collection<VirtualFile> affectedRoots = getSelectedRoots();
            for (VirtualFile root : affectedRoots) {
                try {
                    Couple<String> nameAndEmail = getUserNameAndEmailFromGitConfig(project, root);
                    String name = nameAndEmail.getFirst();
                    String email = nameAndEmail.getSecond();
                    if (name == null || email == null) {
                        notDefined.add(root);
                    }
                    else {
                        defined.put(root, nameAndEmail);
                    }
                }
                catch (VcsException e) {
                    LOG.error("Couldn't get user.name and user.email for root " + root, e);
                    // doing nothing - let commit with possibly empty user.name/email
                }
            }

            if (notDefined.isEmpty()) {
                return ReturnResult.COMMIT;
            }

            GitVersion version = vcs.getVersion();
            if (System.getenv("HOME") == null && GitVersionSpecialty.DOESNT_DEFINE_HOME_ENV_VAR.existsIn(version)) {
                Messages.showErrorDialog(
                    project,
                    "You are using Git " + version + " which doesn't define %HOME% environment variable properly.\n" +
                        "Consider updating Git to a newer version " +
                        "or define %HOME% to point to the place where the global .gitconfig is stored \n" +
                        "(it is usually %USERPROFILE% or %HOMEDRIVE%%HOMEPATH%).",
                    "HOME Variable Is Not Defined"
                );
                return ReturnResult.CANCEL;
            }

            if (defined.isEmpty() && allRoots.size() > affectedRoots.size()) {
                allRoots.removeAll(affectedRoots);
                for (VirtualFile root : allRoots) {
                    try {
                        Couple<String> nameAndEmail = getUserNameAndEmailFromGitConfig(project, root);
                        String name = nameAndEmail.getFirst();
                        String email = nameAndEmail.getSecond();
                        if (name != null && email != null) {
                            defined.put(root, nameAndEmail);
                            break;
                        }
                    }
                    catch (VcsException e) {
                        LOG.error("Couldn't get user.name and user.email for root " + root, e);
                        // doing nothing - not critical not to find the values for other roots not affected by commit
                    }
                }
            }

            GitUserNameNotDefinedDialog dialog = new GitUserNameNotDefinedDialog(project, notDefined, affectedRoots, defined);
            dialog.show();
            if (dialog.isOK()) {
                try {
                    if (dialog.isGlobal()) {
                        GitConfigUtil.setValue(
                            project,
                            notDefined.iterator().next(),
                            GitConfigUtil.USER_NAME,
                            dialog.getUserName(),
                            "--global"
                        );
                        GitConfigUtil.setValue(
                            project,
                            notDefined.iterator().next(),
                            GitConfigUtil.USER_EMAIL,
                            dialog.getUserEmail(),
                            "--global"
                        );
                    }
                    else {
                        for (VirtualFile root : notDefined) {
                            GitConfigUtil.setValue(project, root, GitConfigUtil.USER_NAME, dialog.getUserName());
                            GitConfigUtil.setValue(project, root, GitConfigUtil.USER_EMAIL, dialog.getUserEmail());
                        }
                    }
                }
                catch (VcsException e) {
                    String message = "Couldn't set user.name and user.email";
                    LOG.error(message, e);
                    Messages.showErrorDialog(myPanel.getComponent(), message);
                    return ReturnResult.CANCEL;
                }
                return ReturnResult.COMMIT;
            }
            return ReturnResult.CLOSE_WINDOW;
        }

        @Nonnull
        private Couple<String> getUserNameAndEmailFromGitConfig(
            @Nonnull Project project,
            @Nonnull VirtualFile root
        ) throws VcsException {
            String name = GitConfigUtil.getValue(project, root, GitConfigUtil.USER_NAME);
            String email = GitConfigUtil.getValue(project, root, GitConfigUtil.USER_EMAIL);
            return Couple.of(name, email);
        }

        @RequiredUIAccess
        private boolean emptyCommitMessage() {
            if (myPanel.getCommitMessage().trim().isEmpty()) {
                Messages.showMessageDialog(
                    myPanel.getComponent(),
                    GitLocalize.gitCommitMessageEmpty().get(),
                    GitLocalize.gitCommitMessageEmptyTitle().get(),
                    UIUtil.getErrorIcon()
                );
                return true;
            }
            return false;
        }

        @RequiredUIAccess
        private ReturnResult warnAboutDetachedHeadIfNeeded() {
            // Warning: commit on a detached HEAD
            DetachedRoot detachedRoot = getDetachedRoot();
            if (detachedRoot == null) {
                return ReturnResult.COMMIT;
            }

            String title;
            String message;
            CharSequence rootPath = StringUtil.last(detachedRoot.myRoot.getPresentableUrl(), 50, true);
            String messageCommonStart = "The Git repository <code>" + rootPath + "</code>";
            if (detachedRoot.myRebase) {
                title = "Unfinished rebase process";
                message = messageCommonStart + " <br/> has an <b>unfinished rebase</b> process. <br/>" +
                    "You probably want to <b>continue rebase</b> instead of committing. <br/>" +
                    "Committing during rebase may lead to the commit loss. <br/>" +
                    readMore("http://www.kernel.org/pub/software/scm/git/docs/git-rebase.html", "Read more about Git rebase");
            }
            else {
                title = "Commit in detached HEAD may be dangerous";
                message = messageCommonStart + " is in the <b>detached HEAD</b> state. <br/>" +
                    "You can look around, make experimental changes and commit them, but be sure to checkout a branch not to lose your work. <br/>" +
                    "Otherwise you risk losing your changes. <br/>" +
                    readMore("http://sitaramc.github.com/concepts/detached-head.html", "Read more about detached HEAD");
            }

            int choice = Messages.showOkCancelDialog(
                myPanel.getComponent(),
                XmlStringUtil.wrapInHtml(message),
                title,
                "Cancel",
                "Commit",
                UIUtil.getWarningIcon()
            );
            if (choice == 1) {
                return ReturnResult.COMMIT;
            }
            else {
                return ReturnResult.CLOSE_WINDOW;
            }
        }

        private boolean commitOrCommitAndPush(@Nullable CommitExecutor executor) {
            return executor == null || executor instanceof GitCommitAndPushExecutor;
        }

        private String readMore(String link, String message) {
            return String.format("<a href='%s'>%s</a>.", link, message);
        }

        /**
         * Scans the Git roots, selected for commit, for the root which is on a detached HEAD.
         * Returns null, if all repositories are on the branch.
         * There might be several detached repositories, - in that case only one is returned.
         * This is because the situation is very rare, while it requires a lot of additional effort of making a well-formed message.
         */
        @Nullable
        private DetachedRoot getDetachedRoot() {
            GitRepositoryManager repositoryManager = GitUtil.getRepositoryManager(myPanel.getProject());
            for (VirtualFile root : getSelectedRoots()) {
                GitRepository repository = repositoryManager.getRepositoryForRoot(root);
                if (repository == null) {
                    continue;
                }
                if (!repository.isOnBranch()) {
                    return new DetachedRoot(root, repository.isRebaseInProgress());
                }
            }
            return null;
        }

        @Nonnull
        private Collection<VirtualFile> getSelectedRoots() {
            ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
            Collection<VirtualFile> result = new HashSet<>();
            for (FilePath path : ChangesUtil.getPaths(myPanel.getSelectedChanges())) {
                VirtualFile root = vcsManager.getVcsRootFor(path);
                if (root != null) {
                    result.add(root);
                }
            }
            return result;
        }

        private class DetachedRoot {
            final VirtualFile myRoot;
            final boolean myRebase; // rebase in progress, or just detached due to a checkout of a commit.

            public DetachedRoot(@Nonnull VirtualFile root, boolean rebase) {
                myRoot = root;
                myRebase = rebase;
            }
        }
    }
}
