// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.util;

import consulo.annotation.DeprecationInfo;
import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.document.util.TextRange;
import consulo.execution.ui.console.ConsoleViewContentType;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.util.dataholder.Key;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsConsoleLine;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public final class GitVcsConsoleWriter {
  @Nonnull
  public static GitVcsConsoleWriter getInstance(@Nonnull Project project) {
    return project.getInstance(GitVcsConsoleWriter.class);
  }

  private static final int MAX_CONSOLE_OUTPUT_SIZE = 10000;

  private final Project myProject;

  @Inject
  public GitVcsConsoleWriter(@Nonnull Project project) {
    myProject = project;
  }

  /**
   * Shows a plain message in the Version Control Console.
   */
  public void showMessage(@Nonnull LocalizeValue message) {
    showMessage(message, ConsoleViewContentType.NORMAL_OUTPUT);
  }

  /**
   * Shows a plain message in the Version Control Console.
   */
  public void showMessage(@Nonnull String message) {
    showMessage(message, ConsoleViewContentType.NORMAL_OUTPUT);
  }

  /**
   * Shows error message in the Version Control Console
   */
  public void showErrorMessage(@Nonnull String line) {
    showMessage(line, ConsoleViewContentType.ERROR_OUTPUT);
  }

  /**
   * Shows a command line message in the Version Control Console
   */
  public void showCommandLine(@Nonnull String cmdLine) {
    SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS");
    showMessage(f.format(new Date()) + ": " + cmdLine, ConsoleViewContentType.SYSTEM_OUTPUT);
  }

  /**
   * Show message in the Version Control Console
   *
   * @param message     a message to show
   * @param contentType a style to use
   */
  public void showMessage(@Nonnull LocalizeValue message, @Nonnull ConsoleViewContentType contentType) {
    LocalizeValue shortMessage =
        message.map(value -> StringUtil.shortenPathWithEllipsis(value, MAX_CONSOLE_OUTPUT_SIZE));
    ProjectLevelVcsManager.getInstance(myProject).addMessageToConsoleWindow(shortMessage.get(), contentType);
  }

  /**
   * Show message in the Version Control Console
   *
   * @param message     a message to show
   * @param contentType a style to use
   */
  @Deprecated
  @DeprecationInfo("Use variant with LocalizeValue")
  public void showMessage(@Nonnull String message, @Nonnull ConsoleViewContentType contentType) {
    showMessage(LocalizeValue.of(message), contentType);
  }

  public void showMessage(@Nonnull List<Pair<String, Key>> lineChunks) {
    int totalLength = 0;
    for (Pair<String, Key> chunk : lineChunks) {
      totalLength += chunk.first.length();
    }

    int prefixEnd = (int)(MAX_CONSOLE_OUTPUT_SIZE * 0.3);
    int suffixStart = totalLength - (int)(MAX_CONSOLE_OUTPUT_SIZE * 0.7);
    boolean useEllipsis = totalLength > MAX_CONSOLE_OUTPUT_SIZE * 1.2;

    int index = 0;
    List<Pair<String, ConsoleViewContentType>> messages = new ArrayList<>();
    for (Pair<String, Key> chunk : lineChunks) {
      String message = chunk.first;
      if (message.isEmpty())
        continue;

      ConsoleViewContentType type = ConsoleViewContentType.getConsoleViewType(chunk.second);

      if (useEllipsis) {
        TextRange range = new TextRange(index, index + message.length());

        TextRange range1 = range.intersection(new TextRange(0, prefixEnd));
        TextRange range2 = range.intersection(new TextRange(suffixStart, totalLength));
        if (range1 != null && !range1.isEmpty()) {
          String message1 = range1.shiftLeft(index).substring(message);
          if (!range1.equals(range))
            message1 += "..."; // add ellipsis to the last chunk before the cut
          messages.add(Pair.create(message1, type));
        }
        if (range2 != null && !range2.isEmpty()) {
          String message2 = range2.shiftLeft(index).substring(message);
          messages.add(Pair.create(message2, type));
        }
      }
      else {
        messages.add(Pair.create(message, type));
      }
      index += message.length();
    }
    ProjectLevelVcsManager.getInstance(myProject).addMessageToConsoleWindow(VcsConsoleLine.create(messages));
  }
}
