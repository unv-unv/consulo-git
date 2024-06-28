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
package git4idea.status;


import consulo.project.Project;
import consulo.util.io.FileUtil;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.action.VcsContextFactory;
import consulo.versionControlSystem.change.Change;
import consulo.versionControlSystem.change.ChangeListManager;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.change.VcsDirtyScope;
import consulo.versionControlSystem.root.VcsRoot;
import consulo.virtualFileSystem.VirtualFile;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.*;

/**
 * Common
 * Serves as a container of common utility functions to collect dirty paths for both {@link GitNewChangesCollector} and
 * {@link GitOldChangesCollector}.
 *
 * @author Kirill Likhodedov
 */
abstract class GitChangesCollector {
  @Nonnull
  protected final Project myProject;
  @Nonnull
  protected final VirtualFile myVcsRoot;

  @Nonnull
  private final VcsDirtyScope myDirtyScope;
  @Nonnull
  private final ChangeListManager myChangeListManager;
  @Nonnull
  private final ProjectLevelVcsManager myVcsManager;
  @Nonnull
  private AbstractVcs myVcs;


  GitChangesCollector(@Nonnull Project project, @Nonnull ChangeListManager changeListManager, @Nonnull ProjectLevelVcsManager vcsManager,
                      @Nonnull AbstractVcs vcs, @Nonnull VcsDirtyScope dirtyScope, @Nonnull VirtualFile vcsRoot) {
    myProject = project;
    myChangeListManager = changeListManager;
    myVcsManager = vcsManager;
    myVcs = vcs;
    myDirtyScope = dirtyScope;
    myVcsRoot = vcsRoot;
  }

  /**
   * @return the set of unversioned files (from the specified dirty scope).
   */
  abstract
  @Nonnull
  Collection<VirtualFile> getUnversionedFiles();

  /**
   * @return the set of changes (changed files) from the specified dirty scope.
   */
  abstract
  @Nonnull
  Collection<Change> getChanges();

  /**
   * Collect dirty file paths
   *
   * @param includeChanges if true, previous changes are included in collection
   * @return the set of dirty paths to check, the paths are automatically collapsed if the summary length more than limit
   */
  protected Collection<FilePath> dirtyPaths(boolean includeChanges) {
    final List<String> allPaths = new ArrayList<String>();

    for (FilePath p : myDirtyScope.getRecursivelyDirtyDirectories()) {
      addToPaths(p, allPaths);
    }
    for (FilePath p : myDirtyScope.getDirtyFilesNoExpand()) {
      addToPaths(p, allPaths);
    }

    if (includeChanges) {
      for (Change c : myChangeListManager.getChangesIn(myVcsRoot)) {
        switch (c.getType()) {
          case NEW:
          case DELETED:
          case MOVED:
            ContentRevision afterRevision = c.getAfterRevision();
            if (afterRevision != null) {
              addToPaths(afterRevision.getFile(), allPaths);
            }
            ContentRevision beforeRevision = c.getBeforeRevision();
            if (beforeRevision != null) {
              addToPaths(beforeRevision.getFile(), allPaths);
            }
          case MODIFICATION:
          default:
            // do nothing
        }
      }
    }

    removeCommonParents(allPaths);

    final List<FilePath> paths = new ArrayList<FilePath>(allPaths.size());
    for (String p : allPaths) {
      final File file = new File(p);
      paths.add(VcsContextFactory.getInstance().createFilePathOn(file, file.isDirectory()));
    }
    return paths;
  }

  protected void addToPaths(FilePath pathToAdd, List<String> paths) {
    VcsRoot fileRoot = myVcsManager.getVcsRootObjectFor(pathToAdd);
    if (fileRoot != null && fileRoot.getVcs() != null && myVcs.equals(fileRoot.getVcs()) && myVcsRoot.equals(fileRoot.getPath())) {
      paths.add(pathToAdd.getPath());
    }
  }

  protected static void removeCommonParents(List<String> allPaths) {
    Collections.sort(allPaths);

    String prevPath = null;
    Iterator<String> it = allPaths.iterator();
    while (it.hasNext()) {
      String path = it.next();
      if (prevPath != null && FileUtil.startsWith(path, prevPath)) {      // the file is under previous file, so enough to check the parent
        it.remove();
      }
      else {
        prevPath = path;
      }
    }
  }

}
