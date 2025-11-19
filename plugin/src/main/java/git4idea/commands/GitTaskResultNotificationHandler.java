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
package git4idea.commands;

import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.versionControlSystem.VcsNotifier;
import jakarta.annotation.Nonnull;

public class GitTaskResultNotificationHandler extends GitTaskResultHandlerAdapter {
    @Nonnull
    private final Project myProject;
    @Nonnull
    private final NotificationService myNotificationService;
    @Nonnull
    private final LocalizeValue mySuccessMessage;
    @Nonnull
    private final LocalizeValue myCancelMessage;
    @Nonnull
    private final LocalizeValue myErrorMessage;

    public GitTaskResultNotificationHandler(
        @Nonnull Project project,
        @Nonnull LocalizeValue successMessage,
        @Nonnull LocalizeValue cancelMessage,
        @Nonnull LocalizeValue errorMessage
    ) {
        myProject = project;
        myNotificationService = NotificationService.getInstance();
        mySuccessMessage = successMessage;
        myCancelMessage = cancelMessage;
        myErrorMessage = errorMessage;
    }

    @Override
    protected void onSuccess() {
        myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
            .content(mySuccessMessage)
            .notify(myProject);
    }

    @Override
    protected void onCancel() {
        myNotificationService.newInfo(VcsNotifier.NOTIFICATION_GROUP_ID)
            .content(myCancelMessage)
            .notify(myProject);
    }

    @Override
    protected void onFailure() {
        myNotificationService.newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .content(myErrorMessage)
            .notify(myProject);
    }
}
