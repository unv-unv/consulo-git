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

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.project.Project;
import consulo.versionControlSystem.log.VcsLogUi;
import git4idea.repo.GitRepositoryManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class DeepComparatorHolder implements Disposable {
  @Nonnull
  private final Project myProject;
  @Nonnull
  private final GitRepositoryManager myRepositoryManager;

  @Nonnull
  private final Map<VcsLogUi, DeepComparator> myComparators;

  @Inject
  public DeepComparatorHolder(@Nonnull Project project, @Nonnull GitRepositoryManager repositoryManager) {
    myProject = project;
    myRepositoryManager = repositoryManager;
    myComparators = new HashMap<>();
    Disposer.register(project, this);
  }

  @Nonnull
  public DeepComparator getInstance(@Nonnull VcsLogUi ui) {
    DeepComparator comparator = myComparators.get(ui);
    if (comparator == null) {
      comparator = new DeepComparator(myProject, myRepositoryManager, ui, this);
      myComparators.put(ui, comparator);
    }
    return comparator;
  }

  @Override
  public void dispose() {
    myComparators.clear();
  }

}
