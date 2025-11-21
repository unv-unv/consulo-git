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
package git4idea.log;

import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.log.*;
import consulo.versionControlSystem.log.graph.GraphCommit;
import consulo.versionControlSystem.log.util.BekUtil;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Set;

class GitBekParentFixer {
    @Nonnull
    private static final String MAGIC_TEXT = "Merge remote";
    @Nonnull
    private static final VcsLogFilterCollection MAGIC_FILTER = createVcsLogFilterCollection();

    @Nonnull
    private final Set<Hash> myWrongCommits;

    private GitBekParentFixer(@Nonnull Set<Hash> wrongCommits) {
        myWrongCommits = wrongCommits;
    }

    @Nonnull
    static GitBekParentFixer prepare(@Nonnull VirtualFile root, @Nonnull GitLogProvider provider) throws VcsException {
        if (!BekUtil.isBekEnabled()) {
            return new GitBekParentFixer(Collections.<Hash>emptySet());
        }
        return new GitBekParentFixer(getWrongCommits(provider, root));
    }

    @Nonnull
    TimedVcsCommit fixCommit(@Nonnull TimedVcsCommit commit) {
        if (!myWrongCommits.contains(commit.getId())) {
            return commit;
        }
        return reverseParents(commit);
    }

    @Nonnull
    private static Set<Hash> getWrongCommits(@Nonnull GitLogProvider provider, @Nonnull VirtualFile root) throws VcsException {
        List<TimedVcsCommit> commitsMatchingFilter = provider.getCommitsMatchingFilter(root, MAGIC_FILTER, -1);
        return ContainerUtil.map2Set(commitsMatchingFilter, GraphCommit::getId);
    }

    @Nonnull
    private static TimedVcsCommit reverseParents(@Nonnull final TimedVcsCommit commit) {
        return new TimedVcsCommit() {
            @Override
            public long getTimestamp() {
                return commit.getTimestamp();
            }

            @Nonnull
            @Override
            public Hash getId() {
                return commit.getId();
            }

            @Nonnull
            @Override
            public List<Hash> getParents() {
                return ContainerUtil.reverse(commit.getParents());
            }
        };
    }

    private static VcsLogFilterCollection createVcsLogFilterCollection() {
        final VcsLogTextFilter textFilter = new VcsLogTextFilter() {
            @Override
            public boolean matchesCase() {
                return false;
            }

            @Override
            public boolean isRegex() {
                return false;
            }

            @Nonnull
            @Override
            public String getText() {
                return MAGIC_TEXT;
            }

            @Override
            public boolean matches(@Nonnull VcsCommitMetadata details) {
                return details.getFullMessage().contains(MAGIC_TEXT);
            }
        };

        return new VcsLogFilterCollection() {
            @Nullable
            @Override
            public VcsLogBranchFilter getBranchFilter() {
                return null;
            }

            @Nullable
            @Override
            public VcsLogUserFilter getUserFilter() {
                return null;
            }

            @Nullable
            @Override
            public VcsLogDateFilter getDateFilter() {
                return null;
            }

            @Nonnull
            @Override
            public VcsLogTextFilter getTextFilter() {
                return textFilter;
            }

            @Nullable
            @Override
            public VcsLogHashFilter getHashFilter() {
                return null;
            }

            @Nullable
            @Override
            public VcsLogStructureFilter getStructureFilter() {
                return null;
            }

            @Nullable
            @Override
            public VcsLogRootFilter getRootFilter() {
                return null;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Nonnull
            @Override
            public List<VcsLogDetailsFilter> getDetailsFilters() {
                return Collections.<VcsLogDetailsFilter>singletonList(textFilter);
            }
        };
    }
}
