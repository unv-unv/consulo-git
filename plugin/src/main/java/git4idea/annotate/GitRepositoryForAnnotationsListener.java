/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.annotate;

import consulo.project.Project;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.change.VcsAnnotationRefresher;
import git4idea.GitVcs;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import jakarta.annotation.Nonnull;

/**
 * Created with IntelliJ IDEA.
 * User: Irina.Chernushina
 * Date: 11/26/12
 * Time: 2:11 PM
 */
public class GitRepositoryForAnnotationsListener {
    private final Project myProject;
    private final GitRepositoryChangeListener myListener;
    private ProjectLevelVcsManager myVcsManager;
    private GitVcs myVcs;

    public GitRepositoryForAnnotationsListener(Project project) {
        myProject = project;
        myListener = createListener();
        myVcs = GitVcs.getInstance(myProject);
        myVcsManager = ProjectLevelVcsManager.getInstance(myProject);
        project.getMessageBus().connect().subscribe(GitRepositoryChangeListener.class, myListener);
    }

    private GitRepositoryChangeListener createListener() {
        return new GitRepositoryChangeListener() {
            @Override
            public void repositoryChanged(@Nonnull GitRepository repository) {
                final VcsAnnotationRefresher refresher =
                    myProject.getMessageBus().syncPublisher(VcsAnnotationRefresher.class);
                refresher.dirtyUnder(repository.getRoot());
            }
        };
    }
}
