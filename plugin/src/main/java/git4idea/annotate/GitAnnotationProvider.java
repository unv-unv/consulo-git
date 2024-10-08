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
package git4idea.annotate;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.ProgressManager;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.action.VcsContextFactory;
import consulo.versionControlSystem.annotate.*;
import consulo.versionControlSystem.history.VcsAbstractHistorySession;
import consulo.versionControlSystem.history.VcsFileRevision;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitFileRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import git4idea.history.GitHistoryUtils;
import git4idea.util.StringScanner;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Git annotation provider implementation.
 * <p/>
 * Based on the JetBrains SVNAnnotationProvider.
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitAnnotationProvider implements AnnotationProvider, VcsCacheableAnnotationProvider {
    /**
     * the context project
     */
    private final Project myProject;
    /**
     * The author key for annotations
     */
    private static final String AUTHOR_KEY = "author";
    /**
     * The committer time key for annotations
     */
    private static final String COMMITTER_TIME_KEY = "committer-time";
    private static final Logger LOG = Logger.getInstance(GitAnnotationProvider.class);

    /**
     * A constructor
     *
     * @param project a context project
     */
    @Inject
    public GitAnnotationProvider(@Nonnull Project project) {
        myProject = project;
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public FileAnnotation annotate(@Nonnull VirtualFile file) throws VcsException {
        return annotate(file, null);
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public FileAnnotation annotate(@Nonnull final VirtualFile file, final VcsFileRevision revision) throws VcsException {
        if (file.isDirectory()) {
            throw new VcsException("Cannot annotate a directory");
        }
        final FileAnnotation[] annotation = new FileAnnotation[1];
        final Exception[] exception = new Exception[1];
        Runnable command = () -> {
            final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
            try {
                final FilePath currentFilePath = VcsUtil.getFilePath(file.getPath());
                final FilePath realFilePath;
                if (progress != null) {
                    progress.setTextValue(GitLocalize.gettingHistory(file.getName()));
                }
                final List<VcsFileRevision> revisions = GitHistoryUtils.history(myProject, currentFilePath);
                if (revision == null) {
                    realFilePath = GitHistoryUtils.getLastCommitName(myProject, currentFilePath);
                }
                else {
                    realFilePath = ((GitFileRevision)revision).getPath();
                }
                if (progress != null) {
                    progress.setTextValue(GitLocalize.computingAnnotation(file.getName()));
                }
                final GitFileAnnotation result = annotate(realFilePath, revision, revisions, file);
                annotation[0] = result;
            }
            catch (Exception e) {
                exception[0] = e;
            }
        };
        if (Application.get().isDispatchThread()) {
            ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(command, GitLocalize.annotateActionName().get(), false, myProject);
        }
        else {
            command.run();
        }
        if (exception[0] != null) {
            LOG.warn(exception[0]);
            throw new VcsException("Failed to annotate: " + exception[0], exception[0]);
        }
        return annotation[0];
    }

    /**
     * Calculate annotations
     *
     * @param repositoryFilePath the file path in the repository
     * @param revision           the revision to checkout
     * @param revisions          the revision list from history
     * @param file               a virtual file for the action
     * @return a file annotation object
     * @throws VcsException if there is a problem with running git
     */
    private GitFileAnnotation annotate(
        final FilePath repositoryFilePath,
        final VcsFileRevision revision,
        final List<VcsFileRevision> revisions,
        final VirtualFile file
    ) throws VcsException {
        GitSimpleHandler h = new GitSimpleHandler(myProject, GitUtil.getGitRoot(repositoryFilePath), GitCommand.BLAME);
        h.setStdoutSuppressed(true);
        h.setCharset(file.getCharset());
        h.addParameters("-p", "-l", "-t", "-w");
        if (revision == null) {
            h.addParameters("HEAD");
        }
        else {
            h.addParameters(revision.getRevisionNumber().asString());
        }
        h.endOptions();
        h.addRelativePaths(repositoryFilePath);
        String output = h.run();
        GitFileAnnotation annotation =
            new GitFileAnnotation(myProject, file, revision == null, revision == null ? null : revision.getRevisionNumber());

        class CommitInfo {
            Date date;
            String author;
            GitRevisionNumber revision;
        }
        HashMap<String, CommitInfo> commits = new HashMap<>();
        for (StringScanner s = new StringScanner(output); s.hasMoreData(); ) {
            // parse header line
            String commitHash = s.spaceToken();
            if (commitHash.equals(GitRevisionNumber.NOT_COMMITTED_HASH)) {
                commitHash = null;
            }
            s.spaceToken(); // skip revision line number
            String s1 = s.spaceToken();
            int lineNum = Integer.parseInt(s1);
            s.nextLine();
            // parse commit information
            CommitInfo commit = commits.get(commitHash);
            if (commit != null) {
                while (s.hasMoreData() && !s.startsWith('\t')) {
                    s.nextLine();
                }
            }
            else {
                commit = new CommitInfo();
                while (s.hasMoreData() && !s.startsWith('\t')) {
                    String key = s.spaceToken();
                    String value = s.line();
                    if (commitHash != null && AUTHOR_KEY.equals(key)) {
                        commit.author = value;
                    }
                    if (commitHash != null && COMMITTER_TIME_KEY.equals(key)) {
                        commit.date = GitUtil.parseTimestampWithNFEReport(value, h, output);
                        commit.revision = new GitRevisionNumber(commitHash, commit.date);
                    }
                }
                commits.put(commitHash, commit);
            }
            // parse line
            if (!s.hasMoreData()) {
                // if the file is empty, the next line will not start with tab and it will be
                // empty.
                continue;
            }
            s.skipChars(1);
            String line = s.line(true);
            annotation.appendLineInfo(commit.date, commit.revision, commit.author, line, lineNum);
        }
        annotation.addLogEntries(revisions);
        return annotation;
    }

    @Override
    public VcsAnnotation createCacheable(FileAnnotation fileAnnotation) {
        final GitFileAnnotation gitFileAnnotation = (GitFileAnnotation)fileAnnotation;
        final int size = gitFileAnnotation.getNumLines();
        final VcsUsualLineAnnotationData basicData = new VcsUsualLineAnnotationData(size);
        for (int i = 0; i < size; i++) {
            basicData.put(i, gitFileAnnotation.getLineRevisionNumber(i));
        }
        return new VcsAnnotation(VcsContextFactory.getInstance().createFilePathOn(gitFileAnnotation.getFile()), basicData, null);
    }

    @Override
    public FileAnnotation restore(
        VcsAnnotation vcsAnnotation,
        VcsAbstractHistorySession session,
        String annotatedContent,
        boolean forCurrentRevision,
        VcsRevisionNumber revisionNumber
    ) {
        final GitFileAnnotation gitFileAnnotation =
            new GitFileAnnotation(myProject, vcsAnnotation.getFilePath().getVirtualFile(), forCurrentRevision, revisionNumber);
        gitFileAnnotation.addLogEntries(session.getRevisionList());
        final VcsLineAnnotationData basicAnnotation = vcsAnnotation.getBasicAnnotation();
        final int size = basicAnnotation.getNumLines();
        final Map<VcsRevisionNumber, VcsFileRevision> historyAsMap = session.getHistoryAsMap();
        final List<String> lines =
            StringUtil.split(StringUtil.convertLineSeparators(annotatedContent), "\n", false, false);
        for (int i = 0; i < size; i++) {
            final VcsRevisionNumber revision = basicAnnotation.getRevision(i);
            final VcsFileRevision vcsFileRevision = historyAsMap.get(revision);
            if (vcsFileRevision == null) {
                return null;
            }
            try {
                gitFileAnnotation.appendLineInfo(vcsFileRevision.getRevisionDate(),
                    (GitRevisionNumber)revision,
                    vcsFileRevision.getAuthor(),
                    lines.get(i),
                    i + 1
                );
            }
            catch (VcsException e) {
                return null;
            }
        }
        return gitFileAnnotation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAnnotationValid(@Nonnull VcsFileRevision rev) {
        return true;
    }

    @Nonnull
    @Override
    public LocalizeValue getActionName() {
        return GitLocalize.actionAnnotateWithBlameText();
    }
}
