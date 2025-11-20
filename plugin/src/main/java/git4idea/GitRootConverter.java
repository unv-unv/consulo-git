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
package git4idea;

import consulo.versionControlSystem.AbstractVcs;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Given VFS content roots, filters them and returns only those, which are actual Git roots.
 */
public class GitRootConverter implements AbstractVcs.RootsConvertor {
    public static final GitRootConverter INSTANCE = new GitRootConverter();

    @Nonnull
    @Override
    public List<VirtualFile> convertRoots(@Nonnull List<VirtualFile> result) {
        // TODO this should be faster, because it is called rather often. gitRootOrNull could be a bottle-neck.
        List<VirtualFile> roots = new ArrayList<>();
        Set<VirtualFile> listed = new HashSet<>();
        for (VirtualFile f : result) {
            VirtualFile r = GitUtil.gitRootOrNull(f);
            if (r != null && listed.add(r)) {
                roots.add(r);
            }
        }
        return roots;
    }
}
