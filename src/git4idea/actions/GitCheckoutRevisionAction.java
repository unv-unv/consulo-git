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
package git4idea.actions;

import java.util.Collections;

import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.vcs.log.VcsFullCommitDetails;
import git4idea.branch.GitBrancher;
import git4idea.repo.GitRepository;

public class GitCheckoutRevisionAction extends GitLogSingleCommitAction
{

	@Override
	protected void actionPerformed(@NotNull GitRepository repository, @NotNull VcsFullCommitDetails commit)
	{
		GitBrancher brancher = ServiceManager.getService(repository.getProject(), GitBrancher.class);
		brancher.checkout(commit.getId().asString(), Collections.singletonList(repository), null);
	}

}
