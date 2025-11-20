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
package git4idea.update;

import consulo.application.progress.ProgressIndicator;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.branch.GitBranchUtil;
import git4idea.commands.*;
import git4idea.config.GitVersionSpecialty;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitUIUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static git4idea.GitBranch.REFS_HEADS_PREFIX;
import static git4idea.GitBranch.REFS_REMOTES_PREFIX;

/**
 * @author Kirill Likhodedov
 */
public class GitFetcher {
    private static final Logger LOG = Logger.getInstance(GitFetcher.class);

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final NotificationService myNotificationService;
    private final GitRepositoryManager myRepositoryManager;
    private final ProgressIndicator myProgressIndicator;
    private final boolean myFetchAll;
    private final GitVcs myVcs;

    private final Collection<Exception> myErrors = new ArrayList<>();

    /**
     * @param fetchAll Pass {@code true} to fetch all remotes and all branches (like {@code git fetch} without parameters does).
     *                 Pass {@code false} to fetch only the tracked branch of the current branch.
     */
    public GitFetcher(@Nonnull Project project, @Nonnull ProgressIndicator progressIndicator, boolean fetchAll) {
        myProject = project;
        myNotificationService = NotificationService.getInstance();
        myProgressIndicator = progressIndicator;
        myFetchAll = fetchAll;
        myRepositoryManager = GitUtil.getRepositoryManager(myProject);
        myVcs = GitVcs.getInstance(project);
    }

    /**
     * Invokes 'git fetch'.
     *
     * @return true if fetch was successful, false in the case of error.
     */
    @RequiredUIAccess
    public GitFetchResult fetch(@Nonnull GitRepository repository) {
        // TODO need to have a fair compound result here
        GitFetchResult fetchResult = GitFetchResult.success();
        if (myFetchAll) {
            fetchResult = fetchAll(repository, fetchResult);
        }
        else {
            return fetchCurrentRemote(repository);
        }

        repository.update();
        return fetchResult;
    }

    @Nonnull
    @RequiredUIAccess
    public GitFetchResult fetch(@Nonnull VirtualFile root, @Nonnull String remoteName) {
        GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
        if (repository == null) {
            return logError("Repository can't be null for " + root, myRepositoryManager.toString());
        }
        GitRemote remote = GitUtil.findRemoteByName(repository, remoteName);
        if (remote == null) {
            return logError("Couldn't find remote with the name " + remoteName, null);
        }
        String url = remote.getFirstUrl();
        if (url == null) {
            return logError("URL is null for remote " + remote.getName(), null);
        }
        return fetchRemote(repository, remote, url);
    }

    private static GitFetchResult logError(@Nonnull String message, @Nullable String additionalInfo) {
        String addInfo = additionalInfo != null ? "\n" + additionalInfo : "";
        LOG.error(message + addInfo);
        return GitFetchResult.error(message);
    }

    @Nonnull
    @RequiredUIAccess
    private GitFetchResult fetchCurrentRemote(@Nonnull GitRepository repository) {
        FetchParams fetchParams = getFetchParams(repository);
        if (fetchParams.isError()) {
            return fetchParams.getError();
        }

        GitRemote remote = fetchParams.getRemote();
        String url = fetchParams.getUrl();
        return fetchRemote(repository, remote, url);
    }

    @Nonnull
    @RequiredUIAccess
    private GitFetchResult fetchRemote(@Nonnull GitRepository repository, @Nonnull GitRemote remote, @Nonnull String url) {
        return fetchNatively(repository.getRoot(), remote, url, null);
    }

    // leaving this unused method, because the wanted behavior can change again
    @Nonnull
    @RequiredUIAccess
    @SuppressWarnings("UnusedDeclaration")
    private GitFetchResult fetchCurrentBranch(@Nonnull GitRepository repository) {
        FetchParams fetchParams = getFetchParams(repository);
        if (fetchParams.isError()) {
            return fetchParams.getError();
        }

        GitRemote remote = fetchParams.getRemote();
        String remoteBranch = fetchParams.getRemoteBranch().getNameForRemoteOperations();
        String url = fetchParams.getUrl();
        return fetchNatively(repository.getRoot(), remote, url, remoteBranch);
    }

    @Nonnull
    private static FetchParams getFetchParams(@Nonnull GitRepository repository) {
        GitLocalBranch currentBranch = repository.getCurrentBranch();
        if (currentBranch == null) {
            // fetching current branch is called from Update Project and Push, where branch tracking is pre-checked
            String message = "Current branch can't be null here. \nRepository: " + repository;
            LOG.error(message);
            return new FetchParams(GitFetchResult.error(new Exception(message)));
        }
        GitBranchTrackInfo trackInfo = GitBranchUtil.getTrackInfoForBranch(repository, currentBranch);
        if (trackInfo == null) {
            String message = "Tracked info is null for branch " + currentBranch + "\n Repository: " + repository;
            LOG.error(message);
            return new FetchParams(GitFetchResult.error(new Exception(message)));
        }

        GitRemote remote = trackInfo.getRemote();
        String url = remote.getFirstUrl();
        if (url == null) {
            String message = "URL is null for remote " + remote.getName();
            LOG.error(message);
            return new FetchParams(GitFetchResult.error(new Exception(message)));
        }

        return new FetchParams(remote, trackInfo.remoteBranch(), url);
    }

