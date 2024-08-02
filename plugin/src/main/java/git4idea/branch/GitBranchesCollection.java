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
package git4idea.branch;

import consulo.util.collection.ContainerUtil;
import consulo.util.lang.function.Condition;
import consulo.versionControlSystem.log.Hash;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitReference;
import git4idea.GitRemoteBranch;
import git4idea.repo.GitRepositoryReader;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * <p>
 * Storage for local, remote and current branches.
 * The reason of creating this special collection is that
 * in the terms of performance, they are detected by {@link GitRepositoryReader} at once;
 * and also usually both sets of branches are needed by components, but are treated differently,
 * so it is more convenient to have them separated, but in a single container.
 * </p>
 *
 * @author Kirill Likhodedov
 */
public final class GitBranchesCollection {

  public static final GitBranchesCollection EMPTY =
    new GitBranchesCollection(Collections.<GitLocalBranch, Hash>emptyMap(), Collections.<GitRemoteBranch, Hash>emptyMap());

  @Nonnull
  private final Map<GitLocalBranch, Hash> myLocalBranches;
  @Nonnull
  private final Map<GitRemoteBranch, Hash> myRemoteBranches;

  public GitBranchesCollection(@Nonnull Map<GitLocalBranch, Hash> localBranches, @Nonnull Map<GitRemoteBranch, Hash> remoteBranches) {
    myRemoteBranches = remoteBranches;
    myLocalBranches = localBranches;
  }

  @Nonnull
  public Collection<GitLocalBranch> getLocalBranches() {
    return Collections.unmodifiableCollection(myLocalBranches.keySet());
  }

  @Nonnull
  public Collection<GitRemoteBranch> getRemoteBranches() {
    return Collections.unmodifiableCollection(myRemoteBranches.keySet());
  }

  @Nullable
  public Hash getHash(@Nonnull GitBranch branch) {
    if (branch instanceof GitLocalBranch) {
      return myLocalBranches.get(branch);
    }
    if (branch instanceof GitRemoteBranch) {
      return myRemoteBranches.get(branch);
    }
    return null;
  }

  @Nullable
  public GitLocalBranch findLocalBranch(@Nonnull String name) {
    return findByName(myLocalBranches.keySet(), name);
  }

  @Nullable
  public GitBranch findBranchByName(@Nonnull String name) {
    GitLocalBranch branch = findByName(myLocalBranches.keySet(), name);
    return branch != null ? branch : findByName(myRemoteBranches.keySet(), name);
  }

  @Nullable
  private static <T extends GitBranch> T findByName(Collection<T> branches, @Nonnull final String name) {
    return ContainerUtil.find(branches, new Condition<T>() {
      @Override
      public boolean value(T branch) {
        return GitReference.BRANCH_NAME_HASHING_STRATEGY.equals(name, branch.getName());
      }
    });
  }

}
