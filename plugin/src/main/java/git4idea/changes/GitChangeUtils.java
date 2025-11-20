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
package git4idea.changes;

import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.collection.ArrayUtil;
import consulo.util.io.FileUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.change.CurrentContentRevision;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.status.FileStatus;
import git4idea.GitContentRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.GitCommand;
import git4idea.commands.GitHandler;
import git4idea.commands.GitSimpleHandler;
import git4idea.history.browser.SHAHash;
import git4idea.util.StringScanner;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.util.*;

import static consulo.util.lang.ObjectUtil.assertNotNull;

/**
 * Change related utilities
 */
public class GitChangeUtils {
    /**
     * the pattern for committed changelist assumed.
     */
    public static final String COMMITTED_CHANGELIST_FORMAT = "%ct%n%H%n%P%n%an%x20%x3C%ae%x3E%n%cn%x20%x3C%ce%x3E%n%s%n%x03%n%b%n%x03";

    private static final Logger LOG = Logger.getInstance(GitChangeUtils.class);

    /**
     * A private constructor for utility class
     */
    private GitChangeUtils() {
    }

    /**
     * Parse changes from lines
     *
     * @param project        the context project
     * @param vcsRoot        the git root
     * @param thisRevision   the current revision
     * @param parentRevision the parent revision for this change list
     * @param s              the lines to parse
     * @param changes        a list of changes to update
     * @param ignoreNames    a set of names ignored during collection of the changes
     * @throws VcsException if the input format does not matches expected format
     */
    public static void parseChanges(
        Project project,
        VirtualFile vcsRoot,
        @Nullable GitRevisionNumber thisRevision,
        GitRevisionNumber parentRevision,
        String s,
        Collection<Change> changes,
        Set<String> ignoreNames
    ) throws VcsException {
        StringScanner sc = new StringScanner(s);
        parseChanges(project, vcsRoot, thisRevision, parentRevision, sc, changes, ignoreNames);
        if (sc.hasMoreData()) {
            throw new IllegalStateException("Unknown file status: " + sc.line());
        }
    }

    public static Collection<String> parseDiffForPaths(String rootPath, StringScanner s) throws VcsException {
        Collection<String> result = new ArrayList<>();

        while (s.hasMoreData()) {
            if (s.isEol()) {
                s.nextLine();
                continue;
            }
            if ("CADUMR".indexOf(s.peek()) == -1) {
                // exit if there is no next character
                break;
            }
            assert 'M' != s.peek() : "Moves are not yet handled";
            String[] tokens = s.line().split("\t");
            String path = tokens[tokens.length - 1];
            path = rootPath + File.separator + GitUtil.unescapePath(path);
            path = FileUtil.toSystemDependentName(path);
            result.add(path);
        }
        return result;
    }

    /**
     * Parse changes from lines
     *
     * @param project        the context project
     * @param vcsRoot        the git root
     * @param thisRevision   the current revision
     * @param parentRevision the parent revision for this change list
     * @param s              the lines to parse
     * @param changes        a list of changes to update
     * @param ignoreNames    a set of names ignored during collection of the changes
     * @throws VcsException if the input format does not matches expected format
     */
    private static void parseChanges(
        Project project,
        VirtualFile vcsRoot,
        @Nullable GitRevisionNumber thisRevision,
        @Nullable GitRevisionNumber parentRevision,
        StringScanner s,
        Collection<Change> changes,
        Set<String> ignoreNames
    ) throws VcsException {
        while (s.hasMoreData()) {
            FileStatus status = null;
            if (s.isEol()) {
                s.nextLine();
                continue;
            }
            if ("CADUMRT".indexOf(s.peek()) == -1) {
                // exit if there is no next character
                return;
            }
            String[] tokens = s.line().split("\t");
            ContentRevision before;
            ContentRevision after;
            String path = tokens[tokens.length - 1];
            switch (tokens[0].charAt(0)) {
                case 'C':
                case 'A':
                    before = null;
                    status = FileStatus.ADDED;
                    after = GitContentRevision.createRevision(vcsRoot, path, thisRevision, project, false, false, true);
                    break;
                case 'U':
                    status = FileStatus.MERGED_WITH_CONFLICTS;
                case 'M':
                    if (status == null) {
                        status = FileStatus.MODIFIED;
                    }
                    before = GitContentRevision.createRevision(vcsRoot, path, parentRevision, project, false, true, true);
                    after = GitContentRevision.createRevision(vcsRoot, path, thisRevision, project, false, false, true);
                    break;
                case 'D':
                    status = FileStatus.DELETED;
                    before = GitContentRevision.createRevision(vcsRoot, path, parentRevision, project, true, true, true);
                    after = null;
                    break;
                case 'R':
                    status = FileStatus.MODIFIED;
                    before = GitContentRevision.createRevision(vcsRoot, tokens[1], parentRevision, project, true, true, true);
                    after = GitContentRevision.createRevision(vcsRoot, path, thisRevision, project, false, false, true);
                    break;
                case 'T':
                    status = FileStatus.MODIFIED;
                    before = GitContentRevision.createRevision(vcsRoot, path, parentRevision, project, true, true, true);
                    after = GitContentRevision.createRevisionForTypeChange(project, vcsRoot, path, thisRevision, true);
                    break;
                default:
                    throw new VcsException("Unknown file status: " + Arrays.asList(tokens));
            }
            if (ignoreNames == null || !ignoreNames.contains(path)) {
                changes.add(new Change(before, after, status));
            }
        }
    }

