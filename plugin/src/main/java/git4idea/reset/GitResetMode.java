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
package git4idea.reset;

import jakarta.annotation.Nonnull;

public enum GitResetMode {
    SOFT("Soft", "--soft", "Files won't change, differences will be staged for commit."),
    MIXED("Mixed", "--mixed", "Files won't change, differences won't be staged."),
    HARD("Hard", "--hard", "Files will be reverted to the state of the selected commit.<br/>" + "Warning: any local changes will be lost."),
    KEEP("Keep", "--keep", "Files will be reverted to the state of the selected commit,<br/>" + "but local changes will be kept intact.");

    @Nonnull
    private final String myName;
    @Nonnull
    private final String myArgument;
    @Nonnull
    private final String myDescription;

    GitResetMode(@Nonnull String name, @Nonnull String argument, @Nonnull String description) {
        myName = name;
        myArgument = argument;
        myDescription = description;
    }

    @Nonnull
    public static GitResetMode getDefault() {
        return MIXED;
    }

    @Nonnull
    public String getName() {
        return myName;
    }

    @Nonnull
    public String getArgument() {
        return myArgument;
    }

    @Nonnull
    public String getDescription() {
        return myDescription;
    }
}
