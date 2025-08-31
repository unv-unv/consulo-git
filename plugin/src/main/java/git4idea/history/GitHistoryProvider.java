/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.ex.action.ActionManager;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.awt.ColumnInfo;
import consulo.util.collection.ArrayUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsConfiguration;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.action.VcsActions;
import consulo.versionControlSystem.action.VcsContextFactory;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.history.*;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.changes.GitChangeUtils;
import git4idea.config.GitExecutableValidator;
import git4idea.history.browser.SHAHash;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Git history provider implementation
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitHistoryProvider
    implements VcsHistoryProvider, VcsCacheableHistorySessionFactory<Boolean, VcsAbstractHistorySession>, VcsBaseRevisionAdviser {
    private static final Logger log = Logger.getInstance(GitHistoryProvider.class);

    @Nonnull
    private final Project myProject;

    @Inject
    public GitHistoryProvider(@Nonnull Project project) {
        myProject = project;
    }

    @Override
    public VcsDependentHistoryComponents getUICustomization(
        final VcsHistorySession session,
        JComponent forShortcutRegistration
    ) {
        return VcsDependentHistoryComponents.createOnlyColumns(new ColumnInfo[0]);
    }

    @Override
    public AnAction[] getAdditionalActions(Runnable refresher) {
        ActionManager manager = ActionManager.getInstance();
        return new AnAction[]{
            manager.getAction(VcsActions.ACTION_SHOW_ALL_AFFECTED),
            manager.getAction(VcsActions.ACTION_COPY_REVISION_NUMBER)
        };
    }

    @Override
    public boolean isDateOmittable() {
        return false;
    }

    @Nullable
    @Override
    public String getHelpId() {
        return null;
    }

    @Override
    public FilePath getUsedFilePath(VcsAbstractHistorySession session) {
        return null;
    }

    @Override
    public Boolean getAddinionallyCachedData(VcsAbstractHistorySession session) {
        return null;
    }

    @Override
    public VcsAbstractHistorySession createFromCachedData(
        Boolean aBoolean,
        @Nonnull List<VcsFileRevision> revisions,
        @Nonnull FilePath filePath,
        VcsRevisionNumber currentRevision
    ) {
        return createSession(filePath, revisions, currentRevision);
    }

    @Nullable
    @Override
    public VcsHistorySession createSessionFor(final FilePath filePath) throws VcsException {
        List<VcsFileRevision> revisions = null;
        try {
            revisions = GitHistoryUtils.history(myProject, filePath);
        }
        catch (VcsException e) {
            GitVcs.getInstance(myProject).getExecutableValidator().showNotificationOrThrow(e);
        }
        return createSession(filePath, revisions, null);
    }

    private VcsAbstractHistorySession createSession(
        final FilePath filePath, final List<VcsFileRevision> revisions,
        @Nullable final VcsRevisionNumber number
    ) {
        return new VcsAbstractHistorySession(revisions, number) {
            @Nullable
            @Override
            protected VcsRevisionNumber calcCurrentRevisionNumber() {
                try {
                    return GitHistoryUtils.getCurrentRevision(myProject, filePath, "HEAD");
                }
                catch (VcsException e) {
                    // likely the file is not under VCS anymore.
                    if (log.isDebugEnabled()) {
                        log.debug("Unable to retrieve the current revision number", e);
                    }
                    return null;
                }
            }

            @Override
            public HistoryAsTreeProvider getHistoryAsTreeProvider() {
                return null;
            }

            @Override
            public VcsHistorySession copy() {
                return createSession(filePath, getRevisionList(), getCurrentRevisionNumber());
            }
        };
    }

    @Override
    public boolean getBaseVersionContent(
        FilePath filePath,
        Predicate<CharSequence> processor,
        final String beforeVersionId,
        List<String> warnings
    )
        throws VcsException {
        if (StringUtil.isEmptyOrSpaces(beforeVersionId) || filePath.getVirtualFile() == null) {
            return false;
        }
        // apply if base revision id matches revision
        final VirtualFile root = GitUtil.getGitRoot(filePath);

        final SHAHash shaHash = GitChangeUtils.commitExists(myProject, root, beforeVersionId, null, "HEAD");
        if (shaHash == null) {
            throw new VcsException("Can not apply patch to " + filePath.getPath() + ".\nCan not find revision '" + beforeVersionId + "'.");
        }

        final ContentRevision content = GitVcs.getInstance(myProject).getDiffProvider()
            .createFileContent(new GitRevisionNumber(shaHash.getValue()), filePath.getVirtualFile());
        if (content == null) {
            throw new VcsException("Can not load content of '" + filePath.getPath() + "' for revision '" + shaHash.getValue() + "'");
        }
        return !processor.test(content.getContent());
    }

    @Override
    public void reportAppendableHistory(final FilePath path, final VcsAppendableHistorySessionPartner partner) throws VcsException {
        final VcsAbstractHistorySession emptySession = createSession(path, Collections.<VcsFileRevision>emptyList(), null);
        partner.reportCreatedEmptySession(emptySession);

        VcsConfiguration vcsConfiguration = VcsConfiguration.getInstance(myProject);
        String[] additionalArgs = vcsConfiguration.LIMIT_HISTORY ?
            new String[]{"--max-count=" + vcsConfiguration.MAXIMUM_HISTORY_ROWS} :
            ArrayUtil.EMPTY_STRING_ARRAY;

        final GitExecutableValidator validator = GitVcs.getInstance(myProject).getExecutableValidator();
        GitHistoryUtils.history(
            myProject,
            refreshPath(path),
            null,
            partner::acceptRevision,
            e -> {
                if (validator.checkExecutableAndNotifyIfNeeded()) {
                    partner.reportException(e);
                }
            },
            additionalArgs
        );
    }

    /**
     * Refreshes the IO File inside this FilePath to let it survive moves.
     */
    @Nonnull
    private static FilePath refreshPath(@Nonnull FilePath path) {
        VirtualFile virtualFile = path.getVirtualFile();
        if (virtualFile == null) {
            return path;
        }
        return VcsContextFactory.getInstance().createFilePathOn(virtualFile);
    }

    @Override
    public boolean supportsHistoryForDirectories() {
        return true;
    }

    @Override
    public DiffFromHistoryHandler getHistoryDiffHandler() {
        return new GitDiffFromHistoryHandler(myProject);
    }

    @Override
    public boolean canShowHistoryFor(@Nonnull VirtualFile file) {
        GitRepositoryManager manager = GitUtil.getRepositoryManager(myProject);
        GitRepository repository = manager.getRepositoryForFile(file);
        return repository != null && !repository.isFresh();
    }
}
