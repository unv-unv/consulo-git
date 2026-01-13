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
package git4idea.reset;

import consulo.application.AccessToken;
import consulo.application.Application;
import consulo.application.progress.ProgressIndicator;
import consulo.document.FileDocumentManager;
import consulo.ide.ServiceManager;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.collection.MultiMap;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ref.SimpleReference;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.VcsDirtyScopeManager;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.log.Hash;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitUtil;
import git4idea.branch.GitBranchUiHandlerImpl;
import git4idea.branch.GitSmartOperationDialog;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLocalChangesWouldBeOverwrittenDetector;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import git4idea.util.GitPreservingProcess;
import jakarta.annotation.Nonnull;

import java.util.*;

import static git4idea.commands.GitLocalChangesWouldBeOverwrittenDetector.Operation.RESET;

public class GitResetOperation {
    @Nonnull
    private final Project myProject;
    @Nonnull
    private final Map<GitRepository, Hash> myCommits;
    @Nonnull
    private final GitResetMode myMode;
    @Nonnull
    private final ProgressIndicator myIndicator;
    @Nonnull
    private final Git myGit;
    @Nonnull
    protected final NotificationService myNotificationService;
    @Nonnull
    private final GitBranchUiHandlerImpl myUiHandler;

    public GitResetOperation(
        @Nonnull Project project,
        @Nonnull Map<GitRepository, Hash> targetCommits,
        @Nonnull GitResetMode mode,
        @Nonnull ProgressIndicator indicator
    ) {
        myProject = project;
        myCommits = targetCommits;
        myMode = mode;
        myIndicator = indicator;
        myGit = ServiceManager.getService(Git.class);
        myNotificationService = NotificationService.getInstance();
        myUiHandler = new GitBranchUiHandlerImpl(myProject, myGit, indicator);
    }

    @RequiredUIAccess
    public void execute() {
        saveAllDocuments();
        Map<GitRepository, GitCommandResult> results = new HashMap<>();
        try (AccessToken ignored = DvcsUtil.workingTreeChangeStarted(myProject, "Git Reset")) {
            for (Map.Entry<GitRepository, Hash> entry : myCommits.entrySet()) {
                GitRepository repository = entry.getKey();
                VirtualFile root = repository.getRoot();
                String target = entry.getValue().asString();
                GitLocalChangesWouldBeOverwrittenDetector detector = new GitLocalChangesWouldBeOverwrittenDetector(root, RESET);

                GitCommandResult result = myGit.reset(repository, myMode, target, detector);
                if (!result.success() && detector.wasMessageDetected()) {
                    GitCommandResult smartResult = proposeSmartReset(detector, repository, target);
                    if (smartResult != null) {
                        result = smartResult;
                    }
                }
                results.put(repository, result);
                repository.update();
                VirtualFileUtil.markDirtyAndRefresh(false, true, false, root);
                VcsDirtyScopeManager.getInstance(myProject).dirDirtyRecursively(root);
            }
        }
        notifyResult(results);
    }

    private GitCommandResult proposeSmartReset(
        @Nonnull GitLocalChangesWouldBeOverwrittenDetector detector,
        @Nonnull GitRepository repository,
        @Nonnull String target
    ) {
        Collection<String> absolutePaths = GitUtil.toAbsolute(repository.getRoot(), detector.getRelativeFilePaths());
        List<Change> affectedChanges = GitUtil.findLocalChangesForPaths(myProject, repository.getRoot(), absolutePaths, false);
        int choice = myUiHandler.showSmartOperationDialog(myProject, affectedChanges, absolutePaths, "reset", "&Hard Reset");
        if (choice == GitSmartOperationDialog.SMART_EXIT_CODE) {
            SimpleReference<GitCommandResult> result = SimpleReference.create();
            new GitPreservingProcess(
                myProject,
                myGit,
                Collections.singleton(repository.getRoot()),
                "reset",
                target,
                GitVcsSettings.UpdateChangesPolicy.STASH,
                myIndicator,
                () -> result.set(myGit.reset(repository, myMode, target))
            ).execute();
            return result.get();
        }
        if (choice == GitSmartOperationDialog.FORCE_EXIT_CODE) {
            return myGit.reset(repository, GitResetMode.HARD, target);
        }
        return null;
    }

    private void notifyResult(@Nonnull Map<GitRepository, GitCommandResult> results) {
        Map<GitRepository, GitCommandResult> successes = new HashMap<>();
        Map<GitRepository, GitCommandResult> errors = new HashMap<>();
        for (Map.Entry<GitRepository, GitCommandResult> entry : results.entrySet()) {
            GitCommandResult result = entry.getValue();
            GitRepository repository = entry.getKey();
            if (result.success()) {
                successes.put(repository, result);
            }
            else {
                errors.put(repository, result);
            }
        }

        if (errors.isEmpty()) {
            myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                .content(LocalizeValue.localizeTODO("Reset successful"))
                .notify(myProject);
        }
        else if (!successes.isEmpty()) {
            myNotificationService.newWarn(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Reset partially failed"))
                .content(LocalizeValue.localizeTODO(
                    "Reset was successful for " + joinRepos(successes.keySet()) + "<br/>" +
                        "but failed for " + joinRepos(errors.keySet()) + ": <br/>" +
                        formErrorReport(errors)
                ))
                .notify(myProject);
        }
        else {
            myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Reset Failed"))
                .content(formErrorReport(errors))
                .notify(myProject);
        }
    }

    @Nonnull
    private static LocalizeValue formErrorReport(@Nonnull Map<GitRepository, GitCommandResult> errorResults) {
        MultiMap<LocalizeValue, GitRepository> grouped = groupByResult(errorResults);
        if (grouped.size() == 1) {
            return grouped.keySet().iterator().next().map(s -> "<code>" + s + "</code>");
        }
        return LocalizeValue.localizeTODO(StringUtil.join(
            grouped.entrySet(),
            entry -> joinRepos(entry.getValue()) + ":<br/><code>" + entry.getKey() + "</code>",
            "<br/>"
        ));
    }

    // to avoid duplicate error reports if they are the same for different repositories
    @Nonnull
    private static MultiMap<LocalizeValue, GitRepository> groupByResult(@Nonnull Map<GitRepository, GitCommandResult> results) {
        MultiMap<LocalizeValue, GitRepository> grouped = MultiMap.create();
        for (Map.Entry<GitRepository, GitCommandResult> entry : results.entrySet()) {
            grouped.putValue(entry.getValue().getErrorOutputAsHtmlValue(), entry.getKey());
        }
        return grouped;
    }

    @Nonnull
    private static String joinRepos(@Nonnull Collection<GitRepository> repositories) {
        return StringUtil.join(DvcsUtil.sortRepositories(repositories), ", ");
    }

    @RequiredUIAccess
    private static void saveAllDocuments() {
        Application application = Application.get();
        application.invokeAndWait(
            () -> FileDocumentManager.getInstance().saveAllDocuments(),
            application.getDefaultModalityState()
        );
    }
}
