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
package git4idea.rebase;

import consulo.application.progress.ProgressIndicator;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.branch.GitRebaseParams;
import git4idea.repo.GitRepository;
import git4idea.stash.GitChangesSaver;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static consulo.util.lang.ObjectUtil.assertNotNull;

/**
 * The utilities related to rebase functionality
 */
public class GitRebaseUtils {
    /**
     * The logger instance
     */
    private final static Logger LOG = Logger.getInstance(GitRebaseUtils.class.getName());

    /**
     * A private constructor for utility class
     */
    private GitRebaseUtils() {
    }

    public static void rebase(
        @Nonnull Project project,
        @Nonnull List<GitRepository> repositories,
        @Nonnull GitRebaseParams params,
        @Nonnull ProgressIndicator indicator
    ) {
        if (!isRebaseAllowed(project, repositories)) {
            return;  // TODO maybe move to the outside
        }
        new GitRebaseProcess(project, GitRebaseSpec.forNewRebase(project, params, repositories, indicator), null).rebase();
    }

    public static void continueRebase(@Nonnull Project project) {
        GitRebaseSpec spec = GitUtil.getRepositoryManager(project).getOngoingRebaseSpec();
        if (spec != null) {
            new GitRebaseProcess(project, spec, GitRebaseResumeMode.CONTINUE).rebase();
        }
        else {
            LOG.warn("Refusing to continue: no rebase spec");
            NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Can't Continue Rebase"))
                .content(LocalizeValue.localizeTODO("No rebase in progress"))
                .notify(project);
        }
    }

    public static void continueRebase(@Nonnull Project project, @Nonnull GitRepository repository, @Nonnull ProgressIndicator indicator) {
        GitRebaseSpec spec = GitRebaseSpec.forResumeInSingleRepository(project, repository, indicator);
        if (spec != null) {
            new GitRebaseProcess(project, spec, GitRebaseResumeMode.CONTINUE).rebase();
        }
        else {
            LOG.warn("Refusing to continue: no rebase spec");
            NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Can't Continue Rebase"))
                .content(LocalizeValue.localizeTODO("No rebase in progress"))
                .notify(project);
        }
    }

    public static void skipRebase(@Nonnull Project project) {
        GitRebaseSpec spec = GitUtil.getRepositoryManager(project).getOngoingRebaseSpec();
        if (spec != null) {
            new GitRebaseProcess(project, spec, GitRebaseResumeMode.SKIP).rebase();
        }
        else {
            LOG.warn("Refusing to skip: no rebase spec");
            NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Can't Continue Rebase"))
                .content(LocalizeValue.localizeTODO("No rebase in progress"))
                .notify(project);
        }
    }

    public static void skipRebase(@Nonnull Project project, @Nonnull GitRepository repository, @Nonnull ProgressIndicator indicator) {
        GitRebaseSpec spec = GitRebaseSpec.forResumeInSingleRepository(project, repository, indicator);
        if (spec != null) {
            new GitRebaseProcess(project, spec, GitRebaseResumeMode.SKIP).rebase();
        }
        else {
            LOG.warn("Refusing to skip: no rebase spec");
            NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Can't Continue Rebase"))
                .content(LocalizeValue.localizeTODO("No rebase in progress"))
                .notify(project);
        }
    }

    /**
     * Automatically detects the ongoing rebase process in the project and abort it.
     * Optionally rollbacks repositories which were already rebased during that detected multi-root rebase process.
     * <p>
     * Does nothing if no information about ongoing rebase is available, or if this information has become obsolete.
     */
    @RequiredUIAccess
    public static void abort(@Nonnull Project project, @Nonnull ProgressIndicator indicator) {
        GitRebaseSpec spec = GitUtil.getRepositoryManager(project).getOngoingRebaseSpec();
        if (spec != null) {
            new GitAbortRebaseProcess(
                project,
                spec.getOngoingRebase(),
                spec.getHeadPositionsToRollback(),
                spec.getInitialBranchNames(),
                indicator,
                spec.getSaver()
            ).abortWithConfirmation();
        }
        else {
            LOG.warn("Refusing to abort: no rebase spec");
            NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Can't Abort Rebase"))
                .content(LocalizeValue.localizeTODO("No rebase in progress"))
                .notify(project);
        }
    }

    /**
     * Abort the ongoing rebase process in the given repository.
     */
    @RequiredUIAccess
    public static void abort(@Nonnull Project project, @Nullable GitRepository repository, @Nonnull ProgressIndicator indicator) {
        new GitAbortRebaseProcess(
            project,
            repository,
            Collections.<GitRepository, String>emptyMap(),
            Collections.<GitRepository, String>emptyMap(),
            indicator,
            null
        ).abortWithConfirmation();
    }

