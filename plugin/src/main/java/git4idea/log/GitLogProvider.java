/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package git4idea.log;

import consulo.annotation.component.ExtensionImpl;
import consulo.component.messagebus.MessageBusConnection;
import consulo.disposer.Disposable;
import consulo.logging.Logger;
import consulo.logging.attachment.AttachmentFactory;
import consulo.project.Project;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.HashingStrategy;
import consulo.util.interner.Interner;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.StringUtil;
import consulo.util.lang.function.ThrowableFunction;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.log.*;
import consulo.versionControlSystem.log.base.HashImpl;
import consulo.versionControlSystem.log.graph.GraphColorManager;
import consulo.versionControlSystem.log.graph.GraphCommit;
import consulo.versionControlSystem.log.graph.PermanentGraph;
import consulo.versionControlSystem.log.util.UserNameRegex;
import consulo.versionControlSystem.log.util.VcsUserUtil;
import consulo.versionControlSystem.util.StopWatch;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.*;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitBranchesCollection;
import git4idea.config.GitVersionSpecialty;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@ExtensionImpl
public class GitLogProvider implements VcsLogProvider {
    private static final Logger LOG = Logger.getInstance(GitLogProvider.class);
    public static final Function<VcsRef, String> GET_TAG_NAME = ref -> ref.getType() == GitRefManager.TAG ? ref.getName() : null;
    public static final HashingStrategy<VcsRef> DONT_CONSIDER_SHA = new HashingStrategy<>() {
        @Override
        public int hashCode(@Nonnull VcsRef ref) {
            return 31 * ref.getName().hashCode() + ref.getType().hashCode();
        }

        @Override
        public boolean equals(@Nonnull VcsRef ref1, @Nonnull VcsRef ref2) {
            return ref1.getName().equals(ref2.getName()) && ref1.getType().equals(ref2.getType());
        }
    };

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final GitVcs myVcs;
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;
    @Nonnull
    private final GitUserRegistry myUserRegistry;
    @Nonnull
    private final VcsLogRefManager myRefSorter;
    @Nonnull
    private final VcsLogObjectsFactory myVcsObjectsFactory;

    @Inject
    public GitLogProvider(
        @Nonnull Project project,
        @Nonnull VcsLogObjectsFactory factory,
        @Nonnull GitUserRegistry userRegistry
    ) {
        myProject = project;
        myRepositoryManager = GitRepositoryManager.getInstance(project);
        myUserRegistry = userRegistry;
        myRefSorter = new GitRefManager(myRepositoryManager);
        myVcsObjectsFactory = factory;
        myVcs = ObjectUtil.assertNotNull(GitVcs.getInstance(project));
    }

