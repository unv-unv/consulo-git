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
package git4idea.branch;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.inject.Singleton;

import consulo.disposer.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.VcsLogUi;
import consulo.disposer.Disposer;
import git4idea.repo.GitRepositoryManager;

@Singleton
public class DeepComparatorHolder implements Disposable
{

	@Nonnull
	private final Project myProject;
	@Nonnull
	private final GitRepositoryManager myRepositoryManager;

	@Nonnull
	private final Map<VcsLogUi, DeepComparator> myComparators;

	// initialized by pico-container
	@SuppressWarnings("UnusedDeclaration")
	private DeepComparatorHolder(@Nonnull Project project, @Nonnull GitRepositoryManager repositoryManager)
	{
		myProject = project;
		myRepositoryManager = repositoryManager;
		myComparators = ContainerUtil.newHashMap();
		Disposer.register(project, this);
	}

	@Nonnull
	public DeepComparator getInstance(@Nonnull VcsLogUi ui)
	{
		DeepComparator comparator = myComparators.get(ui);
		if(comparator == null)
		{
			comparator = new DeepComparator(myProject, myRepositoryManager, ui, this);
			myComparators.put(ui, comparator);
		}
		return comparator;
	}

	@Override
	public void dispose()
	{
		myComparators.clear();
	}

}
