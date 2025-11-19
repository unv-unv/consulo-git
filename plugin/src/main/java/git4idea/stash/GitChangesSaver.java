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
import consulo.project.ui.notification.Notification;
import consulo.project.ui.notification.NotificationService;
import consulo.project.ui.notification.event.NotificationListener;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.commands.Git;
import git4idea.config.GitVcsSettings;
import git4idea.merge.GitConflictResolver;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.util.Collection;

/**
 * Saves and restores uncommitted local changes - it is used before and after the update process.
 * Respects changelists.
 *
 * @author Kirill Likhodedov
 */
public abstract class GitChangesSaver {
    private static final Logger LOG = Logger.getInstance(GitChangesSaver.class);

    @Nonnull
    protected final Project myProject;
    @Nonnull
    protected final ChangeListManager myChangeManager;
    @Nonnull
    protected final Git myGit;
    @Nonnull
    protected final ProgressIndicator myProgressIndicator;
    @Nonnull
    protected final String myStashMessage;

    protected GitConflictResolver.Params myParams;

    /**
     * Returns an instance of the proper GitChangesSaver depending on the given save changes policy.
     *
     * @return {@link GitStashChangesSaver} or {@link GitShelveChangesSaver}.
     */
    @Nonnull
    public static GitChangesSaver getSaver(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull ProgressIndicator progressIndicator,
        @Nonnull String stashMessage,
        @Nonnull GitVcsSettings.UpdateChangesPolicy saveMethod
    ) {
        if (saveMethod == GitVcsSettings.UpdateChangesPolicy.SHELVE) {
            return new GitShelveChangesSaver(project, git, progressIndicator, stashMessage);
        }
        return new GitStashChangesSaver(project, git, progressIndicator, stashMessage);
    }

    protected GitChangesSaver(
        @Nonnull Project project,
        @Nonnull Git git,
        @Nonnull ProgressIndicator indicator,
        @Nonnull String stashMessage
    ) {
        myProject = project;
        myGit = git;
        myProgressIndicator = indicator;
        myStashMessage = stashMessage;
        myChangeManager = ChangeListManager.getInstance(project);
    }

    /**
     * Saves local changes in stash or in shelf.
     *
     * @param rootsToSave Save changes only from these roots.
     */
    public void saveLocalChanges(@Nullable Collection<VirtualFile> rootsToSave) throws VcsException {
        if (rootsToSave == null || rootsToSave.isEmpty()) {
            return;
        }
        save(rootsToSave);
    }

    public void notifyLocalChangesAreNotRestored() {
        if (wereChangesSaved()) {
            LOG.info("Update is incomplete, changes are not restored");
            NotificationService.getInstance().newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Local changes were not restored"))
                .content(LocalizeValue.localizeTODO(
                    "Before update your uncommitted changes were saved to <a href='saver'>" +
                        getSaverName() +
                        "</a>.<br/>" +
                        "Update is not complete, you have unresolved merges in your working tree<br/>" +
                        "Resolve conflicts, complete update and restore changes manually."
                ))
                .hyperlinkListener(new ShowSavedChangesNotificationListener())
                .notify(myProject);
        }
    }

    public void setConflictResolverParams(GitConflictResolver.Params params) {
        myParams = params;
    }

    /**
     * Saves local changes - specific for chosen save strategy.
     *
     * @param rootsToSave local changes should be saved on these roots.
     */
    protected abstract void save(Collection<VirtualFile> rootsToSave) throws VcsException;

    /**
     * Loads the changes - specific for chosen save strategy.
     */
    public abstract void load();

    /**
     * @return true if there were local changes to save.
     */
    public abstract boolean wereChangesSaved();

    /**
     * @return name of the save capability provider - stash or shelf.
     */
    public abstract String getSaverName();

    /**
     * @return the name of the saving operation: stash or shelve.
     */
    @Nonnull
    public abstract String getOperationName();

    /**
     * Show the saved local changes in the proper viewer.
     */
    public abstract void showSavedChanges();

    /**
     * The right panel title of the merge conflict dialog: changes that came from update.
     */
    @Nonnull
    protected static LocalizeValue getConflictRightPanelTitle() {
        return LocalizeValue.localizeTODO("Changes from remote");
    }

    /**
     * The left panel title of the merge conflict dialog: changes that were preserved in this saver during update.
     */
    @Nonnull
    protected static LocalizeValue getConflictLeftPanelTitle() {
        return LocalizeValue.localizeTODO("Your uncommitted changes");
    }

    protected class ShowSavedChangesNotificationListener implements NotificationListener {
        @Override
        @RequiredUIAccess
        public void hyperlinkUpdate(@Nonnull Notification notification, @Nonnull HyperlinkEvent event) {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getDescription().equals("saver")) {
                showSavedChanges();
            }
        }
    }
}