    /**
     * Load actual revision number with timestamp basing on a reference: name of a branch or tag, or revision number expression.
     */
    @Nonnull
    public static GitRevisionNumber resolveReference(
        @Nonnull Project project,
        @Nonnull VirtualFile vcsRoot,
        @Nonnull String reference
    ) throws VcsException {
        GitSimpleHandler handler = createRefResolveHandler(project, vcsRoot, reference);
        String output = handler.run();
        StringTokenizer stk = new StringTokenizer(output, "\n\r \t", false);
        if (!stk.hasMoreTokens()) {
            try {
                GitSimpleHandler dh = new GitSimpleHandler(project, vcsRoot, GitCommand.LOG);
                dh.addParameters("-1", "HEAD");
                dh.setSilent(true);
                String out = dh.run();
                LOG.info("Diagnostic output from 'git log -1 HEAD': [" + out + "]");
                dh = createRefResolveHandler(project, vcsRoot, reference);
                out = dh.run();
                LOG.info("Diagnostic output from 'git rev-list -1 --timestamp HEAD': [" + out + "]");
            }
            catch (VcsException e) {
                LOG.info("Exception while trying to get some diagnostics info", e);
            }
            throw new VcsException(String.format(
                "The string '%s' does not represent a revision number. Output: [%s]\n Root: %s",
                reference,
                output,
                vcsRoot
            ));
        }
        Date timestamp = GitUtil.parseTimestampWithNFEReport(stk.nextToken(), handler, output);
        return new GitRevisionNumber(stk.nextToken(), timestamp);
    }

