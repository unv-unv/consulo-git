/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.change.BinaryContentRevision;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.util.GitFileUtils;

import jakarta.annotation.Nonnull;

/**
 * @author irengrig
 * @since 2010-12-20
 */
public class GitBinaryContentRevision extends GitContentRevision implements BinaryContentRevision {
    public GitBinaryContentRevision(@Nonnull FilePath file, @Nonnull GitRevisionNumber revision, @Nonnull Project project) {
        super(file, revision, project, null);
    }

    @Override
    public byte[] getBinaryContent() throws VcsException {
        if (myFile.isDirectory()) {
            return null;
        }
        final VirtualFile root = GitUtil.getGitRoot(myFile);
        return GitFileUtils.getFileContent(myProject, root, myRevision.getRev(), VcsFileUtil.relativePath(root, myFile));
    }
}