    @Nonnull
    @Override
    public DetailedLogData readFirstBlock(@Nonnull VirtualFile root, @Nonnull Requirements requirements) throws VcsException {
        if (!isRepositoryReady(root)) {
            return LogDataImpl.empty();
        }
        GitRepository repository = ObjectUtil.assertNotNull(myRepositoryManager.getRepositoryForRoot(root));

        // need to query more to sort them manually; this doesn't affect performance: it is equal for -1000 and -2000
        int commitCount = requirements.getCommitCount() * 2;

        String[] params = new String[]{
            "HEAD",
            "--branches",
            "--remotes",
            "--max-count=" + commitCount
        };
        // NB: not specifying --tags, because it introduces great slowdown if there are many tags,
        // but makes sense only if there are heads without branch or HEAD labels (rare case). Such cases are partially handled below.

        boolean refresh = requirements instanceof VcsLogProviderRequirementsEx requirementsEx && requirementsEx.isRefresh();

        DetailedLogData data = GitHistoryUtils.loadMetadata(myProject, root, params);

        Set<VcsRef> safeRefs = data.getRefs();
        Interner<VcsRef> allRefs = Interner.createHashInterner(DONT_CONSIDER_SHA);
        allRefs.internAll(safeRefs);

        Set<VcsRef> branches = readBranches(repository);
        addNewElements(allRefs, branches);

        Collection<VcsCommitMetadata> allDetails;
        Set<String> currentTagNames = null;
        DetailedLogData commitsFromTags = null;
        if (!refresh) {
            allDetails = data.getCommits();
        }
        else {
            // on refresh: get new tags, which point to commits not from the first block; then get history, walking down just from these tags
            // on init: just ignore such tagged-only branches. The price for speed-up.
            VcsLogProviderRequirementsEx rex = (VcsLogProviderRequirementsEx) requirements;

            currentTagNames = readCurrentTagNames(root);
            addOldStillExistingTags(allRefs, currentTagNames, rex.getPreviousRefs());

            allDetails = newHashSet(data.getCommits());

            Set<String> previousTags = newHashSet(ContainerUtil.mapNotNull(rex.getPreviousRefs(), GET_TAG_NAME));
            Set<String> safeTags = newHashSet(ContainerUtil.mapNotNull(safeRefs, GET_TAG_NAME));
            Set<String> newUnmatchedTags = remove(currentTagNames, previousTags, safeTags);

            if (!newUnmatchedTags.isEmpty()) {
                commitsFromTags = loadSomeCommitsOnTaggedBranches(root, commitCount, newUnmatchedTags);
                addNewElements(allDetails, commitsFromTags.getCommits());
                addNewElements(allRefs, commitsFromTags.getRefs());
            }
        }

        StopWatch sw = StopWatch.start("sorting commits in " + root.getName());
        List<VcsCommitMetadata> sortedCommits = VcsLogHelper.getInstance().sortByDateTopoOrder(allDetails);
        sortedCommits = sortedCommits.subList(0, Math.min(sortedCommits.size(), requirements.getCommitCount()));
        sw.report();

        if (LOG.isDebugEnabled()) {
            validateDataAndReportError(root, allRefs, sortedCommits, data, branches, currentTagNames, commitsFromTags);
        }

        return new LogDataImpl(allRefs.getValues(), sortedCommits);
    }

    private static void validateDataAndReportError(
        @Nonnull final VirtualFile root,
        @Nonnull final Interner<VcsRef> allRefs,
        @Nonnull final List<VcsCommitMetadata> sortedCommits,
        @Nonnull final DetailedLogData firstBlockSyncData,
        @Nonnull final Set<VcsRef> manuallyReadBranches,
        @Nullable final Set<String> currentTagNames,
        @Nullable final DetailedLogData commitsFromTags
    ) {
        StopWatch sw = StopWatch.start("validating data in " + root.getName());
        final Set<Hash> refs = ContainerUtil.map2Set(allRefs.getValues(), VcsRef::getCommitHash);

        PermanentGraph.newInstance(sortedCommits, new GraphColorManager<>() {
            @Override
            public int getColorOfBranch(@Nonnull Hash headCommit) {
                return 0;
            }

            @Override
            public int getColorOfFragment(@Nonnull Hash headCommit, int magicIndex) {
                return 0;
            }

            @Override
            public int compareHeads(@Nonnull Hash head1, @Nonnull Hash head2) {
                if (!refs.contains(head1) || !refs.contains(head2)) {
                    LOG.error(
                        "GitLogProvider returned inconsistent data",
                        AttachmentFactory.get().create("error-details.txt", printErrorDetails(
                            root,
                            allRefs,
                            sortedCommits,
                            firstBlockSyncData,
                            manuallyReadBranches,
                            currentTagNames,
                            commitsFromTags
                        ))
                    );
                }
                return 0;
            }
        }, refs);
        sw.report();
    }

