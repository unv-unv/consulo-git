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
package git4idea;

import consulo.application.util.function.ThrowableComputable;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.VcsUser;
import consulo.versionControlSystem.log.base.VcsChangesLazilyParsedDetails;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.history.GitChangesParser;
import git4idea.history.GitLogStatusInfo;

import jakarta.annotation.Nonnull;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Represents a Git commit with its meta information (hash, author, message, etc.), its parents and the {@link Change changes}.
 *
 * @author Kirill Likhodedov
 */
public final class GitCommit extends VcsChangesLazilyParsedDetails
{

	public GitCommit(
			Project project,
			@Nonnull Hash hash,
			@Nonnull List<Hash> parents,
			long time,
			@Nonnull VirtualFile root,
			@Nonnull String subject,
			@Nonnull VcsUser author,
			@Nonnull String message,
			@Nonnull VcsUser committer,
			long authorTime,
			@Nonnull List<GitLogStatusInfo> reportedChanges)
	{
		super(hash, parents, time, root, subject, author, message, committer, authorTime, new MyChangesComputable(new Data(project, root,
				reportedChanges, hash, time, parents)));

	}

	private static class MyChangesComputable implements ThrowableComputable<Collection<Change>, VcsException>
	{

		private Data myData;
		private Collection<Change> myChanges;

		public MyChangesComputable(Data data)
		{
			myData = data;
		}

		@Override
		public Collection<Change> compute() throws VcsException
		{
			if(myChanges == null)
			{
				myChanges = GitChangesParser.parse(myData.project, myData.root, myData.changesOutput, myData.hash.asString(), new Date(myData.time),
						ContainerUtil.map(myData.parents, hash -> hash.asString()));
				myData = null; // don't hold the not-yet-parsed string
			}
			return myChanges;
		}

	}

	private static class Data
	{
		private final Project project;
		private final VirtualFile root;
		private final List<GitLogStatusInfo> changesOutput;
		private final Hash hash;
		private final long time;
		private final List<Hash> parents;

		public Data(Project project, VirtualFile root, List<GitLogStatusInfo> changesOutput, Hash hash, long time, List<Hash> parents)
		{
			this.project = project;
			this.root = root;
			this.changesOutput = changesOutput;
			this.hash = hash;
			this.time = time;
			this.parents = parents;
		}
	}

}
