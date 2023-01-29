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
package git4idea.update;

import consulo.application.ApplicationManager;
import consulo.application.CommonBundle;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.ex.awt.Messages;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.ref.Ref;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.log.TimedVcsCommit;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.DialogManager;
import git4idea.history.GitHistoryUtils;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.function.Consumer;

public class GitRebaseOverMergeProblem {
  private static final Logger LOG = Logger.getInstance(GitRebaseOverMergeProblem.class);
  public static final String DESCRIPTION = "You are about to rebase merge commits. \n" +
    "This can lead to duplicate commits in history, or even data loss.\n" +
    "It is recommended to merge instead of rebase in this case.";

  public enum Decision {
    MERGE_INSTEAD("Merge"),
    REBASE_ANYWAY("Rebase Anyway"),
    CANCEL_OPERATION(CommonBundle.getCancelButtonText());

    private final String myButtonText;

    Decision(@Nonnull String buttonText) {
      myButtonText = buttonText;
    }

    @Nonnull
    private static String[] getButtonTitles() {
      return ContainerUtil.map2Array(values(), String.class, decision -> decision.myButtonText);
    }

    @Nonnull
    public static Decision getOption(final int index) {
      return ObjectUtil.assertNotNull(ContainerUtil.find(values(), decision -> decision.ordinal() == index));
    }

    private static int getDefaultButtonIndex() {
      return MERGE_INSTEAD.ordinal();
    }

    private static int getFocusedButtonIndex() {
      return CANCEL_OPERATION.ordinal();
    }
  }

  public static boolean hasProblem(@Nonnull Project project,
                                   @Nonnull VirtualFile root,
                                   @Nonnull String baseRef,
                                   @Nonnull String currentRef) {
    final Ref<Boolean> mergeFound = Ref.create(Boolean.FALSE);
    Consumer<TimedVcsCommit> detectingConsumer = commit -> mergeFound.set(true);

    String range = baseRef + ".." + currentRef;
    try {
      GitHistoryUtils.readCommits(project, root, Arrays.asList(range, "--merges"), e -> {
                                  },
                                  e -> {
                                  }, detectingConsumer);
    }
    catch (VcsException e) {
      LOG.warn("Couldn't get git log --merges " + range, e);
    }
    return mergeFound.get();
  }

  @Nonnull
  public static Decision showDialog() {
    final Ref<Decision> decision = Ref.create();
    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        decision.set(doShowDialog());
      }
    }, ApplicationManager.getApplication().getDefaultModalityState());
    return decision.get();
  }

  @Nonnull
  private static Decision doShowDialog() {
    int decision = DialogManager.showMessage(DESCRIPTION,
                                             "Rebasing Merge Commits",
                                             Decision.getButtonTitles(),
                                             Decision.getDefaultButtonIndex(),
                                             Decision.getFocusedButtonIndex(),
                                             Messages.getWarningIcon(),
                                             null);
    return Decision.getOption(decision);
  }
}
