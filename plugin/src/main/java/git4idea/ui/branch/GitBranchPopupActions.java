/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package git4idea.ui.branch;

import consulo.ide.ServiceManager;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.DumbAwareAction;
import consulo.ui.ex.awt.Messages;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.branch.BranchActionGroup;
import consulo.versionControlSystem.distributed.branch.BranchActionUtil;
import consulo.versionControlSystem.distributed.branch.NewBranchAction;
import consulo.versionControlSystem.distributed.branch.PopupElementWithAdditionalInfo;
import consulo.versionControlSystem.distributed.repository.Repository;
import git4idea.branch.GitBranchUtil;
import git4idea.branch.GitBrancher;
import git4idea.repo.GitRepository;
import git4idea.validators.GitNewBranchNameValidator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;

import static git4idea.GitStatisticsCollectorKt.reportUsage;
import static git4idea.branch.GitBranchType.LOCAL;
import static git4idea.branch.GitBranchType.REMOTE;
import static java.util.stream.Collectors.toList;

class GitBranchPopupActions {
    private final Project myProject;
    private final GitRepository myRepository;

    GitBranchPopupActions(Project project, GitRepository repository) {
        myProject = project;
        myRepository = repository;
    }

    ActionGroup createActions() {
        return createActions(null, "", false);
    }

    ActionGroup createActions(@Nullable ActionGroup toInsert, @Nonnull String repoInfo, boolean firstLevelGroup) {
        ActionGroup.Builder popupGroup = ActionGroup.newImmutableBuilder();
        List<GitRepository> repositoryList = List.of(myRepository);

        popupGroup.add(new GitNewBranchAction(myProject, repositoryList));
        popupGroup.add(new CheckoutRevisionActions(myProject, repositoryList));

        if (toInsert != null) {
            popupGroup.addAll(toInsert);
        }

        popupGroup.addSeparator(LocalizeValue.localizeTODO("Local Branches" + repoInfo));
        List<BranchActionGroup> localBranchActions = myRepository.getBranches()
            .getLocalBranches()
            .stream()
            .sorted()
            .filter(branch -> !branch.equals(myRepository.getCurrentBranch()))
            .map(branch -> new LocalBranchActions(myProject, repositoryList, branch.getName(), myRepository))
            .collect(toList());
        // if there are only a few local favorites -> show all;  for remotes it's better to show only favorites;
        BranchActionUtil.wrapWithMoreActionIfNeeded(
            myProject,
            popupGroup,
            ContainerUtil.sorted(localBranchActions, BranchActionUtil.FAVORITE_BRANCH_COMPARATOR),
            BranchActionUtil.getNumOfTopShownBranches(localBranchActions),
            firstLevelGroup ? GitBranchPopup.SHOW_ALL_LOCALS_KEY : null,
            firstLevelGroup
        );

        popupGroup.addSeparator(LocalizeValue.localizeTODO("Remote Branches" + repoInfo));
        List<BranchActionGroup> remoteBranchActions =
            myRepository.getBranches()
                .getRemoteBranches()
                .stream()
                .sorted()
                .map(remoteBranch -> new RemoteBranchActions(
                    myProject,
                    repositoryList,
                    remoteBranch.getName(),
                    myRepository
                ))
                .collect(toList());
        BranchActionUtil.wrapWithMoreActionIfNeeded(
            myProject,
            popupGroup,
            ContainerUtil.sorted(remoteBranchActions, BranchActionUtil.FAVORITE_BRANCH_COMPARATOR),
            BranchActionUtil.getNumOfFavorites(remoteBranchActions),
            firstLevelGroup ? GitBranchPopup.SHOW_ALL_REMOTES_KEY : null
        );
        return popupGroup.build();
    }

    public static class GitNewBranchAction extends NewBranchAction<GitRepository> {
        public GitNewBranchAction(@Nonnull Project project, @Nonnull List<GitRepository> repositories) {
            super(project, repositories);
        }

        @RequiredUIAccess
        @Override
        public void actionPerformed(@Nonnull AnActionEvent e) {
            final String name = GitBranchUtil.getNewBranchNameFromUser(myProject, myRepositories, "Create New Branch");
            if (name != null) {
                GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
                brancher.checkoutNewBranch(name, myRepositories);
                reportUsage("git.branch.create.new");
            }
        }
    }

