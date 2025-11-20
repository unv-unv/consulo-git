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

import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.history.ShortVcsRevisionNumber;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import jakarta.annotation.Nonnull;

import java.util.Date;
import java.util.StringTokenizer;

/**
 * Git revision number
 */
public class GitRevisionNumber implements ShortVcsRevisionNumber {
    /**
     * the hash from 40 zeros representing not yet created commit
     */
    public static final String NOT_COMMITTED_HASH = StringUtil.repeat("0", 40);

    public static final GitRevisionNumber HEAD = new GitRevisionNumber("HEAD");

    /**
     * the revision number (40 character hashcode, tag, or reference). In some cases incomplete hashcode could be used.
     */
    @Nonnull
    private final String myRevisionHash;
    /**
     * the date when revision created
     */
    @Nonnull
    private final Date myTimestamp;

    private static final Logger LOG = Logger.getInstance(GitRevisionNumber.class);

    /**
     * A constructor from version. The current date is used.
     *
     * @param version the version number.
     */
    public GitRevisionNumber(@Nonnull String version) {
        // TODO review usages
        myRevisionHash = version;
        myTimestamp = new Date();
    }

    /**
     * A constructor from version and time
     *
     * @param version   the version number
     * @param timeStamp the time when the version has been created
     */
    public GitRevisionNumber(@Nonnull String version, @Nonnull Date timeStamp) {
        myTimestamp = timeStamp;
        myRevisionHash = version;
    }

    @Nonnull
    @Override
    public String asString() {
        return myRevisionHash;
    }

    @Override
    public String toShortString() {
        return asString().substring(0, 7);
    }

    /**
     * @return revision time
     */
    @Nonnull
    public Date getTimestamp() {
        return myTimestamp;
    }

    /**
     * @return revision number
     */
    @Nonnull
    public String getRev() {
        return myRevisionHash;
    }

    /**
     * @return the short revision number. The revision number likely unambiguously identify local revision, however in rare cases there could be conflicts.
     */
    @Nonnull
    public String getShortRev() {
        return DvcsUtil.getShortHash(myRevisionHash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(VcsRevisionNumber crev) {
        if (this == crev) {
            return 0;
        }

        if (crev instanceof GitRevisionNumber other) {
            if (myRevisionHash.equals(other.myRevisionHash)) {
                return 0;
            }

            if (other.myRevisionHash.indexOf("[") > 0) {
                return myTimestamp.compareTo(other.myTimestamp);
            }

            // check for parent revs
            String otherName = null;
            String thisName = null;
            int otherParents = -1;
            int thisParent = -1;

            if (other.myRevisionHash.contains("~")) {
                int tildeIndex = other.myRevisionHash.indexOf('~');
                otherName = other.myRevisionHash.substring(0, tildeIndex);
                otherParents = Integer.parseInt(other.myRevisionHash.substring(tildeIndex));
            }

            if (myRevisionHash.contains("~")) {
                int tildeIndex = myRevisionHash.indexOf('~');
                thisName = myRevisionHash.substring(0, tildeIndex);
                thisParent = Integer.parseInt(myRevisionHash.substring(tildeIndex));
            }

            if (otherName == null && thisName == null) {
                int result = myTimestamp.compareTo(other.myTimestamp);
                if (result == 0) {
                    // it can NOT be 0 - it would mean that revisions are equal but they have different hash codes
                    // but this is NOT correct. but we don't know here how to sort
                    return myRevisionHash.compareTo(other.myRevisionHash);
                }
                return result;
            }
            else if (otherName == null) {
                return 1;  // I am an ancestor of the compared revision
            }
            else if (thisName == null) {
                return -1; // the compared revision is my ancestor
            }
            else {
                return thisParent - otherParents;  // higher relative rev numbers are older ancestors
            }
        }

        return -1;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (obj.getClass() != getClass())) {
            return false;
        }

        GitRevisionNumber test = (GitRevisionNumber) obj;
        // TODO normalize revision string?
        return myRevisionHash.equals(test.myRevisionHash);
    }

    @Override
    public int hashCode() {
        return myRevisionHash.hashCode();
    }

    /**
     * @return a revision string that refers to the parent revision relatively
     * to the current one. The git operator "~" is used. Note that in case of merges,
     * the first revision of several will referred.
     */
    public String getParentRevisionStr() {
        String rev = myRevisionHash;
        int bracketIdx = rev.indexOf("[");
        if (bracketIdx > 0) {
            rev = myRevisionHash.substring(bracketIdx + 1, myRevisionHash.indexOf("]"));
        }

        int tildeIndex = rev.indexOf("~");
        if (tildeIndex > 0) {
            int n = Integer.parseInt(rev.substring(tildeIndex)) + 1;
            return rev.substring(0, tildeIndex) + "~" + n;
        }
        return rev + "~1";
    }

    /**
     * Resolve revision number for the specified revision
     *
     * @param project a project
     * @param vcsRoot a vcs root
     * @param rev     a revision expression
     * @return a resolved revision number with correct time
     * @throws VcsException if there is a problem with running git
     */
    @Nonnull
    public static GitRevisionNumber resolve(Project project, VirtualFile vcsRoot, String rev) throws VcsException {
        GitSimpleHandler h = new GitSimpleHandler(project, vcsRoot, GitCommand.REV_LIST);
        h.setSilent(true);
        h.addParameters("--timestamp", "--max-count=1", rev);
        h.endOptions();
        String output = h.run();
        return parseRevlistOutputAsRevisionNumber(h, output);
    }

    @Nonnull
    public static GitRevisionNumber parseRevlistOutputAsRevisionNumber(
        @Nonnull GitSimpleHandler h,
        @Nonnull String output
    ) throws VcsException {
        try {
            StringTokenizer tokenizer = new StringTokenizer(output, "\n\r \t", false);
            LOG.assertTrue(tokenizer.hasMoreTokens(), "No required tokens in the output: \n" + output);
            Date timestamp = GitUtil.parseTimestampWithNFEReport(tokenizer.nextToken(), h, output);
            return new GitRevisionNumber(tokenizer.nextToken(), timestamp);
        }
        catch (Exception e) {
            throw new VcsException("Couldn't parse the output: [" + output + "]", e);
        }
    }

    @Override
    public String toString() {
        return myRevisionHash;
    }
}
