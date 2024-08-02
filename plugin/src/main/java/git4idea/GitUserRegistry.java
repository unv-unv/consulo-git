/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.application.ApplicationManager;
import consulo.disposer.Disposable;
import consulo.ide.ServiceManager;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.StringUtil;
import consulo.util.lang.function.Condition;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsListener;
import consulo.versionControlSystem.log.VcsLogObjectsFactory;
import consulo.versionControlSystem.log.VcsUser;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.config.GitConfigUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitUserRegistry implements Disposable, VcsListener {

  private static final Logger LOG = Logger.getInstance(GitUserRegistry.class);

  @Nonnull
  private final Project myProject;
  @Nonnull
  private final ProjectLevelVcsManager myVcsManager;
  @Nonnull
  private final VcsLogObjectsFactory myFactory;
  @Nonnull
  private final Map<VirtualFile, VcsUser> myUserMap = ContainerUtil.newConcurrentMap();

  @Inject
  public GitUserRegistry(@Nonnull Project project, @Nonnull ProjectLevelVcsManager vcsManager, @Nonnull VcsLogObjectsFactory factory) {
    myProject = project;
    myVcsManager = vcsManager;
    myFactory = factory;
  }

  public static GitUserRegistry getInstance(@Nonnull Project project) {
    return ServiceManager.getService(project, GitUserRegistry.class);
  }

  public void activate() {
    myProject.getMessageBus().connect().subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, this);
    directoryMappingChanged();
  }

  @Nullable
  public VcsUser getUser(@Nonnull VirtualFile root) {
    return myUserMap.get(root);
  }

  @Nullable
  public VcsUser getOrReadUser(@Nonnull VirtualFile root) {
    VcsUser user = myUserMap.get(root);
    if (user == null) {
      try {
        user = readCurrentUser(myProject, root);
        if (user != null) {
          myUserMap.put(root, user);
        }
      }
      catch (VcsException e) {
        LOG.warn("Could not retrieve user name in " + root, e);
      }
    }
    return user;
  }

  @Nullable
  private VcsUser readCurrentUser(@Nonnull Project project, @Nonnull VirtualFile root) throws VcsException {
    String userName = GitConfigUtil.getValue(project, root, GitConfigUtil.USER_NAME);
    String userEmail = StringUtil.notNullize(GitConfigUtil.getValue(project, root, GitConfigUtil.USER_EMAIL));
    return userName == null ? null : myFactory.createUser(userName, userEmail);
  }

  @Override
  public void dispose() {
    myUserMap.clear();
  }

  @Override
  public void directoryMappingChanged() {
    GitVcs vcs = GitVcs.getInstance(myProject);
    if (vcs == null) {
      return;
    }
    final VirtualFile[] roots = myVcsManager.getRootsUnderVcs(vcs);
    final Collection<VirtualFile> rootsToCheck = ContainerUtil.filter(roots, new Condition<VirtualFile>() {
      @Override
      public boolean value(VirtualFile root) {
        return getUser(root) == null;
      }
    });
    if (!rootsToCheck.isEmpty()) {
      ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
        public void run() {
          for (VirtualFile root : rootsToCheck) {
            getOrReadUser(root);
          }
        }
      });
    }
  }

}
