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
package git4idea.merge;

import consulo.application.Application;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.AbstractVcsHelper;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.merge.MergeDialogCustomizer;
import consulo.versionControlSystem.merge.MergeProvider;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.StringScanner;
import jakarta.annotation.Nonnull;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.util.*;

import static consulo.util.lang.ObjectUtil.assertNotNull;
import static consulo.versionControlSystem.distributed.DvcsUtil.findVirtualFilesWithRefresh;
import static consulo.versionControlSystem.distributed.DvcsUtil.sortVirtualFilesByPresentation;

/**
 * The class is highly customizable, since the procedure of resolving conflicts is very common in Git operations.
 */
public class GitConflictResolver {
    private static final Logger LOG = Logger.getInstance(GitConflictResolver.class);

    @Nonnull
    private final Collection<VirtualFile> myRoots;
    @Nonnull
    private final Params myParams;

    @Nonnull
    protected final Project myProject;
    @Nonnull
    protected final NotificationService myNotificationService;
    @Nonnull
    private final Git myGit;
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;
    @Nonnull
    private final AbstractVcsHelper myVcsHelper;
    @Nonnull
    private final GitVcs myVcs;

    /**
     * Customizing parameters - mostly String notification texts, etc.
     */
    public static class Params {
        private boolean reverse;
        private String myErrorNotificationTitle = "";
        private String myErrorNotificationAdditionalDescription = "";
        private String myMergeDescription = "";
        private MergeDialogCustomizer myMergeDialogCustomizer = new MergeDialogCustomizer() {
            @Override
            public String getMultipleFileMergeDescription(@Nonnull Collection<VirtualFile> files) {
                return myMergeDescription;
            }
        };

        /**
         * @param reverseMerge specify {@code true} if reverse merge provider has to be used for merging - it is the case of rebase or stash.
         */
        public Params setReverse(boolean reverseMerge) {
            reverse = reverseMerge;
            return this;
        }

        public Params setErrorNotificationTitle(String errorNotificationTitle) {
            myErrorNotificationTitle = errorNotificationTitle;
            return this;
        }

        public Params setErrorNotificationAdditionalDescription(String errorNotificationAdditionalDescription) {
            myErrorNotificationAdditionalDescription = errorNotificationAdditionalDescription;
            return this;
        }

        public Params setMergeDescription(String mergeDescription) {
            myMergeDescription = mergeDescription;
            return this;
        }

        public Params setMergeDialogCustomizer(MergeDialogCustomizer mergeDialogCustomizer) {
            myMergeDialogCustomizer = mergeDialogCustomizer;
            return this;
        }
    }

    public GitConflictResolver(@Nonnull Project project, @Nonnull Git git, @Nonnull Collection<VirtualFile> roots, @Nonnull Params params) {
        myProject = project;
        myNotificationService = NotificationService.getInstance();
        myGit = git;
        myRoots = roots;
        myParams = params;
        myRepositoryManager = GitUtil.getRepositoryManager(myProject);
        myVcsHelper = AbstractVcsHelper.getInstance(project);
        myVcs = assertNotNull(GitVcs.getInstance(myProject));
    }

    /**
     * <p>
     * Goes throw the procedure of merging conflicts via MergeTool for different types of operations.
     * <ul>
     * <li>Checks if there are unmerged files. If not, executes {@link #proceedIfNothingToMerge()}</li>
     * <li>Otherwise shows a {@link MultipleFileMergeDialog} where user is able to merge files.</li>
     * <li>After the dialog is closed, checks if unmerged files remain.
     * If everything is merged, executes {@link #proceedAfterAllMerged()}. Otherwise shows a notification.</li>
     * </ul>
     * </p>
     * <p>
     * If a Git error happens during seeking for unmerged files or in other cases,
     * the method shows a notification and returns {@code false}.
     * </p>
     *
     * @return {@code true} if there is nothing to merge anymore, {@code false} if unmerged files remain or in the case of error.
     */
    @RequiredUIAccess
    public final boolean merge() {
        return merge(false);
    }

    /**
     * This is executed from {@link #merge()} if the initial check tells that there is nothing to merge.
     * In the basic implementation no action is performed, {@code true} is returned.
     *
     * @return Return value is returned from {@link #merge()}
     */
    protected boolean proceedIfNothingToMerge() throws VcsException {
        return true;
    }

    /**
     * This is executed from {@link #merge()} after all conflicts are resolved.
     * In the basic implementation no action is performed, {@code true} is returned.
     *
     * @return Return value is returned from {@link #merge()}
     */
    protected boolean proceedAfterAllMerged() throws VcsException {
        return true;
    }

    /**
     * Invoke the merge dialog, but execute nothing after merge is completed.
     *
     * @return true if all changes were merged, false if unresolved merges remain.
     */
    @RequiredUIAccess
    public final boolean mergeNoProceed() {
        return merge(true);
    }

    /**
     * Shows notification that not all conflicts were resolved.
     */
    protected void notifyUnresolvedRemain() {
        notifyWarning(
            myParams.myErrorNotificationTitle,
            "You have to <a href='resolve'>resolve</a> all conflicts first." + myParams.myErrorNotificationAdditionalDescription
        );
    }

