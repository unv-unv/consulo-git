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

import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import jakarta.annotation.Nonnull;

import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The tag reference object
 */
public class GitTag extends GitReference {
    /**
     * Prefix for tags ({@value})
     */
    public static final String REFS_TAGS_PREFIX = "refs/tags/";

    /**
     * The constructor
     *
     * @param name the used name
     */
    public GitTag(@Nonnull String name) {
        super(name);
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public String getFullName() {
        return REFS_TAGS_PREFIX + myName;
    }

    /**
     * List tags for the git root
     *
     * @param project          the context
     * @param root             the git root
     * @param tags             the tag list
     * @param containingCommit
     * @throws VcsException if there is a problem with running git
     */
    public static void listAsStrings(
        Project project,
        VirtualFile root,
        Collection<String> tags,
        @Nullable String containingCommit
    ) throws VcsException {
        GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.TAG);
        handler.setSilent(true);
        handler.addParameters("-l");
        if (containingCommit != null) {
            handler.addParameters("--contains");
            handler.addParameters(containingCommit);
        }
        for (String line : handler.run().split("\n")) {
            if (line.length() == 0) {
                continue;
            }
            tags.add(new String(line));
        }
    }

    /**
     * List tags for the git root
     *
     * @param project the context
     * @param root    the git root
     * @param tags    the tag list
     * @throws VcsException if there is a problem with running git
     */
    public static void list(Project project, VirtualFile root, Collection<? super GitTag> tags) throws VcsException {
        List<String> temp = new ArrayList<>();
        listAsStrings(project, root, temp, null);
        for (String t : temp) {
            tags.add(new GitTag(t));
        }
    }
}
