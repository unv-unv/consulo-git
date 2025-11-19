/*
` * Copyright 2000-2015 JetBrains s.r.o.
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
package git4idea.branch;

import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.dataholder.Key;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.log.*;
import consulo.versionControlSystem.log.base.HashImpl;
import consulo.versionControlSystem.log.util.VcsLogUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitBranch;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerAdapter;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DeepComparator implements VcsLogDeepComparator, Disposable {
    private static final Logger LOG = Logger.getInstance(DeepComparator.class);

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;
    @Nonnull
    private final VcsLogUi myUi;

    @Nullable
    private MyTask myTask;
    @Nullable
    private Set<CommitId> myNonPickedCommits;

    public DeepComparator(
        @Nonnull Project project,
        @Nonnull GitRepositoryManager manager,
        @Nonnull VcsLogUi ui,
        @Nonnull Disposable parent
    ) {
        myProject = project;
        myRepositoryManager = manager;
        myUi = ui;
        Disposer.register(parent, this);
    }

    @Override
    @RequiredUIAccess
    public void highlightInBackground(@Nonnull String branchToCompare, @Nonnull VcsLogDataProvider dataProvider) {
        if (myTask != null) {
            LOG.error("Shouldn't be possible");
            return;
        }

        Map<GitRepository, GitBranch> repositories = getRepositories(myUi.getDataPack().getLogProviders(), branchToCompare);
        if (repositories.isEmpty()) {
            removeHighlighting();
            return;
        }

        myTask = new MyTask(myProject, repositories, dataProvider, branchToCompare);
        myTask.queue();
    }

    @Nonnull
    private Map<GitRepository, GitBranch> getRepositories(
        @Nonnull Map<VirtualFile, VcsLogProvider> providers,
        @Nonnull String branchToCompare
    ) {
        Map<GitRepository, GitBranch> repos = new HashMap<>();
        for (VirtualFile root : providers.keySet()) {
            GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
            if (repository == null || repository.getCurrentBranch() == null
                || repository.getBranches().findBranchByName(branchToCompare) == null) {
                continue;
            }
            repos.put(repository, repository.getCurrentBranch());
        }
        return repos;
    }

    @Override
    @RequiredUIAccess
    public void stopAndUnhighlight() {
        stopTask();
        removeHighlighting();
    }

    private void stopTask() {
        if (myTask != null) {
            myTask.cancel();
            myTask = null;
        }
    }

    @RequiredUIAccess
    private void removeHighlighting() {
        myProject.getApplication().assertIsDispatchThread();
        myNonPickedCommits = null;
    }

    @Override
    @RequiredUIAccess
    public void dispose() {
        stopAndUnhighlight();
    }

    @Override
    public boolean hasHighlightingOrInProgress() {
        return myTask != null;
    }

    public static DeepComparator getInstance(@Nonnull Project project, @Nonnull VcsLogUi logUi) {
        return project.getInstance(DeepComparatorHolder.class).getInstance(logUi);
    }

    @Nonnull
    @Override
    public VcsLogHighlighter.VcsCommitStyle getStyle(@Nonnull VcsShortCommitDetails commitDetails, boolean isSelected) {
        if (myNonPickedCommits == null) {
            return VcsCommitStyle.DEFAULT;
        }
        return VcsCommitStyleFactory.foreground(
            !myNonPickedCommits.contains(new CommitId(commitDetails.getId(), commitDetails.getRoot())) ? COMMIT_FOREGROUND : null
        );
    }

    @Override
    @RequiredUIAccess
    public void update(@Nonnull VcsLogDataPack dataPack, boolean refreshHappened) {
        if (myTask == null) { // no task in progress => not interested in refresh events
            return;
        }

        if (refreshHappened) {
            // collect data
            String comparedBranch = myTask.myComparedBranch;
            Map<GitRepository, GitBranch> repositoriesWithCurrentBranches = myTask.myRepositoriesWithCurrentBranches;
            VcsLogDataProvider provider = myTask.myProvider;

            stopTask();

            // highlight again
            Map<GitRepository, GitBranch> repositories = getRepositories(dataPack.getLogProviders(), comparedBranch);
            if (repositories.equals(repositoriesWithCurrentBranches)) { // but not if current branch changed
                highlightInBackground(comparedBranch, provider);
            }
        }
        else {
            VcsLogBranchFilter branchFilter = dataPack.getFilters().getBranchFilter();
            if (branchFilter == null || !myTask.myComparedBranch.equals(VcsLogUtil.getSingleFilteredBranch(
                branchFilter,
                dataPack.getRefs()
            ))) {
                stopAndUnhighlight();
            }
        }
    }

    public static class Factory implements VcsLogHighlighterFactory {
        @Nonnull
        private static final String ID = "CHERRY_PICKED_COMMITS";

        @Nonnull
        @Override
        public VcsLogHighlighter createHighlighter(@Nonnull VcsLogData logDataManager, @Nonnull VcsLogUi logUi) {
            return getInstance(logDataManager.getProject(), logUi);
        }

        @Nonnull
        @Override
        public String getId() {
            return ID;
        }

        @Nonnull
        @Override
        public String getTitle() {
            return "Cherry Picked Commits";
        }

        @Override
        public boolean showMenuItem() {
            return false;
        }
    }

    private class MyTask extends Task.Backgroundable {
        @Nonnull
        private final Project myProject;
        @Nonnull
        private final Map<GitRepository, GitBranch> myRepositoriesWithCurrentBranches;
        @Nonnull
        private final VcsLogDataProvider myProvider;
        @Nonnull
        private final String myComparedBranch;

        @Nonnull
        private final Set<CommitId> myCollectedNonPickedCommits = new HashSet<>();
        @Nullable
        private VcsException myException;
        private boolean myCancelled;

        public MyTask(
            @Nonnull Project project,
            @Nonnull Map<GitRepository, GitBranch> repositoriesWithCurrentBranches,
            @Nonnull VcsLogDataProvider dataProvider,
            @Nonnull String branchToCompare
        ) {
            super(project, "Comparing Branches...");
            myProject = project;
            myRepositoriesWithCurrentBranches = repositoriesWithCurrentBranches;
            myProvider = dataProvider;
            myComparedBranch = branchToCompare;
        }

        @Override
        public void run(@Nonnull ProgressIndicator indicator) {
            try {
                for (Map.Entry<GitRepository, GitBranch> entry : myRepositoriesWithCurrentBranches.entrySet()) {
                    GitRepository repo = entry.getKey();
                    GitBranch currentBranch = entry.getValue();
                    myCollectedNonPickedCommits.addAll(getNonPickedCommitsFromGit(
                        myProject,
                        repo.getRoot(),
                        currentBranch.getName(),
                        myComparedBranch
                    ));
                }
            }
            catch (VcsException e) {
                LOG.warn(e);
                myException = e;
            }
        }

        @Override
        @RequiredUIAccess
        public void onSuccess() {
            if (myCancelled) {
                return;
            }

            removeHighlighting();

            if (myException != null) {
                NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                    .title(LocalizeValue.localizeTODO("Couldn't compare with branch " + myComparedBranch))
                    .content(LocalizeValue.of(myException.getMessage()))
                    .notify(myProject);
                return;
            }
            myNonPickedCommits = myCollectedNonPickedCommits;
        }

        public void cancel() {
            myCancelled = true;
        }

        @Nonnull
        private Set<CommitId> getNonPickedCommitsFromGit(
            @Nonnull Project project,
            @Nonnull final VirtualFile root,
            @Nonnull String currentBranch,
            @Nonnull String comparedBranch
        ) throws VcsException {
            GitLineHandler handler = new GitLineHandler(project, root, GitCommand.CHERRY);
            handler.addParameters(currentBranch, comparedBranch); // upstream - current branch; head - compared branch

            final Set<CommitId> pickedCommits = new HashSet<>();
            handler.addLineListener(new GitLineHandlerAdapter() {
                @Override
                public void onLineAvailable(String line, Key outputType) {
                    // + 645caac042ff7fb1a5e3f7d348f00e9ceea5c317
                    // - c3b9b90f6c26affd7e597ebf65db96de8f7e5860
                    if (line.startsWith("+")) {
                        try {
                            line = line.substring(2).trim();
                            int firstSpace = line.indexOf(' ');
                            if (firstSpace > 0) {
                                line = line.substring(0, firstSpace); // safety-check: take just the first word for sure
                            }
                            Hash hash = HashImpl.build(line);
                            pickedCommits.add(new CommitId(hash, root));
                        }
                        catch (Exception e) {
                            LOG.error("Couldn't parse line [" + line + "]");
                        }
                    }
                }
            });
            handler.runInCurrentThread(null);
            return pickedCommits;
        }
    }
}