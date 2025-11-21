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

import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.distributed.push.PushSource;
import git4idea.GitLocalBranch;
import jakarta.annotation.Nonnull;

abstract class GitPushSource implements PushSource {
    @Nonnull
    static GitPushSource create(@Nonnull GitLocalBranch branch) {
        return new OnBranch(branch);
    }

    @Nonnull
    static GitPushSource create(@Nonnull String revision) {
        return new DetachedHead(revision);
    }

    @Nonnull
    abstract GitLocalBranch getBranch();

    private static class OnBranch extends GitPushSource {
        @Nonnull
        private final GitLocalBranch myBranch;

        private OnBranch(@Nonnull GitLocalBranch branch) {
            myBranch = branch;
        }

        @Nonnull
        @Override
        public String getPresentation() {
            return myBranch.getName();
        }

        @Nonnull
        @Override
        GitLocalBranch getBranch() {
            return myBranch;
        }
    }

    private static class DetachedHead extends GitPushSource {
        @Nonnull
        private final String myRevision;

        public DetachedHead(@Nonnull String revision) {
            myRevision = revision;
        }

        @Nonnull
        @Override
        public String getPresentation() {
            return DvcsUtil.getShortHash(myRevision);
        }

        @Nonnull
        @Override
        GitLocalBranch getBranch() {
            throw new IllegalStateException("Push is not allowed from detached HEAD");
        }
    }
}
