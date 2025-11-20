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
package git4idea;

import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * @author Kirill Likhodedov
 */
public class GitLocalBranch extends GitBranch {
    public GitLocalBranch(@Nonnull String name) {
        super(name);
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public String toString() {
        return super.toString();
    }

    @Nullable
    public GitRemoteBranch findTrackedBranch(@Nonnull GitRepository repository) {
        for (GitBranchTrackInfo info : repository.getBranchTrackInfos()) {
            if (info.localBranch().equals(this)) {
                return info.remoteBranch();
            }
        }
        return null;
    }
}
