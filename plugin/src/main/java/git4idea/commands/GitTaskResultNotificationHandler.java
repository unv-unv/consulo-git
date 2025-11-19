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
import consulo.versionControlSystem.VcsNotifier;
import jakarta.annotation.Nonnull;

public class GitTaskResultNotificationHandler extends GitTaskResultHandlerAdapter {
    @Nonnull
    private final Project myProject;
    private final LocalizeValue mySuccessMessage;
    private final LocalizeValue myCancelMessage;
    private final LocalizeValue myErrorMessage;

    public GitTaskResultNotificationHandler(
        @Nonnull Project project,
        @Nonnull LocalizeValue successMessage,
        @Nonnull LocalizeValue cancelMessage,
        @Nonnull LocalizeValue errorMessage
    ) {
        myProject = project;
        mySuccessMessage = successMessage;
        myCancelMessage = cancelMessage;
        myErrorMessage = errorMessage;
    }

    @Override
    protected void onSuccess() {
        VcsNotifier.getInstance(myProject).notifySuccess(mySuccessMessage.get());
    }

    @Override
    protected void onCancel() {
        VcsNotifier.getInstance(myProject).notifySuccess(myCancelMessage.get());
    }

    @Override
    protected void onFailure() {
        VcsNotifier.getInstance(myProject).notifyError("", myErrorMessage.get());
    }
}
