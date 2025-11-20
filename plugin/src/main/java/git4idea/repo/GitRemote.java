/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.repo;

import consulo.util.collection.ContainerUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * Holds information about a remote in Git repository.
 * </p>
 * <p/>
 * <p>
 * Git remote as defined in {@code .git/config} may contain url(s) and pushUrl(s). <br/>
 * If no pushUrl is given, then url is used to fetch and to push. Otherwise url is used to fetch, pushUrl is used to push.
 * If there are several urls and no pushUrls, then 1 url is used to fetch (because it is not possible and makes no sense to fetch
 * several urls at once), and all urls are used to push. If there are several urls and at least one pushUrl, then only pushUrl(s)
 * are used to push.
 * There are also some rules about url substitution, like {@code url.<base>.insteadOf}.
 * </p>
 * <p>
 * GitRemote instance constructed by {@link GitConfig#read(File)}} has all these rules applied.
 * Thus, for example, if only one {@code url} and no {@code pushUrls} are defined for the remote,
 * both {@link #getUrls()} and {@link #getPushUrls()} will return this url. <br/>
 * This is made to avoid urls transformation logic from the code using GitRemote, leaving it all in GitConfig parsing.
 * </p>
 * <p>
 * Same applies to fetch and push specs: {@link #getPushRefSpecs()} returns the spec,
 * even if there are no separate record in {@code .git/config}
 * </p>
 * <p/>
 * <p>
 * NB: Not all remote preferences (defined in {@code .git/config} are stored in the object.
 * If some additional data is needed, add the field, getter, constructor parameter and populate it in {@link GitConfig}.
 * </p>
 * <p/>
 * <p>Remotes are compared (via equals, hashcode and compareTo) only by names.</p>
 *
 * @author Kirill Likhodedov
 */
public final class GitRemote implements Comparable<GitRemote> {
    /**
     * This is a special instance of GitRemote used in typical git-svn configurations like:
     * [branch "trunk"]
     * remote = .
     * merge = refs/remotes/git-svn
     */
    public static final GitRemote DOT = new GitRemote(".", Collections.singletonList("."), Collections.<String>emptyList(),
        Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String>emptyList()
    );

    /**
     * Default remote name in Git is "origin".
     * Usually all Git repositories have an "origin" remote, so it can be used as a default value in some cases.
     */
    public static final String ORIGIN_NAME = "origin";

    @Nonnull
    private final String myName;
    @Nonnull
    private final List<String> myUrls;
    @Nonnull
    private final Collection<String> myPushUrls;
    @Nonnull
    final List<String> myFetchRefSpecs;
    @Nonnull
    private final List<String> myPushRefSpecs;
    @Nonnull
    private final Collection<String> myPuttyKeyFiles;

    GitRemote(
        @Nonnull String name, @Nonnull List<String> urls, @Nonnull Collection<String> pushUrls, @Nonnull List<String> fetchRefSpecs,
        @Nonnull List<String> pushRefSpecs, @Nonnull Collection<String> puttyKeyFiles
    ) {
        myName = name;
        myUrls = urls;
        myPushUrls = pushUrls;
        myFetchRefSpecs = fetchRefSpecs;
        myPushRefSpecs = pushRefSpecs;
        myPuttyKeyFiles = puttyKeyFiles;
    }

    @Nonnull
    public String getName() {
        return myName;
    }

    /**
     * Returns all urls specified in gitconfig in {@code remote.<name>.url}.
     * If you need url to fetch, use {@link #getFirstUrl()}, because only the first url is fetched by Git,
     * others are ignored.
     */
    @Nonnull
    public List<String> getUrls() {
        return myUrls;
    }

    /**
     * @return the first url (to fetch) or null if and only if there are no urls defined for the remote.
     */
    @Nullable
    public String getFirstUrl() {
        return myUrls.isEmpty() ? null : myUrls.get(0);
    }

    @Nonnull
    public Collection<String> getPushUrls() {
        return myPushUrls;
    }

    @Nonnull
    public List<String> getFetchRefSpecs() {
        return myFetchRefSpecs;
    }

    @Nonnull
    public List<String> getPushRefSpecs() {
        return myPushRefSpecs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GitRemote gitRemote = (GitRemote) o;
        return myName.equals(gitRemote.myName);

        // other parameters don't count: remotes are equal if their names are equal
        // TODO: LOG.warn if other parameters differ
    }

    @Override
    public int hashCode() {
        return myName.hashCode();
    }

    @Override
    public String toString() {
        return String.format("GitRemote{myName='%s', myUrls=%s, myPushUrls=%s, myFetchRefSpec='%s', myPushRefSpec='%s'}", myName, myUrls,
            myPushUrls, myFetchRefSpecs, myPushRefSpecs
        );
    }

    @Override
    public int compareTo(@Nonnull GitRemote o) {
        return getName().compareTo(o.getName());
    }

    @Nullable
    public String getPuttyKeyFile() {
        return ContainerUtil.getFirstItem(myPuttyKeyFiles);
    }
}