    @SuppressWarnings("StringConcatenationInsideStringBufferAppend")
    private static String printErrorDetails(
        @Nonnull VirtualFile root,
        @Nonnull Interner<VcsRef> allRefs,
        @Nonnull List<VcsCommitMetadata> sortedCommits,
        @Nonnull DetailedLogData firstBlockSyncData,
        @Nonnull Set<VcsRef> manuallyReadBranches,
        @Nullable Set<String> currentTagNames,
        @Nullable DetailedLogData commitsFromTags
    ) {

        StringBuilder sb = new StringBuilder();
        sb.append("[" + root.getName() + "]\n");
        sb.append("First block data from Git:\n");
        sb.append(printLogData(firstBlockSyncData));
        sb.append("\n\nManually read refs:\n");
        sb.append(printRefs(manuallyReadBranches));
        sb.append("\n\nCurrent tag names:\n");
        if (currentTagNames != null) {
            sb.append(StringUtil.join(currentTagNames, ", "));
            if (commitsFromTags != null) {
                sb.append(printLogData(commitsFromTags));
            }
            else {
                sb.append("\n\nCommits from new tags were not read.\n");
            }
        }
        else {
            sb.append("\n\nCurrent tags were not read\n");
        }

        sb.append("\n\nResult:\n");
        sb.append("\nCommits (last 100): \n");
        sb.append(printCommits(sortedCommits));
        sb.append("\nAll refs:\n");
        sb.append(printRefs(allRefs.getValues()));
        return sb.toString();
    }

    @Nonnull
    private static String printLogData(@Nonnull DetailedLogData firstBlockSyncData) {
        return String.format(
            "Last 100 commits:\n%s\nRefs:\n%s",
            printCommits(firstBlockSyncData.getCommits()),
            printRefs(firstBlockSyncData.getRefs())
        );
    }

