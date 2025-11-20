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
package git4idea.rebase;

import consulo.platform.Platform;
import consulo.project.Project;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.config.GitConfigUtil;
import git4idea.util.StringScanner;
import jakarta.annotation.Nonnull;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

class GitInteractiveRebaseFile {
    private static final String CYGDRIVE_PREFIX = "/cygdrive/";

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final VirtualFile myRoot;
    @Nonnull
    private final String myFile;

    GitInteractiveRebaseFile(@Nonnull Project project, @Nonnull VirtualFile root, @Nonnull String rebaseFilePath) {
        myProject = project;
        myRoot = root;
        myFile = adjustFilePath(rebaseFilePath);
    }

    @Nonnull
    public List<GitRebaseEntry> load() throws IOException, NoopException {
        String encoding = GitConfigUtil.getLogEncoding(myProject, myRoot);
        List<GitRebaseEntry> entries = new ArrayList<>();
        StringScanner s = new StringScanner(Files.readString(new File(myFile).toPath(), Charset.forName(encoding)));
        boolean noop = false;
        while (s.hasMoreData()) {
            if (s.isEol() || s.startsWith('#')) {
                s.nextLine();
                continue;
            }
            if (s.startsWith("noop")) {
                noop = true;
                s.nextLine();
                continue;
            }
            String action = s.spaceToken();
            String hash = s.spaceToken();
            String comment = s.line();

            entries.add(new GitRebaseEntry(action, hash, comment));
        }
        if (noop && entries.isEmpty()) {
            throw new NoopException();
        }
        return entries;
    }

    public void cancel() throws IOException {
        PrintWriter out = new PrintWriter(new FileWriter(myFile));
        try {
            out.println("# rebase is cancelled");
        }
        finally {
            out.close();
        }
    }

    public void save(@Nonnull List<GitRebaseEntry> entries) throws IOException {
        String encoding = GitConfigUtil.getLogEncoding(myProject, myRoot);
        PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(myFile), encoding));
        try {
            for (GitRebaseEntry e : entries) {
                if (e.getAction() != GitRebaseEntry.Action.skip) {
                    out.println(e.getAction().toString() + " " + e.getCommit() + " " + e.getSubject());
                }
            }
        }
        finally {
            out.close();
        }
    }

    @Nonnull
    private static String adjustFilePath(@Nonnull String file) {
        if (Platform.current().os().isWindows() && file.startsWith(CYGDRIVE_PREFIX)) {
            int prefixSize = CYGDRIVE_PREFIX.length();
            return file.substring(prefixSize, prefixSize + 1) + ":" + file.substring(prefixSize + 1);
        }
        return file;
    }

    static class NoopException extends Exception {
    }
}
