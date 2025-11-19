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
package git4idea.commands;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.ide.ServiceManager;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.branch.GitRebaseParams;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.reset.GitResetMode;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@ServiceAPI(ComponentScope.APPLICATION)
public interface Git {
    @Nonnull
    static Git getInstance() {
        return ServiceManager.getService(Git.class);
    }

    /**
     * A generic method to run a Git command, when existing methods like {@link #fetch(GitRepository, String, String, List, String...)}
     * are not sufficient.
     *
     * @param handlerConstructor this is needed, since the operation may need to repeat (e.g. in case of authentication failure).
     *                           make sure to supply a stateless constructor.
     */
    @Nonnull
    GitCommandResult runCommand(@Nonnull Supplier<GitLineHandler> handlerConstructor);

    /**
     * A generic method to run a Git command, when existing methods are not sufficient. <br/>
     * Can be used instead of {@link #runCommand(Supplier)} if the operation will not need to be repeated for sure
     * (e.g. it is a completely local operation).
     */
    @Nonnull
    GitCommandResult runCommand(@Nonnull GitLineHandler handler);

    @Nonnull
    GitCommandResult init(@Nonnull Project project, @Nonnull VirtualFile root, @Nonnull GitLineHandlerListener... listeners);

    @Nonnull
    Set<VirtualFile> untrackedFiles(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nullable Collection<VirtualFile> files
    ) throws VcsException;

    // relativePaths are guaranteed to fit into command line length limitations.
    @Nonnull
    Collection<VirtualFile> untrackedFilesNoChunk(
        @Nonnull Project project,
        @Nonnull VirtualFile root,
        @Nullable List<String> relativePaths
    ) throws VcsException;

    @Nonnull
    GitCommandResult clone(
        @Nonnull Project project,
        @Nonnull File parentDirectory,
        @Nonnull String url,
        @Nullable String puttyKey,
        @Nonnull String clonedDirectoryName,
        @Nonnull GitLineHandlerListener... progressListeners
    );

    @Nonnull
    GitCommandResult config(@Nonnull GitRepository repository, String... params);

    @Nonnull
    GitCommandResult diff(@Nonnull GitRepository repository, @Nonnull List<String> parameters, @Nonnull String range);

    @Nonnull
    GitCommandResult merge(
        @Nonnull GitRepository repository,
        @Nonnull String branchToMerge,
        @Nullable List<String> additionalParams,
        @Nonnull GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult checkout(
        @Nonnull GitRepository repository,
        @Nonnull String reference,
        @Nullable String newBranch,
        boolean force,
        boolean detach,
        @Nonnull GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult checkoutNewBranch(
        @Nonnull GitRepository repository,
        @Nonnull String branchName,
        @Nullable GitLineHandlerListener listener
    );

    @Nonnull
    GitCommandResult createNewTag(
        @Nonnull GitRepository repository,
        @Nonnull String tagName,
        @Nullable GitLineHandlerListener listener,
        @Nonnull String reference
    );

    @Nonnull
    GitCommandResult branchDelete(
        @Nonnull GitRepository repository,
        @Nonnull String branchName,
        boolean force,
        @Nonnull GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult branchContains(@Nonnull GitRepository repository, @Nonnull String commit);

    /**
     * Create branch without checking it out: <br/>
     * <pre>    git branch &lt;branchName&gt; &lt;startPoint&gt;</pre>
     */
    @Nonnull
    GitCommandResult branchCreate(@Nonnull GitRepository repository, @Nonnull String branchName, @Nonnull String startPoint);

    @Nonnull
    GitCommandResult renameBranch(
        @Nonnull GitRepository repository,
        @Nonnull String currentName,
        @Nonnull String newName,
        @Nonnull GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult reset(
        @Nonnull GitRepository repository,
        @Nonnull GitResetMode mode,
        @Nonnull String target,
        @Nonnull GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult resetMerge(@Nonnull GitRepository repository, @Nullable String revision);

    @Nonnull
    GitCommandResult tip(@Nonnull GitRepository repository, @Nonnull String branchName);

    @Nonnull
    GitCommandResult push(
        @Nonnull GitRepository repository,
        @Nonnull String remote,
        @Nullable String url,
        @Nullable String puttyKey,
        @Nonnull String spec,
        boolean updateTracking,
        @Nonnull GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult push(
        @Nonnull GitRepository repository,
        @Nonnull GitRemote remote,
        @Nonnull String spec,
        boolean force,
        boolean updateTracking,
        boolean skipHook,
        @Nullable String tagMode,
        GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult show(@Nonnull GitRepository repository, @Nonnull String... params);

    @Nonnull
    GitCommandResult cherryPick(
        @Nonnull GitRepository repository,
        @Nonnull String hash,
        boolean autoCommit,
        @Nonnull GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult getUnmergedFiles(@Nonnull GitRepository repository);

    @Nonnull
    GitCommandResult checkAttr(
        @Nonnull GitRepository repository,
        @Nonnull Collection<String> attributes,
        @Nonnull Collection<VirtualFile> files
    );

    @Nonnull
    GitCommandResult stashSave(@Nonnull GitRepository repository, @Nonnull String message);

    @Nonnull
    GitCommandResult stashPop(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners);

    @Nonnull
    GitCommandResult fetch(
        @Nonnull GitRepository repository,
        @Nonnull GitRemote remote,
        @Nonnull List<GitLineHandlerListener> listeners,
        String... params
    );

    @Nonnull
    GitCommandResult addRemote(@Nonnull GitRepository repository, @Nonnull String name, @Nonnull String url);

    @Nonnull
    GitCommandResult lsRemote(@Nonnull Project project, @Nonnull File workingDir, @Nonnull String url);

    @Nonnull
    GitCommandResult lsRemote(
        @Nonnull Project project,
        @Nonnull VirtualFile workingDir,
        @Nonnull GitRemote remote,
        String... additionalParameters
    );

    @Nonnull
    GitCommandResult remotePrune(@Nonnull GitRepository repository, @Nonnull GitRemote remote);

    @Nonnull
    GitCommandResult rebase(
        @Nonnull GitRepository repository,
        @Nonnull GitRebaseParams parameters,
        @Nonnull GitLineHandlerListener... listeners
    );

    @Nonnull
    GitCommandResult rebaseAbort(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners);

    @Nonnull
    GitCommandResult rebaseContinue(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners);

    @Nonnull
    GitCommandResult rebaseSkip(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners);
}