    /**
     * Shows notification that some conflicts were still not resolved - after user invoked the conflict resolver by pressing the link on the
     * notification.
     */
    private void notifyUnresolvedRemainAfterNotification() {
        notifyWarning(
            "Not all conflicts resolved",
            "You should <a href='resolve'>resolve</a> all conflicts before update. <br>" + myParams.myErrorNotificationAdditionalDescription
        );
    }

    private void notifyWarning(String title, String content) {
        myNotificationService.newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO(title))
            .content(LocalizeValue.localizeTODO(content))
            .optionalHyperlinkListener(new ResolveNotificationListener())
            .notify(myProject);
    }

    @RequiredUIAccess
    private boolean merge(boolean mergeDialogInvokedFromNotification) {
        try {
            Collection<VirtualFile> initiallyUnmergedFiles = getUnmergedFiles(myRoots);
            if (initiallyUnmergedFiles.isEmpty()) {
                LOG.info("merge: no unmerged files");
                return mergeDialogInvokedFromNotification || proceedIfNothingToMerge();
            }
            else {
                showMergeDialog(initiallyUnmergedFiles);

                Collection<VirtualFile> unmergedFilesAfterResolve = getUnmergedFiles(myRoots);
                if (unmergedFilesAfterResolve.isEmpty()) {
                    LOG.info("merge no more unmerged files");
                    return mergeDialogInvokedFromNotification || proceedAfterAllMerged();
                }
                else {
                    LOG.info("mergeFiles unmerged files remain: " + unmergedFilesAfterResolve);
                    if (mergeDialogInvokedFromNotification) {
                        notifyUnresolvedRemainAfterNotification();
                    }
                    else {
                        notifyUnresolvedRemain();
                    }
                }
            }
        }
        catch (VcsException e) {
            if (myVcs.getExecutableValidator().checkExecutableAndNotifyIfNeeded()) {
                notifyException(e);
            }
        }
        return false;

    }

    @RequiredUIAccess
    private void showMergeDialog(@Nonnull Collection<VirtualFile> initiallyUnmergedFiles) {
        Application application = myProject.getApplication();
        application.invokeAndWait(
            () -> {
                MergeProvider mergeProvider = new GitMergeProvider(myProject, myParams.reverse);
                myVcsHelper.showMergeDialog(new ArrayList<>(initiallyUnmergedFiles), mergeProvider, myParams.myMergeDialogCustomizer);
            },
            application.getDefaultModalityState()
        );
    }

    private void notifyException(VcsException e) {
        LOG.info("mergeFiles ", e);
        myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO(myParams.myErrorNotificationTitle))
            .content(LocalizeValue.localizeTODO(
                "Couldn't check the working tree for unmerged files because of an error." +
                    myParams.myErrorNotificationAdditionalDescription + "<br/>" + e.getLocalizedMessage()
            ))
            .optionalHyperlinkListener(new ResolveNotificationListener())
            .notify(myProject);
    }

    @Nonnull
    protected NotificationListener getResolveLinkListener() {
        return new ResolveNotificationListener();
    }

    private class ResolveNotificationListener implements NotificationListener {
        @Override
        @RequiredUIAccess
        public void hyperlinkUpdate(@Nonnull Notification notification, @Nonnull HyperlinkEvent event) {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getDescription().equals("resolve")) {
                notification.expire();
                myProject.getApplication().executeOnPooledThread((Runnable) GitConflictResolver.this::mergeNoProceed);
            }
        }
    }

    /**
     * @return unmerged files in the given Git roots, all in a single collection.
     * @see #getUnmergedFiles(VirtualFile)
     */
    private Collection<VirtualFile> getUnmergedFiles(@Nonnull Collection<VirtualFile> roots) throws VcsException {
        Collection<VirtualFile> unmergedFiles = new HashSet<>();
        for (VirtualFile root : roots) {
            unmergedFiles.addAll(getUnmergedFiles(root));
        }
        return unmergedFiles;
    }

    /**
     * @return unmerged files in the given Git root.
     * @see #getUnmergedFiles(Collection
     */
    private Collection<VirtualFile> getUnmergedFiles(@Nonnull VirtualFile root) throws VcsException {
        return unmergedFiles(root);
    }

    /**
     * Parse changes from lines
     *
     * @param root the git root
     * @return a set of unmerged files
     * @throws VcsException if the input format does not matches expected format
     */
    private List<VirtualFile> unmergedFiles(VirtualFile root) throws VcsException {
        GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
        if (repository == null) {
            LOG.error("Repository not found for root " + root);
            return Collections.emptyList();
        }

        GitCommandResult result = myGit.getUnmergedFiles(repository);
        if (!result.success()) {
            throw new VcsException(result.getErrorOutputAsJoinedValue());
        }

        String output = StringUtil.join(result.getOutput(), "\n");
        HashSet<String> unmergedPaths = new HashSet<>();
        for (StringScanner s = new StringScanner(output); s.hasMoreData(); ) {
            if (s.isEol()) {
                s.nextLine();
                continue;
            }
            s.boundedToken('\t');
            String relative = s.line();
            unmergedPaths.add(GitUtil.unescapePath(relative));
        }

        if (unmergedPaths.size() == 0) {
            return Collections.emptyList();
        }
        else {
            List<File> files = ContainerUtil.map(unmergedPaths, path -> new File(root.getPath(), path));
            return sortVirtualFilesByPresentation(findVirtualFilesWithRefresh(files));
        }
    }

}
