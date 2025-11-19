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
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.project.ui.notification.NotificationService;
import consulo.ui.annotation.RequiredUIAccess;
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
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

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

    @Nonnull
    @Override
    public String getVcsName() {
        return "_Git";
    }

    @Override
    @RequiredUIAccess
    public void doCheckout(@Nonnull Project project, @Nullable Listener listener) {
        BasicAction.saveAll();
        GitCloneDialog dialog = new GitCloneDialog(project);
        dialog.show();
        if (!dialog.isOK()) {
            return;
        }
        dialog.rememberSettings();
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        File parent = new File(dialog.getParentDirectory());
        VirtualFile destinationParent = lfs.findFileByIoFile(parent);
        if (destinationParent == null) {
            destinationParent = lfs.refreshAndFindFileByIoFile(parent);
        }
        if (destinationParent == null) {
            return;
        }
        String sourceRepositoryURL = dialog.getSourceRepositoryURL();
        String directoryName = dialog.getDirectoryName();
        String parentDirectory = dialog.getParentDirectory();
        String puttyKey = dialog.getPuttyKeyFile();
        clone(project, myGit, listener, destinationParent, sourceRepositoryURL, directoryName, parentDirectory, puttyKey);
    }

    public static void clone(
        final Project project,
        @Nonnull final Git git,
        final Listener listener,
        final VirtualFile destinationParent,
        final String sourceRepositoryURL,
        final String directoryName,
        final String parentDirectory,
        final String puttyKey
    ) {
        final AtomicBoolean cloneResult = new AtomicBoolean();
        new Task.Backgroundable(project, GitLocalize.cloningRepository(sourceRepositoryURL)) {
            @Override
            public void run(@Nonnull ProgressIndicator indicator) {
                cloneResult.set(doClone(project, indicator, git, directoryName, parentDirectory, sourceRepositoryURL, puttyKey));
            }

            @Override
            @RequiredUIAccess
            public void onSuccess() {
                if (!cloneResult.get()) {
                    return;
                }

                destinationParent.refresh(
                    true,
                    true,
                    () -> {
                        if (project.isOpen() && (!project.isDisposed()) && (!project.isDefault())) {
                            VcsDirtyScopeManager mgr = VcsDirtyScopeManager.getInstance(project);
                            mgr.fileDirty(destinationParent);
                        }
                    }
                );
                listener.directoryCheckedOut(new File(parentDirectory, directoryName), GitVcs.getKey());
                listener.checkoutCompleted();
            }
        }.queue();
    }

    public static boolean doClone(
        @Nonnull Project project,
        @Nonnull ProgressIndicator indicator,
        @Nonnull Git git,
        @Nonnull String directoryName,
        @Nonnull String parentDirectory,
        @Nonnull String sourceRepositoryURL,
        String puttyKey
    ) {
        return cloneNatively(project, indicator, git, new File(parentDirectory), sourceRepositoryURL, directoryName, puttyKey);
    }

    private static boolean cloneNatively(
        @Nonnull Project project,
        @Nonnull ProgressIndicator indicator,
        @Nonnull Git git,
        @Nonnull File directory,
        @Nonnull String url,
        @Nonnull String cloneDirectoryName,
        String puttyKey
    ) {
        indicator.setIndeterminate(false);
        GitLineHandlerListener progressListener = GitStandardProgressAnalyzer.createListener(indicator);
        GitCommandResult result = git.clone(project, directory, url, puttyKey, cloneDirectoryName, progressListener);
        if (result.success()) {
            return true;
        }
        NotificationService.getInstance().newError(VcsNotifier.IMPORTANT_ERROR_NOTIFICATION)
            .title(LocalizeValue.localizeTODO("Clone failed"))
            .content(result.getErrorOutputAsHtmlValue())
            .notify(project);
        return false;
    }
}
