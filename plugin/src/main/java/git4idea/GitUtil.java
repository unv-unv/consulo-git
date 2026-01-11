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

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogBuilder;
import consulo.ui.ex.awt.MultiLineLabel;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.HashingStrategy;
import consulo.util.interner.Interner;
import consulo.util.io.FileUtil;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.*;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.versionControlSystem.distributed.repository.VcsRepositoryManager;
import consulo.versionControlSystem.root.VcsRoot;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.versionControlSystem.versionBrowser.CommittedChangeList;
import consulo.versionControlSystem.virtualFileSystem.AbstractVcsVirtualFile;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.branch.GitBranchUtil;
import git4idea.changes.GitChangeUtils;
import git4idea.changes.GitCommittedChangeList;
import git4idea.commands.*;
import git4idea.config.GitConfigUtil;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitSimplePathsBrowser;
import git4idea.util.GitUIUtil;
import git4idea.util.StringScanner;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import static consulo.util.lang.ObjectUtil.assertNotNull;
import static consulo.versionControlSystem.distributed.DvcsUtil.getShortRepositoryName;
import static consulo.versionControlSystem.distributed.DvcsUtil.joinShortNames;

/**
 * Git utility/helper methods
 */
public class GitUtil {
    private static final class GitRepositoryNotFoundException extends VcsException {
        private GitRepositoryNotFoundException(@Nonnull VirtualFile file) {
            super(GitLocalize.repositoryNotFoundError(file.getPresentableUrl()));
        }

        private GitRepositoryNotFoundException(@Nonnull FilePath filePath) {
            super(GitLocalize.repositoryNotFoundError(filePath.getPresentableUrl()));
        }
    }

    public static final String DOT_GIT = ".git";

    public static final String ORIGIN_HEAD = "origin/HEAD";

    public static final Function<GitRepository, VirtualFile> REPOSITORY_TO_ROOT = Repository::getRoot;

    public static final String HEAD = "HEAD";
    public static final String CHERRY_PICK_HEAD = "CHERRY_PICK_HEAD";
    public static final String MERGE_HEAD = "MERGE_HEAD";

    private static final String SUBMODULE_REPO_PATH_PREFIX = "gitdir:";
    private final static Logger LOG = Logger.getInstance(GitUtil.class);
    private static final String HEAD_FILE = "HEAD";

    private static final Pattern HASH_STRING_PATTERN = Pattern.compile("[a-fA-F0-9]{40}");

    /**
     * A private constructor to suppress instance creation
     */
    private GitUtil() {
        // do nothing
    }

    /**
     * Returns the Git repository location for the given repository root directory, or null if nothing can be found.
     * Able to find the real repository root of a submodule.
     *
     * @see #findGitDir(VirtualFile)
     */
    @Nullable
    public static File findGitDir(@Nonnull File rootDir) {
        File dotGit = new File(rootDir, DOT_GIT);
        if (!dotGit.exists()) {
            return null;
        }
        if (dotGit.isDirectory()) {
            boolean headExists = new File(dotGit, HEAD_FILE).exists();
            return headExists ? dotGit : null;
        }

        String content = DvcsUtil.tryLoadFileOrReturn(dotGit, null, StandardCharsets.UTF_8);
        if (content == null) {
            return null;
        }
        String pathToDir = parsePathToRepository(content);
        return findSubmoduleRepositoryDir(rootDir.getPath(), pathToDir);
    }

    /**
     * Returns the Git repository location for the given repository root directory, or null if nothing can be found.
     * Able to find the real repository root of a submodule.
     * <p>
     * More precisely: checks if there is {@code .git} directory or file directly under rootDir. <br/>
     * If there is a directory, performs a quick check that it looks like a Git repository;<br/>
     * if it is a file, follows the path written inside this file to find the submodule repo dir.
     */
    @Nullable
    public static VirtualFile findGitDir(@Nonnull VirtualFile rootDir) {
        VirtualFile dotGit = rootDir.findChild(DOT_GIT);
        if (dotGit == null) {
            return null;
        }
        if (dotGit.isDirectory()) {
            boolean headExists = dotGit.findChild(HEAD_FILE) != null;
            return headExists ? dotGit : null;
        }

        // if .git is a file with some specific content, it indicates a submodule with a link to the real repository path
        String content = readContent(dotGit);
        if (content == null) {
            return null;
        }
        String pathToDir = parsePathToRepository(content);
        File file = findSubmoduleRepositoryDir(rootDir.getPath(), pathToDir);
        if (file == null) {
            return null;
        }
        return VcsUtil.getVirtualFileWithRefresh(file);
    }

