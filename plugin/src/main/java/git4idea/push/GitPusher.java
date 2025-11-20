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
package git4idea.push;

import consulo.project.Project;
import consulo.project.ui.notification.NotificationType;
import consulo.project.ui.notification.NotificationsManager;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.versionControlSystem.distributed.push.PushSpec;
import consulo.versionControlSystem.distributed.push.Pusher;
import consulo.versionControlSystem.distributed.push.VcsPushOptionValue;
import git4idea.GitUtil;
import git4idea.config.GitVcsSettings;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Map;

class GitPusher extends Pusher<GitRepository, GitPushSource, GitPushTarget> {
    @Nonnull
    private final Project myProject;
    @Nonnull
    private final GitVcsSettings mySettings;
    @Nonnull
    private final GitPushSupport myPushSupport;
    @Nonnull
    private final GitRepositoryManager myRepositoryManager;

    GitPusher(@Nonnull Project project, @Nonnull GitVcsSettings settings, @Nonnull GitPushSupport pushSupport) {
        myProject = project;
        mySettings = settings;
        myPushSupport = pushSupport;
        myRepositoryManager = GitUtil.getRepositoryManager(project);
    }

    @Override
    @RequiredUIAccess
    public void push(
        @Nonnull Map<GitRepository, PushSpec<GitPushSource, GitPushTarget>> pushSpecs,
        @Nullable VcsPushOptionValue optionValue,
        boolean force
    ) {
        expireExistingErrorsAndWarnings();
        GitPushTagMode pushTagMode;
        boolean skipHook;
        if (optionValue instanceof GitVcsPushOptionValue pushOptionValue) {
            pushTagMode = pushOptionValue.getPushTagMode();
            skipHook = pushOptionValue.isSkipHook();
        }
        else {
            pushTagMode = null;
            skipHook = false;
        }

        GitPushResult result = new GitPushOperation(myProject, myPushSupport, pushSpecs, pushTagMode, force, skipHook).execute();
        GitPushResultNotification notification = GitPushResultNotification.create(myProject, result, myRepositoryManager.moreThanOneRoot());
        notification.notify(myProject);
        mySettings.setPushTagMode(pushTagMode);
        rememberTargets(pushSpecs);
    }

    protected void expireExistingErrorsAndWarnings() {
        GitPushResultNotification[] existingNotifications =
            NotificationsManager.getNotificationsManager().getNotificationsOfType(GitPushResultNotification.class, myProject);
        for (GitPushResultNotification notification : existingNotifications) {
            if (notification.getType() != NotificationType.INFORMATION) {
                notification.expire();
            }
        }
    }

    private void rememberTargets(@Nonnull Map<GitRepository, PushSpec<GitPushSource, GitPushTarget>> pushSpecs) {
        for (Map.Entry<GitRepository, PushSpec<GitPushSource, GitPushTarget>> entry : pushSpecs.entrySet()) {
            GitRepository repository = entry.getKey();
            GitPushSource source = entry.getValue().getSource();
            GitPushTarget target = entry.getValue().getTarget();
            GitPushTarget defaultTarget = myPushSupport.getDefaultTarget(repository);
            if (defaultTarget == null || !target.getBranch().equals(defaultTarget.getBranch())) {
                mySettings.setPushTarget(
                    repository,
                    source.getBranch().getName(),
                    target.getBranch().getRemote().getName(),
                    target.getBranch().getNameForRemoteOperations()
                );
            }
        }
    }
}
