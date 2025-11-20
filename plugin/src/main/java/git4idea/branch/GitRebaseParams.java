/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package git4idea.branch;

import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import static consulo.util.lang.StringUtil.nullize;
import static java.util.Arrays.asList;

public class GitRebaseParams {
    @Nullable
    private final String myBranch;
    @Nullable
    private final String myNewBase;
    @Nonnull
    private final String myUpstream;
    private final boolean myInteractive;
    private final boolean myPreserveMerges;

    public GitRebaseParams(@Nonnull String upstream) {
        this(null, null, upstream, false, false);
    }

    public GitRebaseParams(
        @Nullable String branch,
        @Nullable String newBase,
        @Nonnull String upstream,
        boolean interactive,
        boolean preserveMerges
    ) {
        myBranch = nullize(branch, true);
        myNewBase = nullize(newBase, true);
        myUpstream = upstream;
        myInteractive = interactive;
        myPreserveMerges = preserveMerges;
    }

    @Nonnull
    public List<String> asCommandLineArguments() {
        List<String> args = new ArrayList<>();
        if (myInteractive) {
            args.add("--interactive");
        }
        if (myPreserveMerges) {
            args.add("--preserve-merges");
        }
        if (myNewBase != null) {
            args.addAll(asList("--onto", myNewBase));
        }
        args.add(myUpstream);
        if (myBranch != null) {
            args.add(myBranch);
        }
        return args;
    }

    @Nullable
    public String getNewBase() {
        return myNewBase;
    }

    @Nonnull
    public String getUpstream() {
        return myUpstream;
    }

    @Override
    public String toString() {
        return StringUtil.join(asCommandLineArguments(), " ");
    }

    public boolean isInteractive() {
        return myInteractive;
    }

    @Nullable
    public String getBranch() {
        return myBranch;
    }
}