    /**
     * Checkout manually entered tag or revision number.
     */
    public static class CheckoutRevisionActions extends DumbAwareAction {
        private final Project myProject;
        private final List<GitRepository> myRepositories;

        CheckoutRevisionActions(Project project, List<GitRepository> repositories) {
            super("Checkout Tag or Revision...");
            myProject = project;
            myRepositories = repositories;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            // TODO autocomplete branches, tags.
            // on type check ref validity, on OK check ref existence.
            String reference =
                Messages.showInputDialog(myProject, "Enter reference (branch, tag) name or commit hash:", "Checkout", null);
            if (reference != null) {
                GitBrancher brancher = myProject.getInstance(GitBrancher.class);
                brancher.checkout(reference, true, myRepositories, null);
                reportUsage("git.branch.checkout.revision");
            }
        }

        @Override
        @RequiredUIAccess
        public void update(@Nonnull AnActionEvent e) {
            boolean isFresh = ContainerUtil.and(myRepositories, Repository::isFresh);
            if (isFresh) {
                e.getPresentation().setEnabled(false);
                e.getPresentation().setDescription("Checkout is not possible before the first commit");
            }
        }
    }

    /**
     * Actions available for local branches.
     */
    static class LocalBranchActions extends BranchActionGroup implements PopupElementWithAdditionalInfo {
        private final Project myProject;
        private final List<GitRepository> myRepositories;
        private final String myBranchName;
        @Nonnull
        private final GitRepository mySelectedRepository;
        private final GitBranchManager myGitBranchManager;

        LocalBranchActions(
            @Nonnull Project project,
            @Nonnull List<GitRepository> repositories,
            @Nonnull String branchName,
            @Nonnull GitRepository selectedRepository
        ) {
            myProject = project;
            myRepositories = repositories;
            myBranchName = branchName;
            mySelectedRepository = selectedRepository;
            myGitBranchManager = project.getInstance(GitBranchManager.class);
            getTemplatePresentation().setDisabledMnemonic(true);
            getTemplatePresentation().setTextValue(LocalizeValue.of(calcBranchText()));
            setFavorite(myGitBranchManager.isFavorite(LOCAL, repositories.size() > 1 ? null : mySelectedRepository, myBranchName));
        }

        @Nonnull
        private String calcBranchText() {
            return myBranchName;
        }

        @Nonnull
        List<GitRepository> getRepositories() {
            return myRepositories;
        }

        @Nonnull
        public String getBranchName() {
            return myBranchName;
        }

        @Nonnull
        @Override
        public AnAction[] getChildren(@Nullable AnActionEvent e) {
            return new AnAction[]{
                new CheckoutAction(myProject, myRepositories, myBranchName),
                new CheckoutAsNewBranch(myProject, myRepositories, myBranchName),
                new CompareAction(myProject, myRepositories, myBranchName, mySelectedRepository),
                new RebaseAction(myProject, myRepositories, myBranchName),
                new CheckoutWithRebaseAction(myProject, myRepositories, myBranchName),
                new MergeAction(myProject, myRepositories, myBranchName, true),
                new RenameBranchAction(myProject, myRepositories, myBranchName),
                new DeleteAction(myProject, myRepositories, myBranchName)
            };
        }

        @Override
        @Nullable
        public String getInfoText() {
            return new GitMultiRootBranchConfig(myRepositories).getTrackedBranch(myBranchName);
        }

        @Override
        public void toggle() {
            super.toggle();
            myGitBranchManager.setFavorite(LOCAL, myRepositories.size() > 1 ? null : mySelectedRepository, myBranchName, isFavorite());
        }

        static class CheckoutAction extends DumbAwareAction {
            private final Project myProject;
            private final List<GitRepository> myRepositories;
            private final String myBranchName;

            CheckoutAction(@Nonnull Project project, @Nonnull List<GitRepository> repositories, @Nonnull String branchName) {
                super("Checkout");
                myProject = project;
                myRepositories = repositories;
                myBranchName = branchName;
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
                brancher.checkout(myBranchName, false, myRepositories, null);
                reportUsage("git.branch.checkout.local");
            }
        }

        private static class CheckoutAsNewBranch extends DumbAwareAction {
            private final Project myProject;
            private final List<GitRepository> myRepositories;
            private final String myBranchName;