    private static boolean isRebaseAllowed(@Nonnull Project project, @Nonnull Collection<GitRepository> repositories) {
        // TODO links to 'rebase', 'resolve conflicts', etc.
        for (GitRepository repository : repositories) {
            Repository.State state = repository.getState();
            String in = GitUtil.mention(repository);
            String message = null;
            switch (state) {
                case NORMAL:
                    if (repository.isFresh()) {
                        message = "Repository" + in + " is empty.";
                    }
                    break;
                case MERGING:
                    message = "There is an unfinished merge process" + in + ".<br/>You should complete the merge before starting a rebase";
                    break;
                case REBASING:
                    message = "There is an unfinished rebase process" + in + ".<br/>You should complete it before starting another rebase";
                    break;
                case GRAFTING:
                    message = "There is an unfinished cherry-pick process" + in + ".<br/>You should finish it before starting a rebase.";
                    break;
                case DETACHED:
                    message = "You are in the detached HEAD state" + in + ".<br/>Rebase is not possible.";
                    break;
                default:
                    LOG.error("Unknown state [" + state.name() + "]");
                    message = "Rebase is not possible" + in;
            }
            if (message != null) {
                NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                    .title(LocalizeValue.localizeTODO("Rebase not Allowed"))
                    .content(LocalizeValue.localizeTODO(message))
                    .notify(project);
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if the rebase is in the progress for the specified git root
     *
     * @param project
     * @param root    the git root
     * @return true if the rebase directory presents in the root
     */
    @Deprecated
    public static boolean isRebaseInTheProgress(@Nonnull Project project, @Nonnull VirtualFile root) {
        return getRebaseDir(project, root) != null;
    }

    /**
     * Get rebase directory
     *
     * @param root the vcs root
     * @return the rebase directory or null if it does not exist.
     */
    @Nullable
    private static File getRebaseDir(@Nonnull Project project, @Nonnull VirtualFile root) {
        GitRepository repository = assertNotNull(GitUtil.getRepositoryManager(project).getRepositoryForRoot(root));
        File f = repository.getRepositoryFiles().getRebaseApplyDir();
        if (f.exists()) {
            return f;
        }
        f = repository.getRepositoryFiles().getRebaseMergeDir();
        if (f.exists()) {
            return f;
        }
        return null;
    }

    /**
     * Get rebase directory
     *
     * @param project
     * @param root    the vcs root
     * @return the commit information or null if no commit information could be detected
     */
    @Nullable
    public static CommitInfo getCurrentRebaseCommit(@Nonnull Project project, @Nonnull VirtualFile root) {
        File rebaseDir = getRebaseDir(project, root);
        if (rebaseDir == null) {
            LOG.warn("No rebase dir found for " + root.getPath());
            return null;
        }
        File nextFile = new File(rebaseDir, "next");
        int next;
        try {
            next = Integer.parseInt(Files.readString(nextFile.toPath(), StandardCharsets.UTF_8).trim());
        }
        catch (Exception e) {
            LOG.warn("Failed to load next commit number from file " + nextFile.getPath(), e);
            return null;
        }
        File commitFile = new File(rebaseDir, String.format("%04d", next));
        String hash = null;
        String subject = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(commitFile), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("From ")) {
                        hash = line.substring(5, 5 + 40);
                    }
                    if (line.startsWith("Subject: ")) {
                        subject = line.substring("Subject: ".length());
                    }
                    if (hash != null && subject != null) {
                        break;
                    }
                }
            }
            finally {
                in.close();
            }
        }
        catch (Exception e) {
            LOG.warn("Failed to load next commit number from file " + commitFile, e);
            return null;
        }
        if (subject == null || hash == null) {
            LOG.info("Unable to extract information from " + commitFile + " " + hash + ": " + subject);
            return null;
        }
        return new CommitInfo(new GitRevisionNumber(hash), subject);
    }

    @Nonnull
    static LocalizeValue mentionLocalChangesRemainingInStash(@Nullable GitChangesSaver saver) {
        return saver != null && saver.wereChangesSaved()
            ? LocalizeValue.localizeTODO("<br/>Note that some local changes were <a href='stash'>" + toPast(saver.getOperationName()) + "</a> before rebase.")
            : LocalizeValue.empty();
    }

    @Nonnull
    private static String toPast(@Nonnull String word) {
        return word.endsWith("e") ? word + "d" : word + "ed";
    }

    @Nonnull
    public static Collection<GitRepository> getRebasingRepositories(@Nonnull Project project) {
        return ContainerUtil.filter(
            GitUtil.getRepositories(project),
            repository -> repository.getState() == Repository.State.REBASING
        );
    }

    /**
     * Short commit info
     */
    public static class CommitInfo {
        /**
         * The commit hash
         */
        public final GitRevisionNumber revision;
        /**
         * The commit subject
         */
        public final String subject;

        /**
         * The constructor
         *
         * @param revision
         * @param subject  the commit subject
         */
        public CommitInfo(GitRevisionNumber revision, String subject) {
            this.revision = revision;
            this.subject = subject;
        }

        @Override
        public String toString() {
            return revision.toString();
        }
    }
}
