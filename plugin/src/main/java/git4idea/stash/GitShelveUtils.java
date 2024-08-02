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
package git4idea.stash;

import consulo.application.Application;
import consulo.application.ApplicationManager;
import consulo.application.util.function.Processor;
import consulo.document.DocumentReference;
import consulo.document.DocumentReferenceManager;
import consulo.ide.impl.idea.openapi.vcs.changes.shelf.*;
import consulo.ide.impl.idea.openapi.vfs.VfsUtil;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.undoRedo.ProjectUndoManager;
import consulo.undoRedo.UndoManager;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.Change;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;

import jakarta.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class GitShelveUtils {
  private static final Logger LOG = Logger.getInstance(GitShelveUtils.class);

  public static void doSystemUnshelve(final Project project,
                                      final ShelvedChangeList shelvedChangeList,
                                      final ShelveChangesManager shelveManager,
                                      @Nullable final String leftConflictTitle,
                                      @Nullable final String rightConflictTitle) {
    VirtualFile baseDir = project.getBaseDir();
    assert baseDir != null;
    final String projectPath = baseDir.getPath() + "/";

    LOG.info("refreshing files ");
    // The changes are temporary copied to the first local change list, the next operation will restore them back
    // Refresh files that might be affected by unshelve
    refreshFilesBeforeUnshelve(project, shelvedChangeList, projectPath);

    LOG.info("Unshelving shelvedChangeList: " + shelvedChangeList);
    final List<ShelvedChange> changes = shelvedChangeList.getChanges(project);
    // we pass null as target change list for Patch Applier to do NOTHING with change lists
    shelveManager.unshelveChangeList(shelvedChangeList,
                                     changes,
                                     shelvedChangeList.getBinaryFiles(),
                                     null,
                                     false,
                                     true,
                                     true,
                                     leftConflictTitle,
                                     rightConflictTitle);
    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        markUnshelvedFilesNonUndoable(project, changes);
      }
    }, Application.get().getDefaultModalityState());
  }

  @RequiredUIAccess
  private static void markUnshelvedFilesNonUndoable(@Nonnull final Project project, @Nonnull List<ShelvedChange> changes) {
    final UndoManager undoManager = ProjectUndoManager.getInstance(project);
    if (undoManager != null && !changes.isEmpty()) {
      ContainerUtil.process(changes, new Processor<ShelvedChange>() {
        @Override
        public boolean process(ShelvedChange change) {
          final VirtualFile vfUnderProject = VfsUtil.findFileByIoFile(new File(project.getBasePath(), change.getAfterPath()), false);
          if (vfUnderProject != null) {
            final DocumentReference documentReference = DocumentReferenceManager.getInstance().create(vfUnderProject);
            undoManager.nonundoableActionPerformed(documentReference, false);
            undoManager.invalidateActionsFor(documentReference);
          }
          return true;
        }
      });
    }
  }

  private static void refreshFilesBeforeUnshelve(final Project project, ShelvedChangeList shelvedChangeList, String projectPath) {
    HashSet<File> filesToRefresh = new HashSet<>();
    for (ShelvedChange c : shelvedChangeList.getChanges(project)) {
      if (c.getBeforePath() != null) {
        filesToRefresh.add(new File(projectPath + c.getBeforePath()));
      }
      if (c.getAfterPath() != null) {
        filesToRefresh.add(new File(projectPath + c.getAfterPath()));
      }
    }
    for (ShelvedBinaryFile f : shelvedChangeList.getBinaryFiles()) {
      if (f.BEFORE_PATH != null) {
        filesToRefresh.add(new File(projectPath + f.BEFORE_PATH));
      }
      if (f.AFTER_PATH != null) {
        filesToRefresh.add(new File(projectPath + f.AFTER_PATH));
      }
    }
    LocalFileSystem.getInstance().refreshIoFiles(filesToRefresh);
  }

  /**
   * Shelve changes
   *
   * @param project       the context project
   * @param shelveManager the shelve manager
   * @param changes       the changes to process
   * @param description   the description of for the shelve
   * @param exceptions    the generated exceptions
   * @param rollback
   * @return created shelved change list or null in case failure
   */
  @Nullable
  public static ShelvedChangeList shelveChanges(final Project project,
                                                final ShelveChangesManager shelveManager,
                                                Collection<Change> changes,
                                                final String description,
                                                final List<VcsException> exceptions,
                                                boolean rollback,
                                                boolean markToBeDeleted) {
    try {
      ShelvedChangeList shelve = shelveManager.shelveChanges(changes, description, rollback, markToBeDeleted);
      project.getMessageBus().syncPublisher(ShelveChangesListener.class).changeChanged(shelveManager);
      return shelve;
    }
    catch (IOException e) {
      //noinspection ThrowableInstanceNeverThrown
      exceptions.add(new VcsException("Shelving changes failed: " + description, e));
      return null;
    }
    catch (VcsException e) {
      exceptions.add(e);
      return null;
    }
  }
}
