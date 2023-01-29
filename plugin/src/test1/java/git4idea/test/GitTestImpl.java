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
package git4idea.test;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import consulo.process.ProcessOutputTypes;
import consulo.ide.impl.idea.openapi.util.io.FileUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import consulo.project.Project;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitImpl;
import git4idea.commands.GitLineHandlerListener;
import git4idea.history.browser.GitCommit;
import git4idea.push.GitPushSpec;
import git4idea.repo.GitRepository;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static consulo.util.lang.StringUtil.join;
import static git4idea.test.GitExecutor.cd;
import static git4idea.test.GitExecutor.git;
import static java.lang.String.format;

/**
 * @author Kirill Likhodedov
 */
public class GitTestImpl implements Git {

  @Nonnull
  @Override
  public GitCommandResult init(@Nonnull Project project, @Nonnull VirtualFile root, @Nonnull GitLineHandlerListener... listeners) {
    return execute(root.getPath(), "init", listeners);
  }

  @Nonnull
  @Override
  public Set<VirtualFile> untrackedFiles(@Nonnull Project project, @Nonnull VirtualFile root, @Nullable Collection<VirtualFile> files)
    throws VcsException {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public Collection<VirtualFile> untrackedFilesNoChunk(@Nonnull Project project,
                                                       @Nonnull VirtualFile root,
                                                       @Nullable List<String> relativePaths) throws VcsException {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public GitCommandResult clone(@Nonnull Project project,
								@Nonnull File parentDirectory,
								@Nonnull String url,
								@Nonnull String clonedDirectoryName, @Nonnull GitLineHandlerListener... progressListeners) {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public GitCommandResult config(@Nonnull GitRepository repository, String... params) {
    cd(repository);
    String output = git("config " + join(params, " "));
    int exitCode = output.trim().isEmpty() ? 1 : 0;
    return new GitCommandResult(!output.contains("fatal") && exitCode == 0, exitCode, Collections.<String>emptyList(),
                                Arrays.asList(StringUtil.splitByLines(output)), null);
  }

  @Nonnull
  @Override
  public GitCommandResult diff(@Nonnull GitRepository repository, @Nonnull List<String> parameters, @Nonnull String range) {
    return execute(repository, format("diff %s %s", join(parameters, " "), range));
  }

  @Nonnull
  @Override
  public GitCommandResult checkAttr(@Nonnull final GitRepository repository, @Nonnull Collection<String> attributes,
									@Nonnull Collection<VirtualFile> files) {
    cd(repository);
    Collection<String> relativePaths = Collections2.transform(files, new Function<VirtualFile, String>() {
      @Override
      public String apply(VirtualFile input) {
        return FileUtil.getRelativePath(repository.getRoot().getPath(), input.getPath(), '/');
      }
    });
    String output = git("check-attr %s -- %s", join(attributes, " "), join(relativePaths, " "));
    return commandResult(output);
  }

  @Nonnull
  @Override
  public GitCommandResult stashSave(@Nonnull GitRepository repository, @Nonnull String message) {
    return execute(repository, "stash save " + message);
  }

  @Nonnull
  @Override
  public GitCommandResult stashPop(@Nonnull GitRepository repository, @Nonnull GitLineHandlerListener... listeners) {
    return execute(repository, "stash pop");
  }

  @Nonnull
  @Override
  public List<GitCommit> history(@Nonnull GitRepository repository, @Nonnull String range) {
    return Collections.emptyList();
  }

  @Nonnull
  @Override
  public GitCommandResult merge(@Nonnull GitRepository repository, @Nonnull String branchToMerge, @Nullable List<String> additionalParams,
								@Nonnull GitLineHandlerListener... listeners) {
    String addParams = additionalParams == null ? "" : join(additionalParams, " ");
    return execute(repository, format("merge %s %s", addParams, branchToMerge), listeners);
  }

  @Nonnull
  @Override
  public GitCommandResult checkout(@Nonnull GitRepository repository, @Nonnull String reference, @Nullable String newBranch, boolean force,
								   @Nonnull GitLineHandlerListener... listeners) {
    return execute(repository, format("checkout %s %s %s",
                                      force ? "--force" : "",
                                      newBranch != null ? " -b " + newBranch : "", reference), listeners);
  }

  @Nonnull
  @Override
  public GitCommandResult checkoutNewBranch(@Nonnull GitRepository repository, @Nonnull String branchName,
											@Nullable GitLineHandlerListener listener) {
    return execute(repository, "checkout -b " + branchName, listener);
  }

  @Nonnull
  @Override
  public GitCommandResult branchDelete(@Nonnull GitRepository repository, @Nonnull String branchName, boolean force,
									   @Nonnull GitLineHandlerListener... listeners) {
    return execute(repository, format("branch %s %s", force ? "-D" : "-d", branchName), listeners);
  }

  @Nonnull
  @Override
  public GitCommandResult branchContains(@Nonnull GitRepository repository, @Nonnull String commit) {
    return execute(repository, "branch --contains " + commit);
  }

  @Nonnull
  @Override
  public GitCommandResult branchCreate(@Nonnull GitRepository repository, @Nonnull String branchName) {
    return execute(repository, "branch " + branchName);
  }

  @Nonnull
  @Override
  public GitCommandResult resetHard(@Nonnull GitRepository repository, @Nonnull String revision) {
    return execute(repository, "reset --hard " + revision);
  }

  @Nonnull
  @Override
  public GitCommandResult resetMerge(@Nonnull GitRepository repository, @Nullable String revision) {
    return execute(repository, "reset --merge " + revision);
  }

  @Nonnull
  @Override
  public GitCommandResult tip(@Nonnull GitRepository repository, @Nonnull String branchName) {
    return execute(repository, "rev-list -1 " + branchName);
  }

  @Nonnull
  @Override
  public GitCommandResult push(@Nonnull GitRepository repository, @Nonnull String remote, @Nonnull String url, @Nonnull String spec,
							   @Nonnull GitLineHandlerListener... listeners) {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public GitCommandResult push(@Nonnull GitRepository repository,
							   @Nonnull GitPushSpec spec, @Nonnull String url, @Nonnull GitLineHandlerListener... listeners) {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public GitCommandResult show(@Nonnull GitRepository repository, @Nonnull String... params) {
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public GitCommandResult cherryPick(@Nonnull GitRepository repository,
                                     @Nonnull String hash,
                                     boolean autoCommit,
                                     @Nonnull GitLineHandlerListener... listeners) {
    return execute(repository, format("cherry-pick -x %s %s", autoCommit ? "" : "-n", hash), listeners);
  }

  @Nonnull
  @Override
  public GitCommandResult getUnmergedFiles(@Nonnull GitRepository repository) {
    return execute(repository, "ls-files --unmerged");
  }

  @Nonnull
  @Override
  public GitCommandResult createNewTag(@Nonnull GitRepository repository,
                                       @Nonnull String tagName,
                                       @Nullable GitLineHandlerListener listener,
                                       @Nonnull String reference) {
    throw new UnsupportedOperationException();
  }

  private static GitCommandResult commandResult(String output) {
    List<String> err = new ArrayList<String>();
    List<String> out = new ArrayList<String>();
    for (String line : output.split("\n")) {
      if (isError(line)) {
        err.add(line);
      }
      else {
        out.add(line);
      }
    }
    boolean success = err.isEmpty();
    return new GitCommandResult(success, 0, err, out, null);
  }

  private static boolean isError(String s) {
    // we don't want to make that method public, since it is reused only in the test.
    try {
      Method m = GitImpl.class.getDeclaredMethod("isError", String.class);
      m.setAccessible(true);
      return (Boolean) m.invoke(null, s);
    }
    catch (NoSuchMethodException e) {
      e.printStackTrace();
    }
    catch (InvocationTargetException e) {
      e.printStackTrace();
    }
    catch (IllegalAccessException e) {
      e.printStackTrace();
    }
    return true;
  }

  private static void feedOutput(String output, GitLineHandlerListener... listeners) {
    for (GitLineHandlerListener listener : listeners) {
      String[] split = output.split("\n");
      for (String line : split) {
        listener.onLineAvailable(line, ProcessOutputTypes.STDERR);
      }
    }
  }

  private static GitCommandResult execute(GitRepository repository, String operation, GitLineHandlerListener... listeners) {
    return execute(repository.getRoot().getPath(), operation, listeners);
  }

  private static GitCommandResult execute(String workingDir, String operation, GitLineHandlerListener... listeners) {
    cd(workingDir);
    String out = git(operation);
    feedOutput(out, listeners);
    return commandResult(out);
  }

}
