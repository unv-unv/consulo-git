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
package git4idea.diff;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.util.lang.Pair;
import consulo.versionControlSystem.CommittedChangesProvider;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.diff.DiffProvider;
import consulo.versionControlSystem.diff.ItemLatestState;
import consulo.versionControlSystem.history.VcsFileRevision;
import consulo.versionControlSystem.history.VcsRevisionDescription;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.versionControlSystem.versionBrowser.CommittedChangeList;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import consulo.virtualFileSystem.status.FileStatusManager;
import git4idea.GitContentRevision;
import git4idea.GitFileRevision;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.history.GitHistoryUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Git diff provider
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitDiffProvider implements DiffProvider {
    /**
     * The context project
     */
    private final Project myProject;
    /**
     * The status manager for the project
     */
    private final FileStatusManager myStatusManager;

    private static final Set<FileStatus> ourGoodStatuses;

    static {
        ourGoodStatuses = new HashSet<>();
        ourGoodStatuses.addAll(Arrays.asList(
            FileStatus.NOT_CHANGED,
            FileStatus.DELETED,
            FileStatus.MODIFIED,
            FileStatus.MERGE,
            FileStatus.MERGED_WITH_CONFLICTS
        ));
    }

    /**
     * A constructor
     *
     * @param project the context project
     */
    @Inject
    public GitDiffProvider(@Nonnull Project project) {
        myProject = project;
        myStatusManager = FileStatusManager.getInstance(myProject);
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public VcsRevisionNumber getCurrentRevision(VirtualFile file) {
        if (file.isDirectory()) {
            return null;
        }
        try {
            return GitHistoryUtils.getCurrentRevision(myProject, VcsUtil.getFilePath(file.getPath()), "HEAD");
        }
        catch (VcsException e) {
            return null;
        }
    }

    @Nullable
    @Override
    public VcsRevisionDescription getCurrentRevisionDescription(@Nonnull VirtualFile file) {
        if (file.isDirectory()) {
            return null;
        }
        try {
            return GitHistoryUtils.getCurrentRevisionDescription(myProject, VcsUtil.getFilePath(file.getPath()));
        }
        catch (VcsException e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public ItemLatestState getLastRevision(VirtualFile file) {
        if (file.isDirectory()) {
            return null;
        }
        if (!ourGoodStatuses.contains(myStatusManager.getStatus(file))) {
            return null;
        }
        try {
            return GitHistoryUtils.getLastRevision(myProject, VcsUtil.getFilePath(file.getPath()));
        }
        catch (VcsException e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public ContentRevision createFileContent(VcsRevisionNumber revisionNumber, VirtualFile selectedFile) {
        if (selectedFile.isDirectory()) {
            return null;
        }
        String path = selectedFile.getPath();
        if (GitUtil.gitRootOrNull(selectedFile) == null) {
            return null;
        }

        // faster, if there were no renames
        FilePath filePath = VcsUtil.getFilePath(path);
        try {
            CommittedChangesProvider committedChangesProvider = GitVcs.getInstance(myProject).getCommittedChangesProvider();
            Pair<CommittedChangeList, FilePath> pair = committedChangesProvider.getOneList(selectedFile, revisionNumber);
            if (pair != null) {
                return GitContentRevision.createRevision(pair.getSecond(), revisionNumber, myProject, selectedFile.getCharset());
            }
        }
        catch (VcsException e) {
            GitVcs.getInstance(myProject).showErrors(List.of(e), GitLocalize.diffFindError(path));
        }

        try {
            for (VcsFileRevision f : GitHistoryUtils.history(myProject, filePath)) {
                GitFileRevision gitRevision = (GitFileRevision)f;
                if (f.getRevisionNumber().equals(revisionNumber)) {
                    return GitContentRevision.createRevision(gitRevision.getPath(), revisionNumber, myProject, selectedFile.getCharset());
                }
            }
            GitContentRevision candidate =
                (GitContentRevision)GitContentRevision.createRevision(filePath, revisionNumber, myProject, selectedFile.getCharset());
            try {
                candidate.getContent();
                return candidate;
            }
            catch (VcsException e) {
                // file does not exists
            }
        }
        catch (VcsException e) {
            GitVcs.getInstance(myProject).showErrors(List.of(e), GitLocalize.diffFindError(path));
        }
        return null;
    }

    @Override
    public ItemLatestState getLastRevision(FilePath filePath) {
        if (filePath.isDirectory()) {
            return null;
        }
        VirtualFile vf = filePath.getVirtualFile();
        if (vf != null && !ourGoodStatuses.contains(myStatusManager.getStatus(vf))) {
            return null;
        }
        try {
            return GitHistoryUtils.getLastRevision(myProject, filePath);
        }
        catch (VcsException e) {
            return null;
        }
    }

    @Override
    public VcsRevisionNumber getLatestCommittedRevision(VirtualFile vcsRoot) {
        // todo
        return null;
    }
}
