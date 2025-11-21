/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package git4idea.rebase;

import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.List;

class GitSuccessfulRebase extends GitRebaseStatus {
    private final SuccessType mySuccessType;

    private GitSuccessfulRebase(@Nonnull SuccessType successType, @Nonnull Collection<GitRebaseUtils.CommitInfo> skippedCommits) {
        super(Type.SUCCESS, skippedCommits);
        mySuccessType = successType;
    }

    @Nonnull
    public SuccessType getSuccessType() {
        return mySuccessType;
    }

    @Nonnull
    static GitSuccessfulRebase parseFromOutput(
        @Nonnull List<String> output,
        @Nonnull Collection<GitRebaseUtils.CommitInfo> skippedCommits
    ) {
        return new GitSuccessfulRebase(SuccessType.fromOutput(output), skippedCommits);
    }

    enum SuccessType {
        REBASED {
            @Nonnull
            @Override
            public String formatMessage(@Nullable String currentBranch, @Nonnull String baseBranch, boolean withCheckout) {
                if (withCheckout) {
                    return "Checked out" + mention(currentBranch) + " and rebased it on " + baseBranch;
                }
                else {
                    return "Rebased" + mention(currentBranch) + " on " + baseBranch;
                }
            }
        },
        UP_TO_DATE {
            @Nonnull
            @Override
            public String formatMessage(@Nullable String currentBranch, @Nonnull String baseBranch, boolean withCheckout) {
                String msg = currentBranch != null ? currentBranch + " is up-to-date" : "Up-to-date";
                msg += " with " + baseBranch;
                return msg;
            }
        },
        FAST_FORWARDED {
            @Nonnull
            @Override
            public String formatMessage(@Nullable String currentBranch, @Nonnull String baseBranch, boolean withCheckout) {
                if (withCheckout) {
                    return "Checked out" + mention(currentBranch) + " and fast-forwarded it to " + baseBranch;
                }
                else {
                    return "Fast-forwarded" + mention(currentBranch) + " to " + baseBranch;
                }
            }
        };

        @Nonnull
        private static String mention(@Nullable String currentBranch) {
            return currentBranch != null ? " " + currentBranch : "";
        }

        @Nonnull
        abstract String formatMessage(@Nullable String currentBranch, @Nonnull String baseBranch, boolean withCheckout);

        @Nonnull
        public static SuccessType fromOutput(@Nonnull List<String> output) {
            for (String line : output) {
                if (StringUtil.containsIgnoreCase(line, "Fast-forwarded")) {
                    return FAST_FORWARDED;
                }
                if (StringUtil.containsIgnoreCase(line, "is up to date")) {
                    return UP_TO_DATE;
                }
            }
            return REBASED;
        }
    }
}
