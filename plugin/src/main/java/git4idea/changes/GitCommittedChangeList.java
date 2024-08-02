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
package git4idea.changes;

import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.versionBrowser.CommittedChangeListForRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitVcs;

import jakarta.annotation.Nonnull;
import java.util.Collection;
import java.util.Date;

public class GitCommittedChangeList extends CommittedChangeListForRevision
{

	private final boolean myModifiable;
	private final AbstractVcs myVcs;

	@SuppressWarnings("unused") // used externally
	@Deprecated
	public GitCommittedChangeList(@Nonnull String name,
			@Nonnull String comment,
			@Nonnull String committerName,
			@Nonnull GitRevisionNumber revisionNumber,
			@Nonnull Date commitDate,
			@Nonnull Collection<Change> changes,
			boolean isModifiable)
	{
		super(name, comment, committerName, commitDate, changes, revisionNumber);
		myVcs = null;
		myModifiable = isModifiable;
	}

	public GitCommittedChangeList(@Nonnull String name,
			@Nonnull String comment,
			@Nonnull String committerName,
			@Nonnull GitRevisionNumber revisionNumber,
			@Nonnull Date commitDate,
			@Nonnull Collection<Change> changes,
			@Nonnull GitVcs vcs,
			boolean isModifiable)
	{
		super(name, comment, committerName, commitDate, changes, revisionNumber);
		myVcs = vcs;
		myModifiable = isModifiable;
	}

	@Override
	public boolean isModifiable()
	{
		return myModifiable;
	}

	@Override
	public long getNumber()
	{
		return GitChangeUtils.longForSHAHash(getRevisionNumber().asString());
	}

	@Override
	@Nonnull
	public GitRevisionNumber getRevisionNumber()
	{
		return (GitRevisionNumber) super.getRevisionNumber();
	}

	@Override
	public AbstractVcs getVcs()
	{
		return myVcs;
	}
}
