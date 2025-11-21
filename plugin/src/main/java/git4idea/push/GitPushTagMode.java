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

import consulo.versionControlSystem.distributed.push.VcsPushOptionValue;
import jakarta.annotation.Nonnull;

public final class GitPushTagMode implements VcsPushOptionValue {
    public static final GitPushTagMode ALL = new GitPushTagMode("All", "--tags");
    public static final GitPushTagMode FOLLOW = new GitPushTagMode("Current Branch", "--follow-tags");

    @Nonnull
    private String myTitle;
    @Nonnull
    private String myArgument;

    // for deserialization
    @SuppressWarnings("UnusedDeclaration")
    public GitPushTagMode() {
        this(ALL.getTitle(), ALL.getArgument());
    }

    private GitPushTagMode(@Nonnull String title, @Nonnull String argument) {
        myTitle = title;
        myArgument = argument;
    }

    @Nonnull
    public static GitPushTagMode[] getValues() {
        return new GitPushTagMode[]{
            ALL,
            FOLLOW
        };
    }

    @Nonnull
    public String getTitle() {
        return myTitle;
    }

    @Nonnull
    public String getArgument() {
        return myArgument;
    }

    // for deserialization
    @SuppressWarnings("UnusedDeclaration")
    public void setTitle(@Nonnull String title) {
        myTitle = title;
    }

    // for deserialization
    @SuppressWarnings("UnusedDeclaration")
    public void setArgument(@Nonnull String argument) {
        myArgument = argument;
    }

    @Override
    public boolean equals(Object o) {
        //noinspection SimplifiableIfStatement
        if (this == o) {
            return true;
        }
        return o instanceof GitPushTagMode that
            && myArgument.equals(that.myArgument)
            && myTitle.equals(that.myTitle);
    }

    @Override
    public int hashCode() {
        return myArgument.hashCode() + 31 * myTitle.hashCode();
    }
}
