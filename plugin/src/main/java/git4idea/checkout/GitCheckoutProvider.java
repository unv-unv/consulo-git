/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package git4idea.checkout;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.progress.ProgressIndicator;
import consulo.application.progress.Task;
import consulo.project.Project;
import consulo.versionControlSystem.VcsNotifier;
import consulo.versionControlSystem.change.VcsDirtyScopeManager;
import consulo.versionControlSystem.checkout.CheckoutProvider;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitVcs;
import git4idea.actions.BasicAction;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandlerListener;
import git4idea.commands.GitStandardProgressAnalyzer;
import git4idea.i18n.GitBundle;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import jakarta.annotation.Nonnull;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checkout provider for the Git
 */
@ExtensionImpl
public class GitCheckoutProvider implements CheckoutProvider {
  private final Git myGit;

  @Inject
  public GitCheckoutProvider(@Nonnull Git git) {
    myGit = git;
  }

  public String getVcsName() {
    return "_Git";
  }

  public void doCheckout(@Nonnull final Project project, @Nullable final Listener listener) {
    BasicAction.saveAll();
    GitCloneDialog dialog = new GitCloneDialog(project);
    dialog.show();
    if (!dialog.isOK()) {
      return;
    }
    dialog.rememberSettings();
    final LocalFileSystem lfs = LocalFileSystem.getInstance();
    final File parent = new File(dialog.getParentDirectory());
    VirtualFile destinationParent = lfs.findFileByIoFile(parent);
    if (destinationParent == null) {
      destinationParent = lfs.refreshAndFindFileByIoFile(parent);
    }
    if (destinationParent == null) {
      return;
    }
    final String sourceRepositoryURL = dialog.getSourceRepositoryURL();
    final String directoryName = dialog.getDirectoryName();
    final String parentDirectory = dialog.getParentDirectory();
    final String puttyKey = dialog.getPuttyKeyFile();
    clone(project, myGit, listener, destinationParent, sourceRepositoryURL, directoryName, parentDirectory, puttyKey);
  }

  public static void clone(final Project project,
                           @Nonnull final Git git,
                           final Listener listener,
                           final VirtualFile destinationParent,
                           final String sourceRepositoryURL,
                           final String directoryName,
                           final String parentDirectory,
                           final String puttyKey) {

    final AtomicBoolean cloneResult = new AtomicBoolean();
    new Task.Backgroundable(project, GitBundle.message("cloning.repository", sourceRepositoryURL)) {
      @Override
      public void run(@Nonnull ProgressIndicator indicator) {
        cloneResult.set(doClone(project, indicator, git, directoryName, parentDirectory, sourceRepositoryURL, puttyKey));
      }

      @Override
      public void onSuccess() {
        if (!cloneResult.get()) {
          return;
        }

        destinationParent.refresh(true, true, new Runnable() {
          public void run() {
            if (project.isOpen() && (!project.isDisposed()) && (!project.isDefault())) {
              final VcsDirtyScopeManager mgr = VcsDirtyScopeManager.getInstance(project);
              mgr.fileDirty(destinationParent);
            }
          }
        });
        listener.directoryCheckedOut(new File(parentDirectory, directoryName), GitVcs.getKey());
        listener.checkoutCompleted();
      }
    }.queue();
  }

  public static boolean doClone(@Nonnull Project project,
                                @Nonnull ProgressIndicator indicator,
                                @Nonnull Git git,
                                @Nonnull String directoryName,
                                @Nonnull String parentDirectory,
                                @Nonnull String sourceRepositoryURL,
                                String puttyKey) {
    return cloneNatively(project, indicator, git, new File(parentDirectory), sourceRepositoryURL, directoryName, puttyKey);
  }

  private static boolean cloneNatively(@Nonnull Project project, @Nonnull final ProgressIndicator indicator, @Nonnull Git git,
                                       @Nonnull File directory, @Nonnull String url, @Nonnull String cloneDirectoryName, String puttyKey) {
    indicator.setIndeterminate(false);
    GitLineHandlerListener progressListener = GitStandardProgressAnalyzer.createListener(indicator);
    GitCommandResult result = git.clone(project, directory, url, puttyKey, cloneDirectoryName, progressListener);
    if (result.success()) {
      return true;
    }
    VcsNotifier.getInstance(project).notifyError("Clone failed", result.getErrorOutputAsHtmlString());
    return false;
  }

}
