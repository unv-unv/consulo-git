/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package git4idea.checkin;

import consulo.localize.LocalizeValue;
import consulo.versionControlSystem.change.CommitExecutor;
import consulo.versionControlSystem.change.CommitSession;
import jakarta.annotation.Nonnull;

/**
 * @author yole
 */
public class GitCommitAndPushExecutor implements CommitExecutor {
    private final GitCheckinEnvironment myCheckinEnvironment;

    public GitCommitAndPushExecutor(GitCheckinEnvironment checkinEnvironment) {
        myCheckinEnvironment = checkinEnvironment;
    }

    @Nonnull
    @Override
    public LocalizeValue getActionText() {
        return LocalizeValue.localizeTODO("Commit and &Push...");
    }

    @Nonnull
    @Override
    public CommitSession createCommitSession() {
        myCheckinEnvironment.setNextCommitIsPushed(true);
        return CommitSession.VCS_COMMIT;
    }
}