    @Nullable
    private static File findSubmoduleRepositoryDir(@Nonnull String rootPath, @Nonnull String path) {
        if (!FileUtil.isAbsolute(path)) {
            String canonicalPath = FileUtil.toCanonicalPath(FileUtil.join(rootPath, path), true);
            if (canonicalPath == null) {
                return null;
            }
            path = FileUtil.toSystemIndependentName(canonicalPath);
        }
        File file = new File(path);
        return file.isDirectory() ? file : null;
    }

    @Nonnull
    private static String parsePathToRepository(@Nonnull String content) {
        content = content.trim();
        return content.startsWith(SUBMODULE_REPO_PATH_PREFIX) ? content.substring(SUBMODULE_REPO_PATH_PREFIX.length()).trim() : content;
    }

    @Nullable
    private static String readContent(@Nonnull VirtualFile dotGit) {
        String content;
        try {
            content = readFile(dotGit);
        }
        catch (IOException e) {
            LOG.error("Couldn't read the content of " + dotGit, e);
            return null;
        }
        return content;
    }

    /**
     * Makes 3 attempts to get the contents of the file. If all 3 fail with an IOException, rethrows the exception.
     */
    @Nonnull
    public static String readFile(@Nonnull VirtualFile file) throws IOException {
        int ATTEMPTS = 3;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return new String(file.contentsToByteArray());
            }
            catch (IOException e) {
                LOG.info(String.format("IOException while reading %s (attempt #%s)", file, attempt));
                if (attempt >= ATTEMPTS - 1) {
                    throw e;
                }
            }
        }
        throw new AssertionError("Shouldn't get here. Couldn't read " + file);
    }

    /**
     * Sort files by Git root
     *
     * @param virtualFiles files to sort
     * @return sorted files
     * @throws VcsException if non git files are passed
     */
    @Nonnull
    public static Map<VirtualFile, List<VirtualFile>> sortFilesByGitRoot(@Nonnull Collection<VirtualFile> virtualFiles) throws VcsException {
        return sortFilesByGitRoot(virtualFiles, false);
    }

    /**
     * Sort files by Git root
     *
     * @param virtualFiles files to sort
     * @param ignoreNonGit if true, non-git files are ignored
     * @return sorted files
     * @throws VcsException if non git files are passed when {@code ignoreNonGit} is false
     */
    public static Map<VirtualFile, List<VirtualFile>> sortFilesByGitRoot(
        Collection<VirtualFile> virtualFiles,
        boolean ignoreNonGit
    ) throws VcsException {
        Map<VirtualFile, List<VirtualFile>> result = new HashMap<>();
        for (VirtualFile file : virtualFiles) {
            // directory is reported only when it is a submodule => it should be treated in the context of super-root
            VirtualFile vcsRoot = gitRootOrNull(file.isDirectory() ? file.getParent() : file);
            if (vcsRoot == null) {
                if (ignoreNonGit) {
                    continue;
                }
                else {
                    throw new VcsException("The file " + file.getPath() + " is not under Git");
                }
            }
            List<VirtualFile> files = result.get(vcsRoot);
            if (files == null) {
                files = new ArrayList<>();
                result.put(vcsRoot, files);
            }
            files.add(file);
        }
        return result;
    }

    /**
     * Sort files by vcs root
     *
     * @param files files to sort.
     * @return the map from root to the files under the root
     * @throws VcsException if non git files are passed
     */
    public static Map<VirtualFile, List<FilePath>> sortFilePathsByGitRoot(Collection<FilePath> files) throws VcsException {
        return sortFilePathsByGitRoot(files, false);
    }

    /**
     * Sort files by vcs root
     *
     * @param files        files to sort.
     * @param ignoreNonGit if true, non-git files are ignored
     * @return the map from root to the files under the root
     * @throws VcsException if non git files are passed when {@code ignoreNonGit} is false
     */
    @Nonnull
    public static Map<VirtualFile, List<FilePath>> sortFilePathsByGitRoot(
        @Nonnull Collection<FilePath> files,
        boolean ignoreNonGit
    ) throws VcsException {
        Map<VirtualFile, List<FilePath>> rc = new HashMap<>();
        for (FilePath p : files) {
            VirtualFile root = getGitRootOrNull(p);
            if (root == null) {
                if (ignoreNonGit) {
                    continue;
                }
                else {
                    throw new VcsException("The file " + p.getPath() + " is not under Git");
                }
            }
            List<FilePath> l = rc.get(root);
            if (l == null) {
                l = new ArrayList<>();
                rc.put(root, l);
            }
            l.add(p);
        }
        return rc;
    }

    /**
     * Parse UNIX timestamp as it is returned by the git
     *
     * @param value a value to parse
     * @return timestamp as {@link Date} object
     */
    public static Date parseTimestamp(String value) {
        long parsed;
        parsed = Long.parseLong(value.trim());
        return new Date(parsed * 1000);
    }

    /**
     * Parse UNIX timestamp returned from Git and handle {@link NumberFormatException} if one happens: return new {@link Date} and
     * log the error properly.
     * In some cases git output gets corrupted and this method is intended to catch the reason, why.
     *
     * @param value     Value to parse.
     * @param handler   Git handler that was called to received the output.
     * @param gitOutput Git output.
     * @return Parsed Date or <code>new Date</code> in the case of error.
     */
    public static Date parseTimestampWithNFEReport(String value, GitHandler handler, String gitOutput) {
        try {
            return parseTimestamp(value);
        }
        catch (NumberFormatException e) {
            LOG.error("annotate(). NFE. Handler: " + handler + ". Output: " + gitOutput, e);
            return new Date();
        }
    }

    /**
     * Get git roots from content roots
     *
     * @param roots git content roots
     * @return a content root
     */
    public static Set<VirtualFile> gitRootsForPaths(Collection<VirtualFile> roots) {
        Set<VirtualFile> rc = new HashSet<>();
        for (VirtualFile root : roots) {
            VirtualFile f = root;
            do {
                if (f.findFileByRelativePath(DOT_GIT) != null) {
                    rc.add(f);
                    break;
                }
                f = f.getParent();
            }
            while (f != null);
        }
        return rc;
    }

    /**
     * Return a git root for the file path (the parent directory with ".git" subdirectory)
     *
     * @param filePath a file path
     * @return git root for the file
     * @throws IllegalArgumentException if the file is not under git
     * @throws VcsException             if the file is not under git
     * @use GitRepositoryManager#getRepositoryForFile().
     * @deprecated because uses the java.io.File.
     */
    public static VirtualFile getGitRoot(@Nonnull FilePath filePath) throws VcsException {
        VirtualFile root = getGitRootOrNull(filePath);
        if (root != null) {
            return root;
        }
        throw new VcsException("The file " + filePath + " is not under git.");
    }

    /**
     * Return a git root for the file path (the parent directory with ".git" subdirectory)
     *
     * @param filePath a file path
     * @return git root for the file or null if the file is not under git
     * @use GitRepositoryManager#getRepositoryForFile().
     * @deprecated because uses the java.io.File.
     */
    @Deprecated
    @Nullable
    public static VirtualFile getGitRootOrNull(@Nonnull FilePath filePath) {
        return getGitRootOrNull(filePath.getIOFile());
    }

    public static boolean isGitRoot(@Nonnull File folder) {
        return findGitDir(folder) != null;
    }

    /**
     * @use GitRepositoryManager#getRepositoryForFile().
     * @deprecated because uses the java.io.File.
     */
    @Deprecated
    @Nullable
    public static VirtualFile getGitRootOrNull(File file) {
        File root = file;
        while (root != null && (!root.exists() || !root.isDirectory() || !new File(root, DOT_GIT).exists())) {
            root = root.getParentFile();
        }
        return root == null ? null : LocalFileSystem.getInstance().findFileByIoFile(root);
    }

    /**
     * Return a git root for the file (the parent directory with ".git" subdirectory)
     *
     * @param file the file to check
     * @return git root for the file
     * @throws VcsException if the file is not under git
     * @use GitRepositoryManager#getRepositoryForFile().
     * @deprecated because uses the java.io.File.
     */
    public static VirtualFile getGitRoot(@Nonnull VirtualFile file) throws VcsException {
        VirtualFile root = gitRootOrNull(file);
        if (root != null) {
            return root;
        }
        else {
            throw new VcsException("The file " + file.getPath() + " is not under git.");
        }
    }

    /**
     * Return a git root for the file (the parent directory with ".git" subdirectory)
     *
     * @param file the file to check
     * @return git root for the file or null if the file is not not under Git
     * @use GitRepositoryManager#getRepositoryForFile().
     * @deprecated because uses the java.io.File.
     */
    @Nullable
    public static VirtualFile gitRootOrNull(VirtualFile file) {
        if (file instanceof AbstractVcsVirtualFile) {
            return getGitRootOrNull(VcsUtil.getFilePath(file.getPath()));
        }
        VirtualFile root = file;
        while (root != null) {
            if (root.findFileByRelativePath(DOT_GIT) != null) {
                return root;
            }
            root = root.getParent();
        }
        return root;
    }

    /**
     * Get git roots for the project. The method shows dialogs in the case when roots cannot be retrieved, so it should be called
     * from the event dispatch thread.
     *
     * @param project the project
     * @param vcs     the git Vcs
     * @return the list of the roots
     * @use GitRepositoryManager#getRepositoryForFile().
     * @deprecated because uses the java.io.File.
     */
    @Nonnull
    public static List<VirtualFile> getGitRoots(Project project, GitVcs vcs) throws VcsException {
        VirtualFile[] contentRoots = ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs);
        if (contentRoots == null || contentRoots.length == 0) {
            throw new VcsException(GitLocalize.repositoryActionMissingRootsUnconfiguredMessage());
        }
        List<VirtualFile> sortedRoots = DvcsUtil.sortVirtualFilesByPresentation(gitRootsForPaths(Arrays.asList(contentRoots)));
        if (sortedRoots.size() == 0) {
            throw new VcsException(GitLocalize.repositoryActionMissingRootsMisconfigured());
        }
        return sortedRoots;
    }

    /**
     * Check if the virtual file under git
     *
     * @param vFile a virtual file
     * @return true if the file is under git
     */
    public static boolean isUnderGit(VirtualFile vFile) {
        return gitRootOrNull(vFile) != null;
    }

    /**
     * Return committer name based on author name and committer name
     *
     * @param authorName    the name of author
     * @param committerName the name of committer
     * @return just a name if they are equal, or name that includes both author and committer
     */
    public static String adjustAuthorName(String authorName, String committerName) {
        if (!authorName.equals(committerName)) {
            //noinspection HardCodedStringLiteral
            committerName = authorName + ", via " + committerName;
        }
        return committerName;
    }

    /**
     * Check if the file path is under git
     *
     * @param path the path
     * @return true if the file path is under git
     */
    public static boolean isUnderGit(FilePath path) {
        return getGitRootOrNull(path) != null;
    }

    /**
     * Get git roots for the selected paths
     *
     * @param filePaths the context paths
     * @return a set of git roots
     */
    public static Set<VirtualFile> gitRoots(Collection<FilePath> filePaths) {
        Set<VirtualFile> rc = new HashSet<>();
        for (FilePath path : filePaths) {
            VirtualFile root = getGitRootOrNull(path);
            if (root != null) {
                rc.add(root);
            }
        }
        return rc;
    }

    /**
     * Get git time (UNIX time) basing on the date object
     *
     * @param time the time to convert
     * @return the time in git format
     */
    public static String gitTime(Date time) {
        long t = time.getTime() / 1000;
        return Long.toString(t);
    }

    /**
     * Format revision number from long to 16-digit abbreviated revision
     *
     * @param rev the abbreviated revision number as long
     * @return the revision string
     */
    public static String formatLongRev(long rev) {
        return String.format("%015x%x", (rev >>> 4), rev & 0xF);
    }

    public static void getLocalCommittedChanges(
        Project project,
        VirtualFile root,
        Consumer<GitSimpleHandler> parametersSpecifier,
        Consumer<GitCommittedChangeList> consumer,
        boolean skipDiffsForMerge
    ) throws VcsException {
        GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.LOG);
        h.setSilent(true);
        h.addParameters("--pretty=format:%x04%x01" + GitChangeUtils.COMMITTED_CHANGELIST_FORMAT, "--name-status");
        parametersSpecifier.accept(h);

        String output = h.run();
        LOG.debug("getLocalCommittedChanges output: '" + output + "'");
        StringScanner s = new StringScanner(output);
        StringBuilder sb = new StringBuilder();
        boolean firstStep = true;
        while (s.hasMoreData()) {
            String line = s.line();
            boolean lineIsAStart = line.startsWith("\u0004\u0001");
            if (!firstStep && lineIsAStart) {
                StringScanner innerScanner = new StringScanner(sb.toString());
                sb.setLength(0);
                consumer.accept(GitChangeUtils.parseChangeList(project, root, innerScanner, skipDiffsForMerge, h, false, false));
            }
            sb.append(lineIsAStart ? line.substring(2) : line).append('\n');
            firstStep = false;
        }
        if (sb.length() > 0) {
            StringScanner innerScanner = new StringScanner(sb.toString());
            sb.setLength(0);
            consumer.accept(GitChangeUtils.parseChangeList(project, root, innerScanner, skipDiffsForMerge, h, false, false));
        }
        if (s.hasMoreData()) {
            throw new IllegalStateException("More input is available: " + s.line());
        }
    }

    public static List<GitCommittedChangeList> getLocalCommittedChanges(
        Project project,
        VirtualFile root,
        Consumer<GitSimpleHandler> parametersSpecifier
    ) throws VcsException {
        List<GitCommittedChangeList> rc = new ArrayList<>();

        getLocalCommittedChanges(project, root, parametersSpecifier, rc::add, false);

        return rc;
    }

    /**
     * <p>Unescape path returned by Git.</p>
     * <p>
     * If there are quotes in the file name, Git not only escapes them, but also encloses the file name into quotes:
     * <code>"\"quote"</code>
     * </p>
     * <p>
     * If there are spaces in the file name, Git displays the name as is, without escaping spaces and without enclosing name in quotes.
     * </p>
     *
     * @param path a path to unescape
     * @return unescaped path ready to be searched in the VFS or file system.
     * @throws VcsException if the path in invalid
     */
    @Nonnull
    public static String unescapePath(@Nonnull String path) throws VcsException {
        String QUOTE = "\"";
        if (path.startsWith(QUOTE) && path.endsWith(QUOTE)) {
            path = path.substring(1, path.length() - 1);
        }

        int l = path.length();
        StringBuilder rc = new StringBuilder(l);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\') {
                //noinspection AssignmentToForLoopParameter
                i++;
                if (i >= l) {
                    throw new VcsException("Unterminated escape sequence in the path: " + path);
                }
                char e = path.charAt(i);
                switch (e) {
                    case '\\':
                        rc.append('\\');
                        break;
                    case 't':
                        rc.append('\t');
                        break;
                    case 'n':
                        rc.append('\n');
                        break;
                    case '"':
                        rc.append('"');
                        break;
                    default:
                        if (VcsFileUtil.isOctal(e)) {
                            // collect sequence of characters as a byte array.
                            // count bytes first
                            int n = 0;
                            for (int j = i; j < l; ) {
                                if (VcsFileUtil.isOctal(path.charAt(j))) {
                                    n++;
                                    for (int k = 0; k < 3 && j < l && VcsFileUtil.isOctal(path.charAt(j)); k++) {
                                        //noinspection AssignmentToForLoopParameter
                                        j++;
                                    }
                                }
                                if (j + 1 >= l || path.charAt(j) != '\\' || !VcsFileUtil.isOctal(path.charAt(j + 1))) {
                                    break;
                                }
                                //noinspection AssignmentToForLoopParameter
                                j++;
                            }
                            // convert to byte array
                            byte[] b = new byte[n];
                            n = 0;
                            while (i < l) {
                                if (VcsFileUtil.isOctal(path.charAt(i))) {
                                    int code = 0;
                                    for (int k = 0; k < 3 && i < l && VcsFileUtil.isOctal(path.charAt(i)); k++) {
                                        code = code * 8 + (path.charAt(i) - '0');
                                        //noinspection AssignmentToForLoopParameter
                                        i++;
                                    }
                                    b[n++] = (byte) code;
                                }
                                if (i + 1 >= l || path.charAt(i) != '\\' || !VcsFileUtil.isOctal(path.charAt(i + 1))) {
                                    break;
                                }
                                //noinspection AssignmentToForLoopParameter
                                i++;
                            }
                            //noinspection AssignmentToForLoopParameter
                            i--;
                            assert n == b.length;
                            // add them to string
                            String encoding = GitConfigUtil.getFileNameEncoding();
                            try {
                                rc.append(new String(b, encoding));
                            }
                            catch (UnsupportedEncodingException e1) {
                                throw new IllegalStateException("The file name encoding is unsupported: " + encoding);
                            }
                        }
                        else {
                            throw new VcsException("Unknown escape sequence '\\" + path.charAt(i) + "' in the path: " + path);
                        }
                }
            }
            else {
                rc.append(c);
            }
        }
        return rc.toString();
    }

    public static boolean justOneGitRepository(Project project) {
        if (project.isDisposed()) {
            return true;
        }
        GitRepositoryManager manager = getRepositoryManager(project);
        return !manager.moreThanOneRoot();
    }

    @Nullable
    public static GitRemote findRemoteByName(@Nonnull GitRepository repository, @Nonnull String name) {
        return findRemoteByName(repository.getRemotes(), name);
    }

    @Nullable
    public static GitRemote findRemoteByName(Collection<GitRemote> remotes, @Nonnull String name) {
        return ContainerUtil.find(remotes, remote -> remote.getName().equals(name));
    }

    @Nullable
    public static GitRemoteBranch findRemoteBranch(
        @Nonnull GitRepository repository,
        @Nonnull GitRemote remote,
        @Nonnull String nameAtRemote
    ) {
        return ContainerUtil.find(
            repository.getBranches().getRemoteBranches(),
            remoteBranch -> remoteBranch.getRemote().equals(remote)
                && remoteBranch.getNameForRemoteOperations().equals(GitBranchUtil.stripRefsPrefix(nameAtRemote))
        );
    }

    @Nonnull
    public static GitRemoteBranch findOrCreateRemoteBranch(
        @Nonnull GitRepository repository,
        @Nonnull GitRemote remote,
        @Nonnull String branchName
    ) {
        GitRemoteBranch remoteBranch = findRemoteBranch(repository, remote, branchName);
        return ObjectUtil.notNull(remoteBranch, new GitStandardRemoteBranch(remote, branchName));
    }

    @Nullable
    public static GitRemote findOrigin(Collection<GitRemote> remotes) {
        for (GitRemote remote : remotes) {
            if (remote.getName().equals("origin")) {
                return remote;
            }
        }
        return null;
    }

    @Nonnull
    public static Collection<VirtualFile> getRootsFromRepositories(@Nonnull Collection<GitRepository> repositories) {
        return ContainerUtil.map(repositories, REPOSITORY_TO_ROOT);
    }

    @Nonnull
    public static VirtualFile getRootForFile(@Nonnull Project project, @Nonnull FilePath filePath) throws VcsException {
        VcsRoot root = ProjectLevelVcsManager.getInstance(project).getVcsRootObjectFor(filePath);
        if (isGitVcsRoot(root)) {
            return root.getPath();
        }

        Repository repository = VcsRepositoryManager.getInstance(project).getExternalRepositoryForFile(filePath);
        if (repository instanceof GitRepository) {
            return repository.getRoot();
        }
        throw new GitRepositoryNotFoundException(filePath);
    }

    private static boolean isGitVcsRoot(@Nullable VcsRoot root) {
        if (root == null) {
            return false;
        }
        AbstractVcs vcs = root.getVcs();

        return vcs != null && GitVcs.getKey().equals(vcs.getKeyInstanceMethod());
    }

    @Nonnull
    public static Collection<GitRepository> getRepositoriesFromRoots(
        @Nonnull GitRepositoryManager repositoryManager,
        @Nonnull Collection<VirtualFile> roots
    ) {
        Collection<GitRepository> repositories = new ArrayList<>(roots.size());
        for (VirtualFile root : roots) {
            GitRepository repo = repositoryManager.getRepositoryForRoot(root);
            if (repo == null) {
                LOG.error("Repository not found for root " + root);
            }
            else {
                repositories.add(repo);
            }
        }
        return repositories;
    }

    /**
     * Returns absolute paths which have changed remotely comparing to the current branch, i.e. performs
     * <code>git diff --name-only master..origin/master</code>
     * <p>
     * Paths are absolute, Git-formatted (i.e. with forward slashes).
     */
    @Nonnull
    public static Collection<String> getPathsDiffBetweenRefs(
        @Nonnull Git git,
        @Nonnull GitRepository repository,
        @Nonnull String beforeRef,
        @Nonnull String afterRef
    ) throws VcsException {
        List<String> parameters = Arrays.asList("--name-only", "--pretty=format:");
        String range = beforeRef + ".." + afterRef;
        GitCommandResult result = git.diff(repository, parameters, range);
        if (!result.success()) {
            LOG.info(String.format("Couldn't get diff in range [%s] for repository [%s]", range, repository.toLogString()));
            return Collections.emptyList();
        }

        Collection<String> remoteChanges = new HashSet<>();
        for (StringScanner s = new StringScanner(result.getOutputAsJoinedString()); s.hasMoreData(); ) {
            String relative = s.line();
            if (StringUtil.isEmptyOrSpaces(relative)) {
                continue;
            }
            String path = repository.getRoot().getPath() + "/" + unescapePath(relative);
            remoteChanges.add(path);
        }
        return remoteChanges;
    }

    @Nonnull
    public static GitRepositoryManager getRepositoryManager(@Nonnull Project project) {
        return GitRepositoryManager.getInstance(project);
    }

    @Nullable
    public static GitRepository getRepositoryForRootOrLogError(@Nonnull Project project, @Nonnull VirtualFile root) {
        GitRepositoryManager manager = getRepositoryManager(project);
        GitRepository repository = manager.getRepositoryForRoot(root);
        if (repository == null) {
            LOG.error("Repository is null for root " + root);
        }
        return repository;
    }

    @Nonnull
    public static String getPrintableRemotes(@Nonnull Collection<GitRemote> remotes) {
        return StringUtil.join(remotes, remote -> remote.getName() + ": [" + StringUtil.join(remote.getUrls(), ", ") + "]", "\n");
    }

    /**
     * Show changes made in the specified revision.
     *
     * @param project    the project
     * @param revision   the revision number
     * @param file       the file affected by the revision
     * @param local      pass true to let the diff be editable, i.e. making the revision "at the right" be a local (current) revision.
     *                   pass false to let both sides of the diff be non-editable.
     * @param revertible pass true to let "Revert" action be active.
     */
    public static void showSubmittedFiles(
        final Project project,
        final String revision,
        final VirtualFile file,
        final boolean local,
        final boolean revertible
    ) {
        new Task.Backgroundable(project, GitLocalize.changesRetrieving(revision)) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    VirtualFile vcsRoot = getGitRoot(file);
                    CommittedChangeList changeList = GitChangeUtils.getRevisionChanges(project, vcsRoot, revision, true, local, revertible);
                    UIUtil.invokeLaterIfNeeded(
                        () -> AbstractVcsHelper.getInstance(project)
                            .showChangesListBrowser(changeList, GitLocalize.pathsAffectedTitle(revision))
                    );
                }
                catch (VcsException e) {
                    UIUtil.invokeLaterIfNeeded(() -> GitUIUtil.showOperationError(project, e, "git show"));
                }
            }
        }.queue();
    }


    /**
     * Returns the tracking information (remote and the name of the remote branch), or null if we are not on a branch.
     */
    @Nullable
    public static GitBranchTrackInfo getTrackInfoForCurrentBranch(@Nonnull GitRepository repository) {
        GitLocalBranch currentBranch = repository.getCurrentBranch();
        if (currentBranch == null) {
            return null;
        }
        return GitBranchUtil.getTrackInfoForBranch(repository, currentBranch);
    }

    @Nonnull
    public static Collection<GitRepository> getRepositoriesForFiles(@Nonnull Project project, @Nonnull Collection<VirtualFile> files) {
        GitRepositoryManager manager = getRepositoryManager(project);
        Function<VirtualFile, GitRepository> rootToRepo = root -> root != null ? manager.getRepositoryForRoot(root) : null;
        return ContainerUtil.filter(
            ContainerUtil.map(sortFilesByGitRootsIgnoringOthers(files).keySet(), rootToRepo),
            Objects::nonNull
        );
    }

    @Nonnull
    public static Map<VirtualFile, List<VirtualFile>> sortFilesByGitRootsIgnoringOthers(@Nonnull Collection<VirtualFile> files) {
        try {
            return sortFilesByGitRoot(files, true);
        }
        catch (VcsException e) {
            LOG.error("Should never happen, since we passed 'ignore non-git' parameter", e);
            return Collections.emptyMap();
        }
    }

    /**
     * git diff --name-only [--cached]
     *
     * @param staged  if true checks the staging area, if false checks unstaged files.
     * @param project
     * @param root
     * @return true if there is anything in the unstaged/staging area, false if the unstaged/staging area is empty.
     */
    public static boolean hasLocalChanges(boolean staged, Project project, VirtualFile root) throws VcsException {
        GitSimpleHandler diff = new GitSimpleHandler(project, root, GitCommand.DIFF);
        diff.addParameters("--name-only");
        if (staged) {
            diff.addParameters("--cached");
        }
        diff.setStdoutSuppressed(true);
        diff.setStderrSuppressed(true);
        diff.setSilent(true);
        String output = diff.run();
        return !output.trim().isEmpty();
    }

    @Nullable
    public static VirtualFile findRefreshFileOrLog(@Nonnull String absolutePath) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(absolutePath);
        if (file == null) {
            file = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);
        }
        if (file == null) {
            LOG.warn("VirtualFile not found for " + absolutePath);
        }
        return file;
    }

    @Nonnull
    public static String toAbsolute(@Nonnull VirtualFile root, @Nonnull String relativePath) {
        return StringUtil.trimEnd(root.getPath(), "/") + "/" + StringUtil.trimStart(relativePath, "/");
    }

    @Nonnull
    public static Collection<String> toAbsolute(@Nonnull VirtualFile root, @Nonnull Collection<String> relativePaths) {
        return ContainerUtil.map(relativePaths, s -> toAbsolute(root, s));
    }

    /**
     * Given the list of paths converts them to the list of {@link Change Changes} found in the {@link ChangeListManager},
     * i.e. this works only for local changes. </br>
     * Paths can be absolute or relative to the repository.
     * If a path is not found in the local changes, it is ignored, but the fact is logged.
     */
    @Nonnull
    public static List<Change> findLocalChangesForPaths(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nonnull Collection<String> affectedPaths,
        boolean relativePaths
    ) {
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        List<Change> affectedChanges = new ArrayList<>();
        for (String path : affectedPaths) {
            String absolutePath = relativePaths ? toAbsolute(root, path) : path;
            VirtualFile file = findRefreshFileOrLog(absolutePath);
            if (file != null) {
                Change change = changeListManager.getChange(file);
                if (change != null) {
                    affectedChanges.add(change);
                }
                else {
                    String message = "Change is not found for " + file.getPath();
                    if (changeListManager.isInUpdate()) {
                        message += " because ChangeListManager is being updated.";
                        LOG.debug(message);
                    }
                    else {
                        LOG.info(message);
                    }
                }
            }
        }
        return affectedChanges;
    }

    @RequiredUIAccess
    public static void showPathsInDialog(
        @Nonnull Project project,
        @Nonnull Collection<String> absolutePaths,
        @Nonnull LocalizeValue title,
        @Nonnull LocalizeValue description
    ) {
        DialogBuilder builder = new DialogBuilder(project);
        builder.setCenterPanel(new GitSimplePathsBrowser(project, absolutePaths));
        if (description.isNotEmpty()) {
            builder.setNorthPanel(new MultiLineLabel(description.get()));
        }
        builder.addOkAction();
        builder.setTitle(title);
        builder.show();
    }

    @Nonnull
    public static String cleanupErrorPrefixes(@Nonnull String msg) {
        String[] PREFIXES = {
            "fatal:",
            "error:"
        };
        msg = msg.trim();
        for (String prefix : PREFIXES) {
            if (msg.startsWith(prefix)) {
                msg = msg.substring(prefix.length()).trim();
            }
        }
        return msg;
    }

    @Nullable
    public static GitRemote getDefaultRemote(@Nonnull Collection<GitRemote> remotes) {
        for (GitRemote remote : remotes) {
            if (remote.getName().equals(GitRemote.ORIGIN_NAME)) {
                return remote;
            }
        }
        return null;
    }

    @Nonnull
    public static String joinToHtml(@Nonnull Collection<GitRepository> repositories) {
        return StringUtil.join(repositories, Repository::getPresentableUrl, "<br/>");
    }

    @Nonnull
    public static String mention(@Nonnull GitRepository repository) {
        return getRepositoryManager(repository.getProject()).moreThanOneRoot() ? " in " + getShortRepositoryName(repository) : "";
    }

    @Nonnull
    public static String mention(@Nonnull Collection<GitRepository> repositories) {
        return mention(repositories, -1);
    }

    @Nonnull
    public static String mention(@Nonnull Collection<GitRepository> repositories, int limit) {
        if (repositories.isEmpty()) {
            return "";
        }
        return " in " + joinShortNames(repositories, limit);
    }

    public static void updateRepositories(@Nonnull Collection<GitRepository> repositories) {
        for (GitRepository repository : repositories) {
            repository.update();
        }
    }

    public static boolean hasGitRepositories(@Nonnull Project project) {
        return !getRepositories(project).isEmpty();
    }

    @Nonnull
    public static Collection<GitRepository> getRepositories(@Nonnull Project project) {
        return getRepositoryManager(project).getRepositories();
    }

    @Nonnull
    public static Collection<GitRepository> getRepositoriesInState(@Nonnull Project project, @Nonnull Repository.State state) {
        return ContainerUtil.filter(getRepositories(project), repository -> repository.getState() == state);
    }

    /**
     * Checks if the given paths are equal only by case.
     * It is expected that the paths are different at least by the case.
     */
    public static boolean isCaseOnlyChange(@Nonnull String oldPath, @Nonnull String newPath) {
        if (oldPath.equalsIgnoreCase(newPath)) {
            if (oldPath.equals(newPath)) {
                LOG.error("Comparing perfectly equal paths: " + newPath);
            }
            return true;
        }
        return false;
    }

    @Nonnull
    public static String getLogString(@Nonnull String root, @Nonnull Collection<Change> changes) {
        return StringUtil.join(
            changes,
            change -> {
                ContentRevision after = change.getAfterRevision();
                ContentRevision before = change.getBeforeRevision();
                return switch (change.getType()) {
                    case NEW -> "A: " + getRelativePath(root, assertNotNull(after));
                    case DELETED -> "D: " + getRelativePath(root, assertNotNull(before));
                    case MOVED ->
                        "M: " + getRelativePath(root, assertNotNull(before)) + " -> " + getRelativePath(root, assertNotNull(after));
                    default -> "M: " + getRelativePath(root, assertNotNull(after));
                };
            },
            ", "
        );
    }

    @Nullable
    public static String getRelativePath(@Nonnull String root, @Nonnull ContentRevision after) {
        return FileUtil.getRelativePath(root, after.getFile().getPath(), File.separatorChar);
    }

    /**
     * <p>Finds the local changes which are "the same" as the given changes.</p>
     * <p>The purpose of this method is to get actual local changes after some other changes were applied to the working tree
     * (e.g. if they were cherry-picked from a commit). Working with the original non-local changes is limited, in particular,
     * the difference between content revisions may be not the same as the local change.</p>
     * <p>"The same" here means the changes made in the same files. It is possible that there was a change made in file A in the original
     * commit, but there are no local changes made in file A. Such situations are ignored.</p>
     */
    @Nonnull
    public static Collection<Change> findCorrespondentLocalChanges(
        @Nonnull ChangeListManager changeListManager,
        @Nonnull Collection<Change> originalChanges
    ) {
        Interner<Change> allChanges = Interner.createHashInterner(HashingStrategy.canonical());
        allChanges.internAll(changeListManager.getAllChanges());

        return ContainerUtil.mapNotNull(originalChanges, allChanges::get);
    }

    public static boolean isHashString(@Nonnull String revision) {
        return HASH_STRING_PATTERN.matcher(revision).matches();
    }
}