    @Nonnull
    @RequiredUIAccess
    private GitFetchResult fetchAll(@Nonnull GitRepository repository, @Nonnull GitFetchResult fetchResult) {
        for (GitRemote remote : repository.getRemotes()) {
            String url = remote.getFirstUrl();
            if (url == null) {
                LOG.error("URL is null for remote " + remote.getName());
                continue;
            }

            GitFetchResult res = fetchNatively(repository.getRoot(), remote, url, null);
            res.addPruneInfo(fetchResult.getPrunedRefs());
            fetchResult = res;
            if (!fetchResult.isSuccess()) {
                break;
            }
        }
        return fetchResult;
    }

    @RequiredUIAccess
    private GitFetchResult fetchNatively(
        @Nonnull VirtualFile root,
        @Nonnull GitRemote remote,
        @Nonnull String url,
        @Nullable String branch
    ) {
        final GitLineHandlerPasswordRequestAware h = new GitLineHandlerPasswordRequestAware(myProject, root, GitCommand.FETCH);
        h.setUrl(url);
        h.setPuttyKey(remote.getPuttyKeyFile());
        h.addProgressParameter();
        if (GitVersionSpecialty.SUPPORTS_FETCH_PRUNE.existsIn(myVcs.getVersion())) {
            h.addParameters("--prune");
        }

        String remoteName = remote.getName();
        h.addParameters(remoteName);
        if (branch != null) {
            h.addParameters(getFetchSpecForBranch(branch, remoteName));
        }

        GitTask fetchTask = new GitTask(myProject, h, LocalizeValue.localizeTODO("Fetching " + remote.getFirstUrl()));
        fetchTask.setProgressIndicator(myProgressIndicator);
        fetchTask.setProgressAnalyzer(new GitStandardProgressAnalyzer());

        GitFetchPruneDetector pruneDetector = new GitFetchPruneDetector();
        h.addLineListener(pruneDetector);

        final AtomicReference<GitFetchResult> result = new AtomicReference<>();
        fetchTask.execute(true, false, new GitTaskResultHandlerAdapter() {
            @Override
            protected void onSuccess() {
                result.set(GitFetchResult.success());
            }

            @Override
            protected void onCancel() {
                LOG.info("Cancelled fetch.");
                result.set(GitFetchResult.cancel());
            }

            @Override
            protected void onFailure() {
                LOG.info("Error fetching: " + h.errors());
                if (!h.hadAuthRequest()) {
                    myErrors.addAll(h.errors());
                }
                else {
                    myErrors.add(new VcsException(LocalizeValue.localizeTODO("Authentication failed")));
                }
                result.set(GitFetchResult.error(myErrors));
            }
        });

        result.get().addPruneInfo(pruneDetector.getPrunedRefs());
        return result.get();
    }

    private static String getRidOfPrefixIfExists(String branch) {
        if (branch.startsWith(REFS_HEADS_PREFIX)) {
            return branch.substring(REFS_HEADS_PREFIX.length());
        }
        return branch;
    }

    @Nonnull
    public static String getFetchSpecForBranch(@Nonnull String branch, @Nonnull String remoteName) {
        branch = getRidOfPrefixIfExists(branch);
        return REFS_HEADS_PREFIX + branch + ":" + REFS_REMOTES_PREFIX + remoteName + "/" + branch;
    }

    @Nonnull
    public Collection<Exception> getErrors() {
        return myErrors;
    }

    public static void displayFetchResult(
        @Nonnull Project project,
        @Nonnull GitFetchResult result,
        @Nullable String errorNotificationTitle,
        @Nonnull Collection<? extends Exception> errors
    ) {
        if (result.isSuccess()) {
            NotificationService.getInstance().newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                .content(LocalizeValue.localizeTODO("Fetched successfully" + result.getAdditionalInfo()))
                .notify(project);
        }
        else if (result.isCancelled()) {
            NotificationService.getInstance().newWarn(VcsNotifier.STANDARD_NOTIFICATION)
                .content(LocalizeValue.localizeTODO("Fetch cancelled by user" + result.getAdditionalInfo()))
                .notify(project);
        }
        else if (result.isNotAuthorized()) {
            LocalizeValue title;
            LocalizeValue description;
            if (errorNotificationTitle != null) {
                title = LocalizeValue.localizeTODO(errorNotificationTitle);
                description = LocalizeValue.localizeTODO("Fetch failed: couldn't authorize" + result.getAdditionalInfo());
            }
            else {
                title = LocalizeValue.localizeTODO("Fetch failed");
                description = LocalizeValue.localizeTODO("Couldn't authorize" + result.getAdditionalInfo());
            }
            GitUIUtil.notifyMessage(project, title, description, true, null);
        }
        else {
            GitVcs instance = GitVcs.getInstance(project);
            if (instance != null && instance.getExecutableValidator().isExecutableValid()) {
                GitUIUtil.notifyMessage(project, LocalizeValue.localizeTODO("Fetch failed"), result.getAdditionalInfo(), true, errors);
            }
        }
    }