            CheckoutAsNewBranch(@Nonnull Project project, @Nonnull List<GitRepository> repositories, @Nonnull String branchName) {
                super("Checkout as New Branch");
                myProject = project;
                myRepositories = repositories;
                myBranchName = branchName;
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                final String name = Messages.showInputDialog(
                    myProject,
                    "New branch name:",
                    "Checkout New Branch From " + myBranchName,
                    null,
                    "",
                    GitNewBranchNameValidator.newInstance(myRepositories)
                );
                if (name != null) {
                    GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
                    brancher.checkoutNewBranchStartingFrom(name, myBranchName, myRepositories, null);
                }
                reportUsage("git.checkout.as.new.branch");
            }
        }

        private static class RenameBranchAction extends DumbAwareAction {
            @Nonnull
            private final Project myProject;
            @Nonnull
            private final List<GitRepository> myRepositories;
            @Nonnull
            private final String myCurrentBranchName;

            public RenameBranchAction(
                @Nonnull Project project,
                @Nonnull List<GitRepository> repositories,
                @Nonnull String currentBranchName
            ) {
                super("Rename");
                myProject = project;
                myRepositories = repositories;
                myCurrentBranchName = currentBranchName;
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                String newName = Messages.showInputDialog(
                    myProject,
                    "New name for the branch '" + myCurrentBranchName + "':",
                    "Rename Branch " + myCurrentBranchName,
                    null,
                    myCurrentBranchName,
                    GitNewBranchNameValidator.newInstance(myRepositories)
                );
                if (newName != null) {
                    GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
                    brancher.renameBranch(myCurrentBranchName, newName, myRepositories);
                    reportUsage("git.branch.rename");
                }
            }
        }

        private static class DeleteAction extends DumbAwareAction {
            private final Project myProject;
            private final List<GitRepository> myRepositories;
            private final String myBranchName;

            DeleteAction(Project project, List<GitRepository> repositories, String branchName) {
                super("Delete");
                myProject = project;
                myRepositories = repositories;
                myBranchName = branchName;
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                GitBrancher brancher = myProject.getInstance(GitBrancher.class);
                brancher.deleteBranch(myBranchName, myRepositories);
                reportUsage("git.branch.delete.local");
            }
        }
    }

    /**
     * Actions available for remote branches
     */
    static class RemoteBranchActions extends BranchActionGroup {

        private final Project myProject;
        private final List<GitRepository> myRepositories;
        private final String myBranchName;
        @Nonnull
        private final GitRepository mySelectedRepository;
        @Nonnull
        private final GitBranchManager myGitBranchManager;

        RemoteBranchActions(
            @Nonnull Project project,
            @Nonnull List<GitRepository> repositories,
            @Nonnull String branchName,
            @Nonnull GitRepository selectedRepository
        ) {

            myProject = project;
            myRepositories = repositories;
            myBranchName = branchName;
            mySelectedRepository = selectedRepository;
            myGitBranchManager = ServiceManager.getService(project, GitBranchManager.class);
            getTemplatePresentation().setDisabledMnemonic(true);
            getTemplatePresentation().setTextValue(LocalizeValue.of(branchName));
            setFavorite(myGitBranchManager.isFavorite(REMOTE, repositories.size() > 1 ? null : mySelectedRepository, myBranchName));
        }

        @Override
        public void toggle() {
            super.toggle();
            myGitBranchManager.setFavorite(REMOTE, myRepositories.size() > 1 ? null : mySelectedRepository, myBranchName, isFavorite());
        }

        @Nonnull
        @Override
        public AnAction[] getChildren(@Nullable AnActionEvent e) {
            return new AnAction[]{
                new CheckoutRemoteBranchAction(myProject, myRepositories, myBranchName),
                new CompareAction(myProject, myRepositories, myBranchName, mySelectedRepository),
                new RebaseAction(myProject, myRepositories, myBranchName),
                new MergeAction(myProject, myRepositories, myBranchName, false),
                new RemoteDeleteAction(myProject, myRepositories, myBranchName)
            };
        }

        static class CheckoutRemoteBranchAction extends DumbAwareAction {
            private final Project myProject;
            private final List<GitRepository> myRepositories;
            private final String myRemoteBranchName;