    @Nonnull
    private static GitSimpleHandler createRefResolveHandler(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String reference
    ) {
        GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.REV_LIST);
        handler.addParameters("--timestamp", "--max-count=1", reference);
        handler.endOptions();
        handler.setSilent(true);
        return handler;
    }

    /**
     * Check if the exception means that HEAD is missing for the current repository.
     *
     * @param e the exception to examine
     * @return true if the head is missing
     */
    public static boolean isHeadMissing(VcsException e) {
        String errorText = "fatal: bad revision 'HEAD'\n";
        return e.getMessage().equals(errorText);
    }

    /**
     * Get list of changes. Because native Git non-linear revision tree structure is not
     * supported by the current IDEA interfaces some simplifications are made in the case
     * of the merge, so changes are reported as difference with the first revision
     * listed on the the merge that has at least some changes.
     *
     * @param project           the project file
     * @param root              the git root
     * @param revisionName      the name of revision (might be tag)
     * @param skipDiffsForMerge
     * @param local
     * @param revertible
     * @return change list for the respective revision
     * @throws VcsException in case of problem with running git
     */
    public static GitCommittedChangeList getRevisionChanges(
        Project project,
        VirtualFile root,
        String revisionName,
        boolean skipDiffsForMerge,
        boolean local,
        boolean revertible
    ) throws VcsException {
        GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.SHOW);
        h.setSilent(true);
        h.addParameters(
            "--name-status",
            "--first-parent",
            "--no-abbrev",
            "-M",
            "--pretty=format:" + COMMITTED_CHANGELIST_FORMAT,
            "--encoding=UTF-8",
            revisionName,
            "--"
        );
        String output = h.run();
        StringScanner s = new StringScanner(output);
        return parseChangeList(project, root, s, skipDiffsForMerge, h, local, revertible);
    }

    @Nullable
    public static SHAHash commitExists(
        Project project,
        VirtualFile root,
        String anyReference,
        List<VirtualFile> paths,
        String... parameters
    ) {
        GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.LOG);
        h.setSilent(true);
        h.addParameters(parameters);
        h.addParameters("--max-count=1", "--pretty=%H", "--encoding=UTF-8", anyReference, "--");
        if (paths != null && !paths.isEmpty()) {
            h.addRelativeFiles(paths);
        }
        try {
            String output = h.run().trim();
            if (StringUtil.isEmptyOrSpaces(output)) {
                return null;
            }
            return new SHAHash(output);
        }
        catch (VcsException e) {
            return null;
        }
    }

    /**
     * Parse changelist
     *
     * @param project           the project
     * @param root              the git root
     * @param s                 the scanner for log or show command output
     * @param skipDiffsForMerge
     * @param handler           the handler that produced the output to parse. - for debugging purposes.
     * @param local             pass {@code true} to indicate that this revision should be an editable
     *                          {@link CurrentContentRevision}.
     *                          Pass {@code false} for
     * @param revertible
     * @return the parsed changelist
     * @throws VcsException if there is a problem with running git
     */
    public static GitCommittedChangeList parseChangeList(
        Project project,
        VirtualFile root,
        StringScanner s,
        boolean skipDiffsForMerge,
        GitHandler handler,
        boolean local,
        boolean revertible
    ) throws VcsException {
        List<Change> changes = new ArrayList<>();
        // parse commit information
        Date commitDate = GitUtil.parseTimestampWithNFEReport(s.line(), handler, s.getAllText());
        String revisionNumber = s.line();
        String parentsLine = s.line();
        String[] parents = parentsLine.length() == 0 ? ArrayUtil.EMPTY_STRING_ARRAY : parentsLine.split(" ");
        String authorName = s.line();
        String committerName = s.line();
        committerName = GitUtil.adjustAuthorName(authorName, committerName);
        String commentSubject = s.boundedToken('\u0003', true);
        s.nextLine();
        String commentBody = s.boundedToken('\u0003', true);
        // construct full comment
        String fullComment;
        if (commentSubject.length() == 0) {
            fullComment = commentBody;
        }
        else if (commentBody.length() == 0) {
            fullComment = commentSubject;
        }
        else {
            fullComment = commentSubject + "\n" + commentBody;
        }
        GitRevisionNumber thisRevision = new GitRevisionNumber(revisionNumber, commitDate);

        if (skipDiffsForMerge || (parents.length <= 1)) {
            GitRevisionNumber parentRevision = parents.length > 0 ? resolveReference(project, root, parents[0]) : null;
            // This is the first or normal commit with the single parent.
            // Just parse changes in this commit as returned by the show command.
            parseChanges(project, root, thisRevision, local ? null : parentRevision, s, changes, null);
        }
        else {
            // This is the merge commit. It has multiple parent commits.
            // Find the first commit with changes and report it as a change list.
            // If no changes are found (why to merge then?). Empty changelist is reported.

            for (String parent : parents) {
                GitRevisionNumber parentRevision = resolveReference(project, root, parent);
                GitSimpleHandler diffHandler = new GitSimpleHandler(project, root, GitCommand.DIFF);
                diffHandler.setSilent(true);
                diffHandler.addParameters("--name-status", "-M", parentRevision.getRev(), thisRevision.getRev());
                String diff = diffHandler.run();
                parseChanges(project, root, thisRevision, parentRevision, diff, changes, null);

                if (changes.size() > 0) {
                    break;
                }
            }
        }
        String changeListName = String.format("%s(%s)", commentSubject, revisionNumber);
        return new GitCommittedChangeList(
            changeListName,
            fullComment,
            committerName,
            thisRevision,
            commitDate,
            changes,
            assertNotNull(GitVcs.getInstance(project)),
            revertible
        );
    }

    public static long longForSHAHash(String revisionNumber) {
        return Long.parseLong(revisionNumber.substring(0, 15), 16) << 4 + Integer.parseInt(revisionNumber.substring(15, 16), 16);
    }

    @Nonnull
    public static Collection<Change> getDiff(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nullable String oldRevision,
        @Nullable String newRevision,
        @Nullable Collection<FilePath> dirtyPaths
    ) throws VcsException {
        LOG.assertTrue(oldRevision != null || newRevision != null, "Both old and new revisions can't be null");
        String range;
        GitRevisionNumber newRev;
        GitRevisionNumber oldRev;
        if (newRevision == null) { // current revision at the right
            range = oldRevision + "..";
            oldRev = resolveReference(project, root, oldRevision);
            newRev = null;
        }
        else if (oldRevision == null) { // current revision at the left
            range = ".." + newRevision;
            oldRev = null;
            newRev = resolveReference(project, root, newRevision);
        }
        else {
            range = oldRevision + ".." + newRevision;
            oldRev = resolveReference(project, root, oldRevision);
            newRev = resolveReference(project, root, newRevision);
        }
        String output = getDiffOutput(project, root, range, dirtyPaths);

        Collection<Change> changes = new ArrayList<>();
        parseChanges(project, root, newRev, oldRev, output, changes, Collections.<String>emptySet());
        return changes;
    }

    @Nonnull
    public static Collection<Change> getStagedChanges(@Nonnull Project project, @Nonnull VirtualFile root) throws VcsException {
        GitSimpleHandler diff = new GitSimpleHandler(project, root, GitCommand.DIFF);
        diff.addParameters("--name-status", "--cached", "-M");
        String output = diff.run();

        Collection<Change> changes = new ArrayList<>();
        parseChanges(project, root, null, GitRevisionNumber.HEAD, output, changes, Collections.emptySet());
        return changes;
    }

    @Nonnull
    public static Collection<Change> getDiffWithWorkingDir(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String oldRevision,
        @Nullable Collection<FilePath> dirtyPaths,
        boolean reverse
    ) throws VcsException {
        String output = getDiffOutput(project, root, oldRevision, dirtyPaths, reverse);
        Collection<Change> changes = new ArrayList<>();
        GitRevisionNumber revisionNumber = resolveReference(project, root, oldRevision);
        parseChanges(
            project,
            root,
            reverse ? revisionNumber : null,
            reverse ? null : revisionNumber,
            output,
            changes,
            Collections.<String>emptySet()
        );
        return changes;
    }

    /**
     * Calls {@code git diff} on the given range.
     *
     * @param project
     * @param root
     * @param diffRange  range or just revision (will be compared with current working tree).
     * @param dirtyPaths limit the command by paths if needed or pass null.
     * @param reverse    swap two revision; that is, show differences from index or on-disk file to tree contents.
     * @return output of the 'git diff' command.
     * @throws VcsException
     */
    @Nonnull
    private static String getDiffOutput(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String diffRange,
        @Nullable Collection<FilePath> dirtyPaths,
        boolean reverse
    ) throws VcsException {
        GitSimpleHandler handler = getDiffHandler(project, root, diffRange, dirtyPaths, reverse);
        if (handler.isLargeCommandLine()) {
            // if there are too much files, just get all changes for the project
            handler = getDiffHandler(project, root, diffRange, null, reverse);
        }
        return handler.run();
    }

    @Nonnull
    public static String getDiffOutput(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String diffRange,
        @Nullable Collection<FilePath> dirtyPaths
    ) throws VcsException {
        return getDiffOutput(project, root, diffRange, dirtyPaths, false);
    }


    @Nonnull
    private static GitSimpleHandler getDiffHandler(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull String diffRange,
        @Nullable Collection<FilePath> dirtyPaths,
        boolean reverse
    ) {
        GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.DIFF);
        if (reverse) {
            handler.addParameters("-R");
        }
        handler.addParameters("--name-status", "--diff-filter=ADCMRUXT", "-M", diffRange);
        handler.setSilent(true);
        handler.setStdoutSuppressed(true);
        handler.endOptions();
        if (dirtyPaths != null) {
            handler.addRelativePaths(dirtyPaths);
        }
        return handler;
    }
}
