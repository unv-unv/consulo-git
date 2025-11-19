/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.roots;

import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.ui.notification.NotificationService;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.root.VcsIntegrationEnabler;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;

import jakarta.annotation.Nonnull;

public class GitIntegrationEnabler extends VcsIntegrationEnabler<GitVcs> {
    @Nonnull
    private final Git myGit;

    private static final Logger LOG = Logger.getInstance(GitIntegrationEnabler.class);

    public GitIntegrationEnabler(@Nonnull GitVcs vcs, @Nonnull Git git) {
        super(vcs);
        myGit = git;
    }

    @Override
    protected boolean initOrNotifyError(@Nonnull VirtualFile projectDir) {
        NotificationService notificationService = NotificationService.getInstance();
        GitCommandResult result = myGit.init(myProject, projectDir);
        if (result.success()) {
            refreshVcsDir(projectDir, GitUtil.DOT_GIT);
            notificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
                .content(LocalizeValue.localizeTODO("Created Git repository in " + projectDir.getPresentableUrl()))
                .notify(myProject);
            return true;
        }
        else if (myVcs.getExecutableValidator().checkExecutableAndNotifyIfNeeded()) {
            notificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
                .title(LocalizeValue.localizeTODO("Couldn't git init " + projectDir.getPresentableUrl()))
                .content(result.getErrorOutputAsHtmlValue())
                .notify(myProject);
            LOG.info(result.getErrorOutputAsHtmlString());
        }
        return false;
    }
}
