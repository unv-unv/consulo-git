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
package git4idea.commands;

import consulo.localize.LocalizeValue;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import git4idea.GitUtil;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * This class represents the result of a Git command execution.
 *
 * @author Kirill Likhodedov
 */
public class GitCommandResult {
    private final boolean mySuccess;
    private final int myExitCode; // non-zero exit code doesn't necessarily mean an error
    private final List<String> myErrorOutput;
    private final List<String> myOutput;
    @Nullable
    private final Throwable myException;

    public GitCommandResult(
        boolean success,
        int exitCode,
        @Nonnull List<String> errorOutput,
        @Nonnull List<String> output,
        @Nullable Throwable exception
    ) {
        myExitCode = exitCode;
        mySuccess = success;
        myErrorOutput = errorOutput;
        myOutput = output;
        myException = exception;
    }

    @Nonnull
    public static GitCommandResult merge(@Nullable GitCommandResult first, @Nonnull GitCommandResult second) {
        if (first == null) {
            return second;
        }

        int mergedExitCode;
        if (first.myExitCode == 0) {
            mergedExitCode = second.myExitCode;
        }
        else if (second.myExitCode == 0) {
            mergedExitCode = first.myExitCode;
        }
        else {
            mergedExitCode = second.myExitCode; // take exit code of the latest command
        }
        return new GitCommandResult(
            first.success() && second.success(),
            mergedExitCode,
            ContainerUtil.concat(first.myErrorOutput, second.myErrorOutput),
            ContainerUtil.concat(
                first.myOutput,
                second.myOutput
            ),
            ObjectUtil.chooseNotNull(second.myException, first.myException)
        );
    }

    /**
     * @return we think that the operation succeeded
     */
    public boolean success() {
        return mySuccess;
    }

    @Nonnull
    public List<String> getOutput() {
        return Collections.unmodifiableList(myOutput);
    }

    public int getExitCode() {
        return myExitCode;
    }

    @Nonnull
    public List<String> getErrorOutput() {
        return Collections.unmodifiableList(myErrorOutput);
    }

    @Override
    public String toString() {
        return String.format("{%d} %nOutput: %n%s %nError output: %n%s", myExitCode, myOutput, myErrorOutput);
    }

    @Nonnull
    public LocalizeValue getErrorOutputAsHtmlValue() {
        return LocalizeValue.of(getErrorOutputAsHtmlString());
    }

    @Nonnull
    public String getErrorOutputAsHtmlString() {
        return StringUtil.join(cleanup(getErrorOrStdOutput()), "<br/>");
    }

    @Nonnull
    public LocalizeValue getErrorOutputAsJoinedValue() {
        return LocalizeValue.of(getErrorOutputAsJoinedString());
    }

    @Nonnull
    public String getErrorOutputAsJoinedString() {
        return StringUtil.join(cleanup(getErrorOrStdOutput()), "\n");
    }

    // in some cases operation fails but no explicit error messages are given, in this case return the output to display something to user
    @Nonnull
    private List<String> getErrorOrStdOutput() {
        return myErrorOutput.isEmpty() && !success() ? myOutput : myErrorOutput;
    }

    @Nonnull
    public String getOutputAsJoinedString() {
        return StringUtil.join(myOutput, "\n");
    }

    @Nullable
    public Throwable getException() {
        return myException;
    }

    @Nonnull
    public static GitCommandResult error(@Nonnull String error) {
        return new GitCommandResult(false, 1, Collections.singletonList(error), Collections.<String>emptyList(), null);
    }

    public boolean cancelled() {
        return false; // will be implemented later
    }

    @Nonnull
    private static Collection<String> cleanup(@Nonnull Collection<String> errorOutput) {
        return ContainerUtil.map(errorOutput, GitUtil::cleanupErrorPrefixes);
    }

    /**
     * Check if execution was successful and return textual result or throw exception
     *
     * @return result of {@link #getOutputAsJoinedString()}
     * @throws VcsException with message from {@link #getErrorOutputAsJoinedValue()}
     */
    @Nonnull
    public String getOutputOrThrow() throws VcsException {
        if (!success()) {
            throw new VcsException(getErrorOutputAsJoinedValue());
        }
        return getOutputAsJoinedString();
    }
}
