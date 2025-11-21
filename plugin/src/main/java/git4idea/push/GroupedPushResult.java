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

import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;

import java.util.HashMap;
import java.util.Map;

class GroupedPushResult {
    @Nonnull
    final Map<GitRepository, GitPushRepoResult> successful;
    @Nonnull
    final Map<GitRepository, GitPushRepoResult> errors;
    @Nonnull
    final Map<GitRepository, GitPushRepoResult> rejected;
    @Nonnull
    final Map<GitRepository, GitPushRepoResult> customRejected;

    private GroupedPushResult(
        @Nonnull Map<GitRepository, GitPushRepoResult> successful,
        @Nonnull Map<GitRepository, GitPushRepoResult> errors,
        @Nonnull Map<GitRepository, GitPushRepoResult> rejected,
        @Nonnull Map<GitRepository, GitPushRepoResult> customRejected
    ) {
        this.successful = successful;
        this.errors = errors;
        this.rejected = rejected;
        this.customRejected = customRejected;
    }

    @Nonnull
    static GroupedPushResult group(@Nonnull Map<GitRepository, GitPushRepoResult> results) {
        Map<GitRepository, GitPushRepoResult> successful = new HashMap<>();
        Map<GitRepository, GitPushRepoResult> rejected = new HashMap<>();
        Map<GitRepository, GitPushRepoResult> customRejected = new HashMap<>();
        Map<GitRepository, GitPushRepoResult> errors = new HashMap<>();
        for (Map.Entry<GitRepository, GitPushRepoResult> entry : results.entrySet()) {
            GitRepository repository = entry.getKey();
            GitPushRepoResult result = entry.getValue();

            if (result.getType() == GitPushRepoResult.Type.REJECTED_NO_FF) {
                rejected.put(repository, result);
            }
            else if (result.getType() == GitPushRepoResult.Type.ERROR) {
                errors.put(repository, result);
            }
            else if (result.getType() == GitPushRepoResult.Type.REJECTED_OTHER) {
                customRejected.put(repository, result);
            }
            else {
                successful.put(repository, result);
            }
        }
        return new GroupedPushResult(successful, errors, rejected, customRejected);
    }
}
