/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package git4idea.repo;

import consulo.versionControlSystem.distributed.repository.Repository;
import consulo.versionControlSystem.log.Hash;
import consulo.util.collection.HashingStrategy;
import consulo.util.collection.Sets;
import git4idea.GitBranch;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.*;

public class GitRepoInfo
{

	@Nullable
	private final GitLocalBranch myCurrentBranch;
	@Nullable
	private final String myCurrentRevision;
	@Nonnull
	private final Repository.State myState;
	@Nonnull
	private final Set<GitRemote> myRemotes;
	@Nonnull
	private final Map<GitLocalBranch, Hash> myLocalBranches;
	@Nonnull
	private final Map<GitRemoteBranch, Hash> myRemoteBranches;
	@Nonnull
	private final Set<GitBranchTrackInfo> myBranchTrackInfos;
	@Nonnull
	private final Collection<GitSubmoduleInfo> mySubmodules;
	@Nonnull
	private final GitHooksInfo myHooksInfo;

	public GitRepoInfo(@Nullable GitLocalBranch currentBranch,
			@Nullable String currentRevision,
			@Nonnull Repository.State state,
			@Nonnull Collection<GitRemote> remotes,
			@Nonnull Map<GitLocalBranch, Hash> localBranches,
			@Nonnull Map<GitRemoteBranch, Hash> remoteBranches,
			@Nonnull Collection<GitBranchTrackInfo> branchTrackInfos,
			@Nonnull Collection<GitSubmoduleInfo> submodules,
			@Nonnull GitHooksInfo hooksInfo)
	{
		myCurrentBranch = currentBranch;
		myCurrentRevision = currentRevision;
		myState = state;
		myRemotes = new LinkedHashSet<>(remotes);
		myLocalBranches = new LinkedHashMap<>(localBranches);
		myRemoteBranches = new LinkedHashMap<>(remoteBranches);
		myBranchTrackInfos = new LinkedHashSet<>(branchTrackInfos);
		mySubmodules = submodules;
		myHooksInfo = hooksInfo;
	}

	@Nullable
	public GitLocalBranch getCurrentBranch()
	{
		return myCurrentBranch;
	}

	@Nonnull
	public Collection<GitRemote> getRemotes()
	{
		return myRemotes;
	}

	@Nonnull
	public Map<GitLocalBranch, Hash> getLocalBranchesWithHashes()
	{
		return myLocalBranches;
	}

	@Nonnull
	public Map<GitRemoteBranch, Hash> getRemoteBranchesWithHashes()
	{
		return myRemoteBranches;
	}

	@Nonnull
	@Deprecated
	public Collection<GitRemoteBranch> getRemoteBranches()
	{
		return myRemoteBranches.keySet();
	}

	@Nonnull
	public Collection<GitBranchTrackInfo> getBranchTrackInfos()
	{
		return myBranchTrackInfos;
	}

	@Nullable
	public String getCurrentRevision()
	{
		return myCurrentRevision;
	}

	@Nonnull
	public Repository.State getState()
	{
		return myState;
	}

	@Nonnull
	public Collection<GitSubmoduleInfo> getSubmodules()
	{
		return mySubmodules;
	}

	@Nonnull
	public GitHooksInfo getHooksInfo()
	{
		return myHooksInfo;
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o)
		{
			return true;
		}
		if(o == null || getClass() != o.getClass())
		{
			return false;
		}

		GitRepoInfo info = (GitRepoInfo) o;

		if(myState != info.myState)
		{
			return false;
		}
		if(myCurrentRevision != null ? !myCurrentRevision.equals(info.myCurrentRevision) : info.myCurrentRevision != null)
		{
			return false;
		}
		if(myCurrentBranch != null ? !myCurrentBranch.equals(info.myCurrentBranch) : info.myCurrentBranch != null)
		{
			return false;
		}
		if(!myRemotes.equals(info.myRemotes))
		{
			return false;
		}
		if(!myBranchTrackInfos.equals(info.myBranchTrackInfos))
		{
			return false;
		}
		if(!areEqual(myLocalBranches, info.myLocalBranches))
		{
			return false;
		}
		if(!areEqual(myRemoteBranches, info.myRemoteBranches))
		{
			return false;
		}
		if(!mySubmodules.equals(info.mySubmodules))
		{
			return false;
		}
		if(!myHooksInfo.equals(info.myHooksInfo))
		{
			return false;
		}

		return true;
	}

	@Override
	public int hashCode()
	{
		int result = myCurrentBranch != null ? myCurrentBranch.hashCode() : 0;
		result = 31 * result + (myCurrentRevision != null ? myCurrentRevision.hashCode() : 0);
		result = 31 * result + myState.hashCode();
		result = 31 * result + myRemotes.hashCode();
		result = 31 * result + myLocalBranches.hashCode();
		result = 31 * result + myRemoteBranches.hashCode();
		result = 31 * result + myBranchTrackInfos.hashCode();
		result = 31 * result + mySubmodules.hashCode();
		result = 31 * result + myHooksInfo.hashCode();
		return result;
	}

	@Override
	public String toString()
	{
		return String.format("GitRepoInfo{current=%s, remotes=%s, localBranches=%s, remoteBranches=%s, trackInfos=%s, submodules=%s, hooks=%s}", myCurrentBranch, myRemotes, myLocalBranches,
				myRemoteBranches, myBranchTrackInfos, mySubmodules, myHooksInfo);
	}

	private static <T extends GitBranch> boolean areEqual(Map<T, Hash> c1, Map<T, Hash> c2)
	{
		// GitBranch has perverted equals contract (see the comment there)
		// until GitBranch is created only from a single place with correctly defined Hash, we can't change its equals
		Set<Map.Entry<? extends GitBranch, Hash>> set1 = Sets.newHashSet(c1.entrySet(), new BranchesComparingStrategy());
		Set<Map.Entry<? extends GitBranch, Hash>> set2 = Sets.newHashSet(c2.entrySet(), new BranchesComparingStrategy());
		return set1.equals(set2);
	}

	private static class BranchesComparingStrategy implements HashingStrategy<Map.Entry<? extends GitBranch, Hash>>
	{

		@Override
		public int hashCode(@Nonnull Map.Entry<? extends GitBranch, Hash> branchEntry)
		{
			return 31 * branchEntry.getKey().getName().hashCode() + branchEntry.getValue().hashCode();
		}

		@Override
		public boolean equals(@Nonnull Map.Entry<? extends GitBranch, Hash> b1, @Nonnull Map.Entry<? extends GitBranch, Hash> b2)
		{
			if(b1 == b2)
			{
				return true;
			}
			if(b1.getClass() != b2.getClass())
			{
				return false;
			}
			return b1.getKey().getName().equals(b2.getKey().getName()) && b1.getValue().equals(b2.getValue());
		}
	}

}