    /**
     * Fetches all specified roots.
     * Once a root has failed, stops and displays the notification.
     * If needed, displays the successful notification at the end.
     *
     * @param roots                  roots to fetch.
     * @param errorNotificationTitle if specified, this notification title will be used instead of the standard "Fetch failed".
     *                               Use this when fetch is a part of a compound process.
     * @param notifySuccess          if set to {@code true} successful notification will be displayed.
     * @return true if all fetches were successful, false if at least one fetch failed.
     */
    @RequiredUIAccess
    public boolean fetchRootsAndNotify(
        @Nonnull Collection<GitRepository> roots,
        @Nullable String errorNotificationTitle,
        boolean notifySuccess
    ) {
        Map<VirtualFile, String> additionalInfo = new HashMap<>();
        for (GitRepository repository : roots) {
            LOG.info("fetching " + repository);
            GitFetchResult result = fetch(repository);
            LocalizeValue ai = result.getAdditionalInfo();
            if (ai != LocalizeValue.empty()) {
                additionalInfo.put(repository.getRoot(), ai.get());
            }
            if (!result.isSuccess()) {
                Collection<Exception> errors = new ArrayList<>(getErrors());
                errors.addAll(result.getErrors());
                displayFetchResult(myProject, result, errorNotificationTitle, errors);
                return false;
            }
        }
        if (notifySuccess) {
            myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                .content(LocalizeValue.localizeTODO("Fetched successfully"))
                .notify(myProject);
        }

        String addInfo = makeAdditionalInfoByRoot(additionalInfo);
        if (!StringUtil.isEmptyOrSpaces(addInfo)) {
            myNotificationService.newInfo(VcsNotifier.STANDARD_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Fetch details"))
                .content(LocalizeValue.localizeTODO(addInfo))
                .notify(myProject);
        }

        return true;
    }

    @Nonnull
    private String makeAdditionalInfoByRoot(@Nonnull Map<VirtualFile, String> additionalInfo) {
        if (additionalInfo.isEmpty()) {
            return "";
        }
        StringBuilder info = new StringBuilder();
        if (myRepositoryManager.moreThanOneRoot()) {
            for (Map.Entry<VirtualFile, String> entry : additionalInfo.entrySet()) {
                info.append(entry.getValue())
                    .append(" in ")
                    .append(VcsUtil.getShortVcsRootName(myProject, entry.getKey()))
                    .append("<br/>");
            }
        }
        else {
            info.append(additionalInfo.values().iterator().next());
        }
        return info.toString();
    }

    private static class GitFetchPruneDetector extends GitLineHandlerAdapter {

        private static final Pattern PRUNE_PATTERN = Pattern.compile("\\s*x\\s*\\[deleted\\].*->\\s*(\\S*)");

        @Nonnull
        private final Collection<String> myPrunedRefs = new ArrayList<>();

        @Override
        public void onLineAvailable(String line, Key outputType) {
            //  x [deleted]         (none)     -> origin/frmari
            Matcher matcher = PRUNE_PATTERN.matcher(line);
            if (matcher.matches()) {
                myPrunedRefs.add(matcher.group(1));
            }
        }

        @Nonnull
        public Collection<String> getPrunedRefs() {
            return myPrunedRefs;
        }
    }

    private static class FetchParams {
        private GitRemote myRemote;
        private GitRemoteBranch myRemoteBranch;
        private GitFetchResult myError;
        private String myUrl;

        FetchParams(GitFetchResult error) {
            myError = error;
        }

        FetchParams(GitRemote remote, GitRemoteBranch remoteBranch, String url) {
            myRemote = remote;
            myRemoteBranch = remoteBranch;
            myUrl = url;
        }

        boolean isError() {
            return myError != null;
        }

        public GitFetchResult getError() {
            return myError;
        }

        public GitRemote getRemote() {
            return myRemote;
        }

        public GitRemoteBranch getRemoteBranch() {
            return myRemoteBranch;
        }

        public String getUrl() {
            return myUrl;
        }
    }
}
