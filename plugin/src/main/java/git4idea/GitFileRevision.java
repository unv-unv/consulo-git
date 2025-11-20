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
package git4idea;

import consulo.project.Project;
import consulo.util.lang.Couple;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.RepositoryLocation;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.history.VcsFileRevision;
import consulo.versionControlSystem.history.VcsFileRevisionDvcsSpecific;
import consulo.versionControlSystem.history.VcsFileRevisionEx;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.util.GitFileUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;

public class GitFileRevision extends VcsFileRevisionEx implements Comparable<VcsFileRevision>, VcsFileRevisionDvcsSpecific {

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final FilePath myPath;
    @Nonnull
    private final GitRevisionNumber myRevision;
    @Nullable
    private final Couple<Couple<String>> myAuthorAndCommitter;
    @Nullable
    private final String myMessage;
    @Nullable
    private final String myBranch;
    @Nullable
    private final Date myAuthorTime;
    @Nonnull
    private final Collection<String> myParents;
    @Nullable
    private final VirtualFile myRoot;

    public GitFileRevision(@Nonnull Project project, @Nonnull FilePath path, @Nonnull GitRevisionNumber revision) {
        this(project, null, path, revision, null, null, null, null, Collections.<String>emptyList());
    }

    public GitFileRevision(
        @Nonnull Project project,
        @Nullable VirtualFile root,
        @Nonnull FilePath path,
        @Nonnull GitRevisionNumber revision,
        @Nullable Couple<Couple<String>> authorAndCommitter,
        @Nullable String message,
        @Nullable String branch,
        @Nullable Date authorTime,
        @Nonnull Collection<String> parents
    ) {
        myProject = project;
        myRoot = root;
        myPath = path;
        myRevision = revision;
        myAuthorAndCommitter = authorAndCommitter;
        myMessage = message;
        myBranch = branch;
        myAuthorTime = authorTime;
        myParents = parents;
    }

    @Override
    @Nonnull
    public FilePath getPath() {
        return myPath;
    }

    @Nullable
    @Override
    public RepositoryLocation getChangedRepositoryPath() {
        return null;
    }

    @Override
    @Nonnull
    public VcsRevisionNumber getRevisionNumber() {
        return myRevision;
    }

    @Override
    public Date getRevisionDate() {
        return myRevision.getTimestamp();
    }

    @Nullable
    @Override
    public Date getDateForRevisionsOrdering() {
        return myAuthorTime;
    }

    @Override
    @Nullable
    public String getAuthor() {
        if (myAuthorAndCommitter != null) {
            return myAuthorAndCommitter.getFirst().getFirst();
        }
        return null;
    }

    @Nullable
    @Override
    public String getAuthorEmail() {
        if (myAuthorAndCommitter != null) {
            return myAuthorAndCommitter.getFirst().getSecond();
        }
        return null;
    }

    @Nullable
    @Override
    public String getCommitterName() {
        if (myAuthorAndCommitter != null) {
            return myAuthorAndCommitter.getSecond() == null ? null : myAuthorAndCommitter.getSecond().getFirst();
        }
        return null;
    }

    @Nullable
    @Override
    public String getCommitterEmail() {
        if (myAuthorAndCommitter != null) {
            return myAuthorAndCommitter.getSecond() == null ? null : myAuthorAndCommitter.getSecond().getSecond();
        }
        return null;
    }

    @Override
    @Nullable
    public String getCommitMessage() {
        return myMessage;
    }

    @Override
    @Nullable
    public String getBranchName() {
        return myBranch;
    }

    @Override
    public synchronized byte[] loadContent() throws IOException, VcsException {
        VirtualFile root = getRoot();
        return GitFileUtils.getFileContent(myProject, root, myRevision.getRev(), VcsFileUtil.relativePath(root, myPath));
    }

    private VirtualFile getRoot() throws VcsException {
        return myRoot != null ? myRoot : GitUtil.getGitRoot(myPath);
    }

    @Override
    public synchronized byte[] getContent() throws IOException, VcsException {
        return loadContent();
    }

    @Override
    public int compareTo(VcsFileRevision rev) {
        if (rev instanceof GitFileRevision fileRevision) {
            return myRevision.compareTo(fileRevision.myRevision);
        }
        return getRevisionDate().compareTo(rev.getRevisionDate());
    }

    @Override
    public String toString() {
        return myPath.getName() + ":" + myRevision.getShortRev();
    }

    @Nonnull
    public Collection<String> getParents() {
        return myParents;
    }

    @Nonnull
    public String getHash() {
        return myRevision.getRev();
    }

}