    @Nonnull
    private static String printCommits(@Nonnull List<VcsCommitMetadata> commits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(commits.size(), 100); i++) {
            GraphCommit<Hash> commit = commits.get(i);
            sb.append(String.format(
                "%s -> %s\n",
                commit.getId().toShortString(),
                StringUtil.join(commit.getParents(), Hash::toShortString, ", ")
            ));
        }
        return sb.toString();
    }

    @Nonnull
    private static String printRefs(@Nonnull Set<VcsRef> refs) {
        return StringUtil.join(refs, ref -> ref.getCommitHash().toShortString() + " : " + ref.getName(), "\n");
    }

    private static void addOldStillExistingTags(
        @Nonnull Interner<VcsRef> allRefs,
        @Nonnull Set<String> currentTags,
        @Nonnull Collection<VcsRef> previousRefs
    ) {
        for (VcsRef ref : previousRefs) {
            VcsRef t = allRefs.get(ref);
            if (t == null && currentTags.contains(ref.getName())) {
                allRefs.intern(ref);
            }
        }
    }

    @Nonnull
    private Set<String> readCurrentTagNames(@Nonnull VirtualFile root) throws VcsException {
        StopWatch sw = StopWatch.start("reading tags in " + root.getName());
        Set<String> tags = newHashSet();
        GitTag.listAsStrings(myProject, root, tags, null);
        sw.report();
        return tags;
    }

    @Nonnull
    @SafeVarargs
    private static <T> Set<T> remove(@Nonnull Set<T> original, @Nonnull Set<T>... toRemove) {
        Set<T> result = newHashSet(original);
        for (Set<T> set : toRemove) {
            result.removeAll(set);
        }
        return result;
    }

    private static <T> void addNewElements(@Nonnull Interner<T> original, @Nonnull Collection<T> toAdd) {
        for (T item : toAdd) {
            T t = original.get(item);
            if (t == null) {
                original.intern(item);
            }
        }
    }

    private static <T> void addNewElements(@Nonnull Collection<T> original, @Nonnull Collection<T> toAdd) {
        for (T item : toAdd) {
            if (!original.contains(item)) {
                original.add(item);
            }
        }
    }

    @Nonnull
    private DetailedLogData loadSomeCommitsOnTaggedBranches(
        @Nonnull VirtualFile root,
        int commitCount,
        @Nonnull Collection<String> unmatchedTags
    ) throws VcsException {
        StopWatch sw = StopWatch.start("loading commits on tagged branch in " + root.getName());
        List<String> params = new ArrayList<>();
        params.add("--max-count=" + commitCount);
        params.addAll(unmatchedTags);
        sw.report();
        return GitHistoryUtils.loadMetadata(myProject, root, ArrayUtil.toStringArray(params));
    }

    @Override
    @Nonnull
    public LogData readAllHashes(@Nonnull VirtualFile root, @Nonnull Consumer<TimedVcsCommit> commitConsumer) throws VcsException {
        if (!isRepositoryReady(root)) {
            return LogDataImpl.empty();
        }

        List<String> parameters = new ArrayList<>(GitHistoryUtils.LOG_ALL);
        parameters.add("--date-order");

        GitBekParentFixer parentFixer = GitBekParentFixer.prepare(root, this);
        Set<VcsUser> userRegistry = newHashSet();
        Set<VcsRef> refs = newHashSet();
        GitHistoryUtils.readCommits(
            myProject,
            root,
            parameters,
            userRegistry::add,
            refs::add,
            commit -> commitConsumer.accept(parentFixer.fixCommit(commit))
        );
        return new LogDataImpl(refs, userRegistry);
    }

    @Override
    public void readAllFullDetails(@Nonnull VirtualFile root, @Nonnull Consumer<VcsFullCommitDetails> commitConsumer) throws VcsException {
        if (!isRepositoryReady(root)) {
            return;
        }

        GitHistoryUtils.loadDetails(myProject, root, commitConsumer, ArrayUtil.toStringArray(GitHistoryUtils.LOG_ALL));
    }

    @Override
    public void readFullDetails(
        @Nonnull VirtualFile root,
        @Nonnull List<String> hashes,
        @Nonnull Consumer<VcsFullCommitDetails> commitConsumer
    ) throws VcsException {
        if (!isRepositoryReady(root)) {
            return;
        }

        VcsFileUtil.foreachChunk(hashes, 1, hashesChunk -> {
            String noWalk = GitVersionSpecialty.NO_WALK_UNSORTED.existsIn(myVcs.getVersion()) ? "--no-walk=unsorted" : "--no-walk";
            List<String> parameters = new ArrayList<>();
            parameters.add(noWalk);
            parameters.addAll(hashesChunk);
            GitHistoryUtils.loadDetails(myProject, root, commitConsumer, ArrayUtil.toStringArray(parameters));
        });
    }

    @Nonnull
    @Override
    public List<? extends VcsShortCommitDetails> readShortDetails(
        @Nonnull final VirtualFile root,
        @Nonnull List<String> hashes
    ) throws VcsException {
        //noinspection Convert2Lambda
        return VcsFileUtil.foreachChunk(hashes, new ThrowableFunction<List<String>, List<? extends VcsShortCommitDetails>, VcsException>() {
            @Nonnull
            @Override
            public List<? extends VcsShortCommitDetails> apply(@Nonnull List<String> hashes) throws VcsException {
                return GitHistoryUtils.readMiniDetails(myProject, root, hashes);
            }
        });
    }

    @Nonnull
    private Set<VcsRef> readBranches(@Nonnull GitRepository repository) {
        StopWatch sw = StopWatch.start("readBranches in " + repository.getRoot().getName());
        VirtualFile root = repository.getRoot();
        repository.update();
        GitBranchesCollection branches = repository.getBranches();
        Collection<GitLocalBranch> localBranches = branches.getLocalBranches();
        Collection<GitRemoteBranch> remoteBranches = branches.getRemoteBranches();
        Set<VcsRef> refs = new HashSet<>(localBranches.size() + remoteBranches.size());
        for (GitLocalBranch localBranch : localBranches) {
            Hash hash = branches.getHash(localBranch);
            assert hash != null;
            refs.add(myVcsObjectsFactory.createRef(hash, localBranch.getName(), GitRefManager.LOCAL_BRANCH, root));
        }
        for (GitRemoteBranch remoteBranch : remoteBranches) {
            Hash hash = branches.getHash(remoteBranch);
            assert hash != null;
            refs.add(myVcsObjectsFactory.createRef(hash, remoteBranch.getNameForLocalOperations(), GitRefManager.REMOTE_BRANCH, root));
        }
        String currentRevision = repository.getCurrentRevision();
        if (currentRevision != null) { // null => fresh repository
            refs.add(myVcsObjectsFactory.createRef(HashImpl.build(currentRevision), "HEAD", GitRefManager.HEAD, root));
        }
        sw.report();
        return refs;
    }

    @Nonnull
    @Override
    public VcsKey getSupportedVcs() {
        return GitVcs.getKey();
    }

    @Nonnull
    @Override
    public VcsLogRefManager getReferenceManager() {
        return myRefSorter;
    }

    @Nonnull
    @Override
    public Disposable subscribeToRootRefreshEvents(@Nonnull Collection<VirtualFile> roots, @Nonnull VcsLogRefresher refresher) {
        MessageBusConnection connection = myProject.getMessageBus().connect(myProject);
        connection.subscribe(
            GitRepositoryChangeListener.class,
            repository -> {
                VirtualFile root = repository.getRoot();
                if (roots.contains(root)) {
                    refresher.refresh(root);
                }
            }
        );
        return connection::disconnect;
    }

    @Nonnull
    @Override
    public List<TimedVcsCommit> getCommitsMatchingFilter(
        @Nonnull VirtualFile root,
        @Nonnull VcsLogFilterCollection filterCollection,
        int maxCount
    ) throws VcsException {
        if (!isRepositoryReady(root)) {
            return Collections.emptyList();
        }

        List<String> filterParameters = new ArrayList<>();

        VcsLogBranchFilter branchFilter = filterCollection.getBranchFilter();
        if (branchFilter != null) {
            GitRepository repository = getRepository(root);
            assert repository != null : "repository is null for root " + root + " but was previously reported as 'ready'";

            Collection<GitBranch> branches = ContainerUtil.newArrayList(ContainerUtil.concat(
                repository.getBranches().getLocalBranches(),
                repository.getBranches().getRemoteBranches()
            ));
            Collection<String> branchNames = GitBranchUtil.convertBranchesToNames(branches);
            Collection<String> predefinedNames = ContainerUtil.list("HEAD");

            boolean atLeastOneBranchExists = false;
            for (String branchName : ContainerUtil.concat(branchNames, predefinedNames)) {
                if (branchFilter.matches(branchName)) {
                    filterParameters.add(branchName);
                    atLeastOneBranchExists = true;
                }
            }

            if (!atLeastOneBranchExists) { // no such branches in this repository => filter matches nothing
                return Collections.emptyList();
            }
        }
        else {
            filterParameters.addAll(GitHistoryUtils.LOG_ALL);
        }

        if (filterCollection.getDateFilter() != null) {
            // assuming there is only one date filter, until filter expressions are defined
            VcsLogDateFilter filter = filterCollection.getDateFilter();
            if (filter.getAfter() != null) {
                filterParameters.add(prepareParameter("after", filter.getAfter().toString()));
            }
            if (filter.getBefore() != null) {
                filterParameters.add(prepareParameter("before", filter.getBefore().toString()));
            }
        }

        boolean regexp = true;
        boolean caseSensitive = false;
        if (filterCollection.getTextFilter() != null) {
            regexp = filterCollection.getTextFilter().isRegex();
            caseSensitive = filterCollection.getTextFilter().matchesCase();
            String textFilter = filterCollection.getTextFilter().getText();
            filterParameters.add(prepareParameter("grep", textFilter));
        }
        filterParameters.add(regexp ? "--extended-regexp" : "--fixed-strings");
        if (!caseSensitive) {
            filterParameters.add("--regexp-ignore-case"); // affects case sensitivity of any filter (except file filter)
        }

        if (filterCollection.getUserFilter() != null) {
            Collection<String> names = ContainerUtil.map(filterCollection.getUserFilter().getUsers(root), VcsUserUtil::toExactString);
            if (regexp) {
                List<String> authors = ContainerUtil.map(names, UserNameRegex.EXTENDED_INSTANCE);
                if (GitVersionSpecialty.LOG_AUTHOR_FILTER_SUPPORTS_VERTICAL_BAR.existsIn(myVcs.getVersion())) {
                    filterParameters.add(prepareParameter("author", StringUtil.join(authors, "|")));
                }
                else {
                    filterParameters.addAll(authors.stream().map(a -> prepareParameter("author", a)).collect(Collectors.toList()));
                }
            }
            else {
                filterParameters.addAll(ContainerUtil.map(names, a -> prepareParameter("author", StringUtil.escapeBackSlashes(a))));
            }
        }

        if (maxCount > 0) {
            filterParameters.add(prepareParameter("max-count", String.valueOf(maxCount)));
        }

        // note: structure filter must be the last parameter, because it uses "--" which separates parameters from paths
        if (filterCollection.getStructureFilter() != null) {
            Collection<FilePath> files = filterCollection.getStructureFilter().getFiles();
            if (!files.isEmpty()) {
                filterParameters.add("--full-history");
                filterParameters.add("--simplify-merges");
                filterParameters.add("--");
                for (FilePath file : files) {
                    filterParameters.add(file.getPath());
                }
            }
        }

        List<TimedVcsCommit> commits = new ArrayList<>();
        GitHistoryUtils.readCommits(
            myProject,
            root,
            filterParameters,
            vcsUser -> {
            },
            vcsRef -> {
            },
            commits::add
        );
        return commits;
    }

    @Nullable
    @Override
    public VcsUser getCurrentUser(@Nonnull VirtualFile root) throws VcsException {
        return myUserRegistry.getOrReadUser(root);
    }

    @Nonnull
    @Override
    public Collection<String> getContainingBranches(@Nonnull VirtualFile root, @Nonnull Hash commitHash) throws VcsException {
        return GitBranchUtil.getBranches(myProject, root, true, true, commitHash.asString());
    }

    @Nullable
    @Override
    public String getCurrentBranch(@Nonnull VirtualFile root) {
        GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
        if (repository == null) {
            return null;
        }
        String currentBranchName = repository.getCurrentBranchName();
        if (currentBranchName == null && repository.getCurrentRevision() != null) {
            return "HEAD";
        }
        return currentBranchName;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T> T getPropertyValue(VcsLogProperties.VcsLogProperty<T> property) {
        if (property == VcsLogProperties.LIGHTWEIGHT_BRANCHES) {
            return (T) Boolean.TRUE;
        }
        else if (property == VcsLogProperties.SUPPORTS_INDEXING) {
            return (T) Boolean.TRUE;
        }
        return null;
    }

    private static String prepareParameter(String paramName, String value) {
        return "--" + paramName + "=" + value; // no value quoting needed, because the parameter itself will be quoted by GeneralCommandLine
    }

    @Nullable
    private GitRepository getRepository(@Nonnull VirtualFile root) {
        return myRepositoryManager.getRepositoryForRoot(root);
    }

    private boolean isRepositoryReady(@Nonnull VirtualFile root) {
        GitRepository repository = getRepository(root);
        if (repository == null) {
            LOG.error("Repository not found for root " + root);
            return false;
        }
        else if (repository.isFresh()) {
            LOG.info("Fresh repository: " + root);
            return false;
        }
        return true;
    }

    @Nonnull
    private static <T> Set<T> newHashSet() {
        return new HashSet<>();
    }

    @Nonnull
    private static <T> Set<T> newHashSet(@Nonnull Collection<T> initialCollection) {
        return new HashSet<>(initialCollection);
    }
}