            public CheckoutRemoteBranchAction(
                @Nonnull Project project,
                @Nonnull List<GitRepository> repositories,
                @Nonnull String remoteBranchName
            ) {
                super("Checkout as new local branch");
                myProject = project;
                myRepositories = repositories;
                myRemoteBranchName = remoteBranchName;
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                final String name = Messages.showInputDialog(
                    myProject,
                    "New branch name:",
                    "Checkout Remote Branch",
                    null,
                    guessBranchName(),
                    GitNewBranchNameValidator.newInstance(myRepositories)
                );
                if (name != null) {
                    GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
                    brancher.checkoutNewBranchStartingFrom(name, myRemoteBranchName, myRepositories, null);
                    reportUsage("git.branch.checkout.remote");
                }
            }

            private String guessBranchName() {
                // TODO: check if we already have a branch with that name;
                // TODO: check if that branch tracks this remote branch. Show different messages
                int slashPosition = myRemoteBranchName.indexOf("/");
                // if no slash is found (for example, in the case of git-svn remote branches), propose the whole name.
                return myRemoteBranchName.substring(slashPosition + 1);
            }
        }

        private static class RemoteDeleteAction extends DumbAwareAction {
            private final Project myProject;
            private final List<GitRepository> myRepositories;
            private final String myBranchName;

            RemoteDeleteAction(@Nonnull Project project, @Nonnull List<GitRepository> repositories, @Nonnull String branchName) {
                super("Delete");
                myProject = project;
                myRepositories = repositories;
                myBranchName = branchName;
            }

            @Override
            @RequiredUIAccess
            public void actionPerformed(@Nonnull AnActionEvent e) {
                GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
                brancher.deleteRemoteBranch(myBranchName, myRepositories);
                reportUsage("git.branch.delete.remote");
            }
        }
    }

    private static class CompareAction extends DumbAwareAction {
        private final Project myProject;
        private final List<GitRepository> myRepositories;
        private final String myBranchName;
        private final GitRepository mySelectedRepository;

        public CompareAction(
            @Nonnull Project project,
            @Nonnull List<GitRepository> repositories,
            @Nonnull String branchName,
            @Nonnull GitRepository selectedRepository
        ) {
            super("Compare");
            myProject = project;
            myRepositories = repositories;
            myBranchName = branchName;
            mySelectedRepository = selectedRepository;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
            brancher.compare(myBranchName, myRepositories, mySelectedRepository);
            reportUsage("git.branch.compare");
        }
    }

    private static class MergeAction extends DumbAwareAction {
        private final Project myProject;
        private final List<GitRepository> myRepositories;
        private final String myBranchName;
        private final boolean myLocalBranch;

        public MergeAction(
            @Nonnull Project project,
            @Nonnull List<GitRepository> repositories,
            @Nonnull String branchName,
            boolean localBranch
        ) {
            super("Merge");
            myProject = project;
            myRepositories = repositories;
            myBranchName = branchName;
            myLocalBranch = localBranch;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
            brancher.merge(myBranchName, deleteOnMerge(), myRepositories);
            reportUsage("git.branch.merge");
        }

        private GitBrancher.DeleteOnMergeOption deleteOnMerge() {
            if (myLocalBranch && !myBranchName.equals("master")) {
                return GitBrancher.DeleteOnMergeOption.PROPOSE;
            }
            return GitBrancher.DeleteOnMergeOption.NOTHING;
        }
    }

    private static class RebaseAction extends DumbAwareAction {
        private final Project myProject;
        private final List<GitRepository> myRepositories;
        private final String myBranchName;

        public RebaseAction(@Nonnull Project project, @Nonnull List<GitRepository> repositories, @Nonnull String branchName) {
            super("Rebase onto");
            myProject = project;
            myRepositories = repositories;
            myBranchName = branchName;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
            brancher.rebase(myRepositories, myBranchName);
            reportUsage("git.branch.rebase");
        }
    }

    private static class CheckoutWithRebaseAction extends DumbAwareAction {
        private final Project myProject;
        private final List<GitRepository> myRepositories;
        private final String myBranchName;

        public CheckoutWithRebaseAction(@Nonnull Project project, @Nonnull List<GitRepository> repositories, @Nonnull String branchName) {
            super(
                "Checkout with Rebase",
                "Checkout the given branch, and rebase it on current branch in one step, " +
                    "just like `git rebase HEAD " + branchName + "` would do.",
                null
            );
            myProject = project;
            myRepositories = repositories;
            myBranchName = branchName;
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            GitBrancher brancher = myProject.getInstance(GitBrancher.class);
            brancher.rebaseOnCurrent(myRepositories, myBranchName);
            reportUsage("git.branch.checkout.with.rebase");
        }
    }
}
