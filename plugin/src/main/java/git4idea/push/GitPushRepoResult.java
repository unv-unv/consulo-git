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
package git4idea.push;

import consulo.util.collection.ContainerUtil;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.update.GitUpdateResult;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Result of pushing one repository.
 * <p>
 * Includes information about the number of pushed commits (or -1 if undefined),
 * and tells whether the repository was updated after the push was rejected.
 *
 * @see GitPushNativeResult
 */
class GitPushRepoResult {
    enum Type {
        SUCCESS,
        NEW_BRANCH,
        UP_TO_DATE,
        FORCED,
        REJECTED_NO_FF,
        REJECTED_OTHER,
        ERROR,
        NOT_PUSHED
    }

    static Comparator<Type> TYPE_COMPARATOR = (o1, o2) -> o1.ordinal() - o2.ordinal();

    @Nonnull
    private final Type myType;
    private final int myCommits;
    @Nonnull
    private final String mySourceBranch;
    @Nonnull
    private final String myTargetBranch;
    @Nonnull
    private final String myTargetRemote;
    @Nonnull
    private final List<String> myPushedTags;
    @Nullable
    private final String myError;
    @Nullable
    private final GitUpdateResult myUpdateResult;

    @Nonnull
    static GitPushRepoResult convertFromNative(
        @Nonnull GitPushNativeResult result,
        @Nonnull List<GitPushNativeResult> tagResults,
        int commits,
        @Nonnull GitLocalBranch source,
        @Nonnull GitRemoteBranch target
    ) {
        List<String> tags = ContainerUtil.map(tagResults, GitPushNativeResult::getSourceRef);
        return new GitPushRepoResult(
            convertType(result),
            commits,
            source.getFullName(),
            target.getFullName(),
            target.getRemote().getName(),
            tags,
            null,
            null
        );
    }

    @Nonnull
    static GitPushRepoResult error(@Nonnull GitLocalBranch source, @Nonnull GitRemoteBranch target, @Nonnull String error) {
        return new GitPushRepoResult(
            Type.ERROR,
            -1,
            source.getFullName(),
            target.getFullName(),
            target.getRemote().getName(),
            Collections.<String>emptyList(),
            error,
            null
        );
    }

    @Nonnull
    static GitPushRepoResult notPushed(GitLocalBranch source, GitRemoteBranch target) {
        return new GitPushRepoResult(
            Type.NOT_PUSHED,
            -1,
            source.getFullName(),
            target.getFullName(),
            target.getRemote().getName(),
            Collections.<String>emptyList(),
            null,
            null
        );
    }

    @Nonnull
    static GitPushRepoResult addUpdateResult(GitPushRepoResult original, GitUpdateResult updateResult) {
        return new GitPushRepoResult(
            original.getType(),
            original.getNumberOfPushedCommits(),
            original.getSourceBranch(),
            original.getTargetBranch(),
            original.getTargetRemote(),
            original.getPushedTags(),
            original.getError(),
            updateResult
        );
    }

    private GitPushRepoResult(
        @Nonnull Type type,
        int pushedCommits,
        @Nonnull String sourceBranch,
        @Nonnull String targetBranch,
        @Nonnull String targetRemote,
        @Nonnull List<String> pushedTags,
        @Nullable String error,
        @Nullable GitUpdateResult result
    ) {
        myType = type;
        myCommits = pushedCommits;
        mySourceBranch = sourceBranch;
        myTargetBranch = targetBranch;
        myTargetRemote = targetRemote;
        myPushedTags = pushedTags;
        myError = error;
        myUpdateResult = result;
    }

    @Nonnull
    Type getType() {
        return myType;
    }

    @Nullable
    GitUpdateResult getUpdateResult() {
        return myUpdateResult;
    }

    int getNumberOfPushedCommits() {
        return myCommits;
    }

    /**
     * Returns the branch we were pushing from, in the full-name format, e.g. {@code refs/heads/master}.
     */
    @Nonnull
    String getSourceBranch() {
        return mySourceBranch;
    }

    /**
     * Returns the branch we were pushing to, in the full-name format, e.g. {@code refs/remotes/origin/master}.
     */
    @Nonnull
    String getTargetBranch() {
        return myTargetBranch;
    }

    @Nullable
    String getError() {
        return myError;
    }

    @Nonnull
    List<String> getPushedTags() {
        return myPushedTags;
    }

    @Nonnull
    public String getTargetRemote() {
        return myTargetRemote;
    }

    @Nonnull
    private static Type convertType(@Nonnull GitPushNativeResult nativeResult) {
        return switch (nativeResult.getType()) {
            case SUCCESS -> Type.SUCCESS;
            case FORCED_UPDATE -> Type.FORCED;
            case NEW_REF -> Type.NEW_BRANCH;
            case REJECTED -> nativeResult.isNonFFUpdate() ? Type.REJECTED_NO_FF : Type.REJECTED_OTHER;
            case UP_TO_DATE -> Type.UP_TO_DATE;
            case ERROR -> Type.ERROR;
            default -> throw new IllegalArgumentException("Conversion is not supported: " + nativeResult.getType());
        };
    }

    @Override
    public String toString() {
        return String.format("%s (%d, '%s'), update: %s}", myType, myCommits, mySourceBranch, myUpdateResult);
    }
}
