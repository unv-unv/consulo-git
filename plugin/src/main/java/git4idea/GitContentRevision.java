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
package git4idea;

import consulo.application.util.SystemInfo;
import consulo.application.util.function.Throwable2Computable;
import consulo.project.Project;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.base.FilePathImpl;
import consulo.versionControlSystem.change.ContentRevision;
import consulo.versionControlSystem.change.ContentRevisionCache;
import consulo.versionControlSystem.change.CurrentContentRevision;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.versionControlSystem.util.VcsFilePathUtil;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.util.GitFileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Git content revision
 */
public class GitContentRevision implements ContentRevision {
  /**
   * the file path
   */
  @Nonnull
  protected final FilePath myFile;
  /**
   * the revision number
   */
  @Nonnull
  protected final GitRevisionNumber myRevision;
  /**
   * the context project
   */
  @Nonnull
  protected final Project myProject;
  /**
   * The charset for the file
   */
  @Nullable
  private Charset myCharset;

  protected GitContentRevision(@Nonnull FilePath file,
                               @Nonnull GitRevisionNumber revision,
                               @Nonnull Project project,
                               @Nullable Charset charset) {
    myProject = project;
    myFile = file;
    myRevision = revision;
    myCharset = charset;
  }

  @Nullable
  public String getContent() throws VcsException {
    if (myFile.isDirectory()) {
      return null;
    }
    try {
      return ContentRevisionCache.getOrLoadAsString(myProject,
                                                    myFile,
                                                    myRevision,
                                                    GitVcs.getKey(),
                                                    ContentRevisionCache.UniqueType.REPOSITORY_CONTENT,
                                                    new Throwable2Computable<byte[], VcsException, IOException>() {
                                                      @Override
                                                      public byte[] compute() throws VcsException, IOException {
                                                        return loadContent();
                                                      }
                                                    },
                                                    myCharset);
    }
    catch (IOException e) {
      throw new VcsException(e);
    }
  }

  private byte[] loadContent() throws VcsException {
    VirtualFile root = GitUtil.getGitRoot(myFile);
    return GitFileUtils.getFileContent(myProject, root, myRevision.getRev(), VcsFileUtil.relativePath(root, myFile));
  }

  @Nonnull
  public FilePath getFile() {
    return myFile;
  }

  @Nonnull
  public VcsRevisionNumber getRevisionNumber() {
    return myRevision;
  }

  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if ((obj == null) || (obj.getClass() != getClass())) {
      return false;
    }

    GitContentRevision test = (GitContentRevision)obj;
    return (myFile.equals(test.myFile) && myRevision.equals(test.myRevision));
  }

  public int hashCode() {
    return myFile.hashCode() + myRevision.hashCode();
  }

  /**
   * Create revision
   *
   * @param vcsRoot        a vcs root for the repository
   * @param path           an path inside with possibly escape sequences
   * @param revisionNumber a revision number, if null the current revision will be created
   * @param project        the context project
   * @param isDeleted      if true, the file is deleted
   * @param unescapePath
   * @return a created revision
   * @throws VcsException if there is a problem with creating revision
   */
  public static ContentRevision createRevision(VirtualFile vcsRoot,
                                               String path,
                                               @Nullable VcsRevisionNumber revisionNumber,
                                               Project project,
                                               boolean isDeleted,
                                               final boolean canBeDeleted,
                                               boolean unescapePath) throws VcsException {
    final FilePath file;
    if (project.isDisposed()) {
      file = new FilePathImpl(new File(makeAbsolutePath(vcsRoot, path, unescapePath)), false);
    }
    else {
      file = createPath(vcsRoot, path, isDeleted, canBeDeleted, unescapePath);
    }
    return createRevision(file, revisionNumber, project);
  }

  private static ContentRevision createRevision(@Nonnull FilePath filePath,
                                                @Nullable VcsRevisionNumber revisionNumber,
                                                @Nonnull Project project) {
    if (revisionNumber != null && revisionNumber != VcsRevisionNumber.NULL) {
      return createRevisionImpl(filePath, (GitRevisionNumber)revisionNumber, project, null);
    }
    else {
      return CurrentContentRevision.create(filePath);
    }
  }

  public static ContentRevision createRevisionForTypeChange(@Nonnull Project project,
                                                            @Nonnull VirtualFile vcsRoot,
                                                            @Nonnull String path,
                                                            @Nullable VcsRevisionNumber revisionNumber,
                                                            boolean unescapePath) throws VcsException {
    final FilePath filePath;
    if (revisionNumber == null) {
      File file = new File(makeAbsolutePath(vcsRoot, path, unescapePath));
      VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
      filePath = virtualFile == null ? new FilePathImpl(file, false) : new FilePathImpl(virtualFile);
    }
    else {
      filePath = createPath(vcsRoot, path, false, false, unescapePath);
    }
    return createRevision(filePath, revisionNumber, project);
  }

  public static FilePath createPath(@Nonnull VirtualFile vcsRoot,
                                    @Nonnull String path,
                                    boolean isDeleted,
                                    boolean canBeDeleted,
                                    boolean unescapePath) throws VcsException {
    final String absolutePath = makeAbsolutePath(vcsRoot, path, unescapePath);
    FilePath file = isDeleted ? VcsUtil.getFilePathForDeletedFile(absolutePath, false) : VcsUtil.getFilePath(absolutePath, false);
    if (canBeDeleted && (!SystemInfo.isFileSystemCaseSensitive) && VcsFilePathUtil.caseDiffers(file.getPath(), absolutePath)) {
      // as for deleted file
      file = FilePathImpl.createForDeletedFile(new File(absolutePath), false);
    }
    return file;
  }

  private static String makeAbsolutePath(@Nonnull VirtualFile vcsRoot, @Nonnull String path, boolean unescapePath) throws VcsException {
    final String unescapedPath = unescapePath ? GitUtil.unescapePath(path) : path;
    return vcsRoot.getPath() + "/" + unescapedPath;
  }

  public static ContentRevision createRevision(@Nonnull final VirtualFile file,
                                               @Nullable final VcsRevisionNumber revisionNumber,
                                               @Nonnull final Project project) {
    return createRevision(file, revisionNumber, project, null);
  }

  public static ContentRevision createRevision(@Nonnull final VirtualFile file,
                                               @Nullable final VcsRevisionNumber revisionNumber,
                                               @Nonnull final Project project,
                                               @Nullable final Charset charset) {
    final FilePathImpl filePath = new FilePathImpl(file);
    return createRevision(filePath, revisionNumber, project, charset);
  }

  public static ContentRevision createRevision(@Nonnull final FilePath filePath,
                                               @Nullable final VcsRevisionNumber revisionNumber,
                                               @Nonnull final Project project,
                                               @Nullable final Charset charset) {
    if (revisionNumber != null && revisionNumber != VcsRevisionNumber.NULL) {
      return createRevisionImpl(filePath, (GitRevisionNumber)revisionNumber, project, charset);
    }
    else {
      return CurrentContentRevision.create(filePath);
    }
  }

  private static GitContentRevision createRevisionImpl(@Nonnull FilePath path,
                                                       @Nonnull GitRevisionNumber revisionNumber,
                                                       @Nonnull Project project,
                                                       @Nullable final Charset charset) {
    if (path.getFileType().isBinary()) {
      return new GitBinaryContentRevision(path, revisionNumber, project);
    }
    else {
      return new GitContentRevision(path, revisionNumber, project, charset);
    }
  }

  @Override
  public String toString() {
    return myFile.getPath();
  }
}
