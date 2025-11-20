/*
 * Copyright 2000-2011 JetBrains s.r.o.
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

import consulo.logging.Logger;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.branch.GitBranchUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.ini4j.Ini;
import org.ini4j.Profile;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads information from the {@code .git/config} file, and parses it to actual objects.
 *
 * <p>Currently doesn't read all the information: just general information about remotes and branch tracking.</p>
 *
 * <p>Parsing is performed with the help of <a href="http://ini4j.sourceforge.net/">ini4j</a> library.</p>
 *
 * <p>TODO: note, that other git configuration files (such as ~/.gitconfig) are not handled yet.</p>
 */
public class GitConfig {
    private static final Logger LOG = Logger.getInstance(GitConfig.class);

    private static final Pattern REMOTE_SECTION = Pattern.compile("(?:svn-)?remote \"(.*)\"");
    private static final Pattern URL_SECTION = Pattern.compile("url \"(.*)\"");
    private static final Pattern BRANCH_INFO_SECTION = Pattern.compile("branch \"(.*)\"");
    private static final Pattern BRANCH_COMMON_PARAMS_SECTION = Pattern.compile("branch");

    @Nonnull
    private final Collection<Remote> myRemotes;
    @Nonnull
    private final Collection<Url> myUrls;
    @Nonnull
    private final Collection<BranchConfig> myTrackedInfos;


    private GitConfig(@Nonnull Collection<Remote> remotes, @Nonnull Collection<Url> urls, @Nonnull Collection<BranchConfig> trackedInfos) {
        myRemotes = remotes;
        myUrls = urls;
        myTrackedInfos = trackedInfos;
    }

    /**
     * <p>Returns Git remotes defined in {@code .git/config}.</p>
     *
     * <p>Remote is returned with all transformations (such as {@code pushUrl, url.<base>.insteadOf}) already applied to it.
     * See {@link GitRemote} for details.</p>
     *
     * <p><b>Note:</b> remotes can be defined separately in {@code .git/remotes} directory, by creating a file for each remote with
     * remote parameters written in the file. This method returns ONLY remotes defined in {@code .git/config}.</p>
     *
     * @return Git remotes defined in {@code .git/config}.
     */
    @Nonnull
    Collection<GitRemote> parseRemotes() {
        // populate GitRemotes with substituting urls when needed
        return ContainerUtil.map(
            myRemotes,
            remote -> {
                assert remote != null;
                return convertRemoteToGitRemote(myUrls, remote);
            }
        );
    }

    @Nonnull
    private static GitRemote convertRemoteToGitRemote(@Nonnull Collection<Url> urls, @Nonnull Remote remote) {
        UrlsAndPushUrls substitutedUrls = substituteUrls(urls, remote);
        return new GitRemote(
            remote.myName,
            substitutedUrls.getUrls(),
            substitutedUrls.getPushUrls(),
            remote.getFetchSpecs(),
            remote.getPushSpec(),
            remote.getPuttyKeys()
        );
    }

    /**
     * Create branch tracking information based on the information defined in {@code .git/config}.
     */
    @Nonnull
    Collection<GitBranchTrackInfo> parseTrackInfos(
        @Nonnull Collection<GitLocalBranch> localBranches,
        @Nonnull Collection<GitRemoteBranch> remoteBranches
    ) {
        return ContainerUtil.mapNotNull(
            myTrackedInfos,
            config -> {
                if (config != null) {
                    return convertBranchConfig(config, localBranches, remoteBranches);
                }
                return null;
            }
        );
    }

    /**
     * Creates an instance of GitConfig by reading information from the specified {@code .git/config} file.
     *
     * <p>If some section is invalid, it is skipped, and a warning is reported.</p>
     */
    @Nonnull
    static GitConfig read(@Nonnull File configFile) {
        GitConfig emptyConfig =
            new GitConfig(Collections.<Remote>emptyList(), Collections.<Url>emptyList(), Collections.<BranchConfig>emptyList());
        if (!configFile.exists()) {
            LOG.info("No .git/config file at " + configFile.getPath());
            return emptyConfig;
        }

        Ini ini;
        try {
            ini = GitConfigHelper.loadIniFile(configFile);
        }
        catch (IOException e) {
            return emptyConfig;
        }

        Pair<Collection<Remote>, Collection<Url>> remotesAndUrls = parseRemotes(ini, GitConfig.class.getClassLoader());
        Collection<BranchConfig> trackedInfos = parseTrackedInfos(ini, GitConfig.class.getClassLoader());

        return new GitConfig(remotesAndUrls.getFirst(), remotesAndUrls.getSecond(), trackedInfos);
    }

    @Nonnull
    private static Collection<BranchConfig> parseTrackedInfos(@Nonnull Ini ini, @Nonnull ClassLoader classLoader) {
        Collection<BranchConfig> configs = new ArrayList<>();
        for (Map.Entry<String, Profile.Section> stringSectionEntry : ini.entrySet()) {
            String sectionName = stringSectionEntry.getKey();
            Profile.Section section = stringSectionEntry.getValue();
            if (sectionName.startsWith("branch")) {
                BranchConfig branchConfig = parseBranchSection(sectionName, section, classLoader);
                if (branchConfig != null) {
                    configs.add(branchConfig);
                }
            }
        }
        return configs;
    }

    @Nullable
    private static GitBranchTrackInfo convertBranchConfig(
        @Nullable BranchConfig branchConfig,
        @Nonnull Collection<GitLocalBranch> localBranches,
        @Nonnull Collection<GitRemoteBranch> remoteBranches
    ) {
        if (branchConfig == null) {
            return null;
        }
        String branchName = branchConfig.getName();
        String remoteName = branchConfig.getBean().getRemote();
        String mergeName = branchConfig.getBean().getMerge();
        String rebaseName = branchConfig.getBean().getRebase();

        if (StringUtil.isEmptyOrSpaces(mergeName) && StringUtil.isEmptyOrSpaces(rebaseName)) {
            LOG.info("No branch." + branchName + ".merge/rebase item in the .git/config");
            return null;
        }
        if (StringUtil.isEmptyOrSpaces(remoteName)) {
            LOG.info("No branch." + branchName + ".remote item in the .git/config");
            return null;
        }

        boolean merge = mergeName != null;
        String remoteBranchName = StringUtil.unquoteString(merge ? mergeName : rebaseName);

        GitLocalBranch localBranch = findLocalBranch(branchName, localBranches);
        GitRemoteBranch remoteBranch = findRemoteBranch(remoteBranchName, remoteName, remoteBranches);
        if (localBranch == null || remoteBranch == null) {
            // obsolete record in .git/config: local or remote branch doesn't exist, but the tracking information wasn't removed
            LOG.debug("localBranch: " + localBranch + ", remoteBranch: " + remoteBranch);
            return null;
        }
        return new GitBranchTrackInfo(localBranch, remoteBranch, merge);
    }

    @Nullable
    private static GitLocalBranch findLocalBranch(@Nonnull String branchName, @Nonnull Collection<GitLocalBranch> localBranches) {
        String name = GitBranchUtil.stripRefsPrefix(branchName);
        return ContainerUtil.find(
            localBranches,
            input -> {
                assert input != null;
                return input.getName().equals(name);
            }
        );
    }

    @Nullable
    public static GitRemoteBranch findRemoteBranch(
        @Nonnull String remoteBranchName,
        @Nonnull String remoteName,
        @Nonnull Collection<GitRemoteBranch> remoteBranches
    ) {
        String branchName = GitBranchUtil.stripRefsPrefix(remoteBranchName);
        return ContainerUtil.find(
            remoteBranches,
            branch -> branch.getNameForRemoteOperations().equals(branchName)
                && branch.getRemote().getName().equals(remoteName)
        );
    }

    @Nullable
    private static BranchConfig parseBranchSection(String sectionName, Profile.Section section, @Nonnull ClassLoader classLoader) {
        BranchBean branchBean = section.as(BranchBean.class, classLoader);
        Matcher matcher = BRANCH_INFO_SECTION.matcher(sectionName);
        if (matcher.matches()) {
            return new BranchConfig(matcher.group(1), branchBean);
        }
        if (BRANCH_COMMON_PARAMS_SECTION.matcher(sectionName).matches()) {
            LOG.debug(String.format("Common branch option(s) defined .git/config. sectionName: %s%n section: %s", sectionName, section));
            return null;
        }
        LOG.error(String.format("Invalid branch section format in .git/config. sectionName: %s%n section: %s", sectionName, section));
        return null;
    }

    @Nonnull
    private static Pair<Collection<Remote>, Collection<Url>> parseRemotes(@Nonnull Ini ini, @Nonnull ClassLoader classLoader) {
        Collection<Remote> remotes = new ArrayList<>();
        Collection<Url> urls = new ArrayList<>();
        for (Map.Entry<String, Profile.Section> stringSectionEntry : ini.entrySet()) {
            String sectionName = stringSectionEntry.getKey();
            Profile.Section section = stringSectionEntry.getValue();

            Remote remote = parseRemoteSection(sectionName, section, classLoader);
            if (remote != null) {
                remotes.add(remote);
            }
            else {
                Url url = parseUrlSection(sectionName, section, classLoader);
                if (url != null) {
                    urls.add(url);
                }
            }
        }
        return Pair.create(remotes, urls);
    }

    /**
     * <p>Applies {@code url.<base>.insteadOf} and {@code url.<base>.pushInsteadOf} transformations to {@code url} and {@code pushUrl} of
     * the given remote.</p>
     *
     * <p>The logic, is as follows:
     * <ul>
     * <li>If remote.url starts with url.insteadOf, it it substituted.</li>
     * <li>If remote.pushUrl starts with url.insteadOf, it is substituted.</li>
     * <li>If remote.pushUrl starts with url.pushInsteadOf, it is not substituted.</li>
     * <li>If remote.url starts with url.pushInsteadOf, but remote.pushUrl is given, additional push url is not added.</li>
     * </ul></p>
     *
     * <p>
     * TODO: if there are several matches in url sections, the longest should be applied. // currently only one is applied
     * </p>
     *
     * <p> * This is according to {@code man git-config ("url.<base>.insteadOf" and "url.<base>.pushInsteadOf" sections},
     * {@code man git-push ("URLS" section)} and the following discussions in the Git mailing list:
     * <a href="http://article.gmane.org/gmane.comp.version-control.git/183587">insteadOf override urls and pushUrls</a>,
     * <a href="http://thread.gmane.org/gmane.comp.version-control.git/127910">pushInsteadOf doesn't override explicit pushUrl</a>.</p>
     */
    @Nonnull
    private static UrlsAndPushUrls substituteUrls(@Nonnull Collection<Url> urlSections, @Nonnull Remote remote) {
        List<String> urls = new ArrayList<>(remote.getUrls().size());
        Collection<String> pushUrls = new ArrayList<>();

        // urls are substituted by insteadOf
        // if there are no pushUrls, we create a pushUrl for pushInsteadOf substitutions
        for (String remoteUrl : remote.getUrls()) {
            boolean substituted = false;
            for (Url url : urlSections) {
                String insteadOf = url.getInsteadOf();
                String pushInsteadOf = url.getPushInsteadOf();
                // null means no entry, i.e. nothing to substitute. Empty string means substituting everything
                if (insteadOf != null && remoteUrl.startsWith(insteadOf)) {
                    urls.add(substituteUrl(remoteUrl, url, insteadOf));
                    substituted = true;
                    break;
                }
                else if (pushInsteadOf != null && remoteUrl.startsWith(pushInsteadOf)) {
                    if (remote.getPushUrls().isEmpty()) { // only if there are no explicit pushUrls
                        pushUrls.add(substituteUrl(remoteUrl, url, pushInsteadOf)); // pushUrl is different
                    }
                    urls.add(remoteUrl);                                             // but url is left intact
                    substituted = true;
                    break;
                }
            }
            if (!substituted) {
                urls.add(remoteUrl);
            }
        }

        // pushUrls are substituted only by insteadOf, not by pushInsteadOf
        for (String remotePushUrl : remote.getPushUrls()) {
            boolean substituted = false;
            for (Url url : urlSections) {
                String insteadOf = url.getInsteadOf();
                // null means no entry, i.e. nothing to substitute. Empty string means substituting everything
                if (insteadOf != null && remotePushUrl.startsWith(insteadOf)) {
                    pushUrls.add(substituteUrl(remotePushUrl, url, insteadOf));
                    substituted = true;
                    break;
                }
            }
            if (!substituted) {
                pushUrls.add(remotePushUrl);
            }
        }

        // if no pushUrls are explicitly defined yet via pushUrl or url.<base>.pushInsteadOf, they are the same as urls.
        if (pushUrls.isEmpty()) {
            pushUrls = new ArrayList<>(urls);
        }

        return new UrlsAndPushUrls(urls, pushUrls);
    }

    private static class UrlsAndPushUrls {
        final List<String> myUrls;
        final Collection<String> myPushUrls;

        private UrlsAndPushUrls(List<String> urls, Collection<String> pushUrls) {
            myPushUrls = pushUrls;
            myUrls = urls;
        }

        public Collection<String> getPushUrls() {
            return myPushUrls;
        }

        public List<String> getUrls() {
            return myUrls;
        }
    }

    @Nonnull
    private static String substituteUrl(@Nonnull String remoteUrl, @Nonnull Url url, @Nonnull String insteadOf) {
        return url.myName + remoteUrl.substring(insteadOf.length());
    }

    @Nullable
    private static Remote parseRemoteSection(
        @Nonnull String sectionName,
        @Nonnull Profile.Section section,
        @Nonnull ClassLoader classLoader
    ) {
        Matcher matcher = REMOTE_SECTION.matcher(sectionName);
        if (matcher.matches() && matcher.groupCount() == 1) {
            return new Remote(matcher.group(1), section.as(RemoteBean.class, classLoader));
        }
        return null;
    }

    @Nullable
    private static Url parseUrlSection(@Nonnull String sectionName, @Nonnull Profile.Section section, @Nonnull ClassLoader classLoader) {
        Matcher matcher = URL_SECTION.matcher(sectionName);
        if (matcher.matches() && matcher.groupCount() == 1) {
            return new Url(matcher.group(1), section.as(UrlBean.class, classLoader));
        }
        return null;
    }

    private static class Remote {
        private final String myName;
        private final RemoteBean myRemoteBean;

        private Remote(@Nonnull String name, @Nonnull RemoteBean remoteBean) {
            myRemoteBean = remoteBean;
            myName = name;
        }

        @Nonnull
        private Collection<String> getUrls() {
            return nonNullCollection(myRemoteBean.getUrl());
        }

        @Nonnull
        private Collection<String> getPushUrls() {
            return nonNullCollection(myRemoteBean.getPushUrl());
        }

        @Nonnull
        private Collection<String> getPuttyKeys() {
            return nonNullCollection(myRemoteBean.getPuttyKeyFile());
        }

        @Nonnull
        private List<String> getPushSpec() {
            String[] push = myRemoteBean.getPush();
            return push == null ? Collections.<String>emptyList() : Arrays.asList(push);
        }

        @Nonnull
        private List<String> getFetchSpecs() {
            return Arrays.asList(notNull(myRemoteBean.getFetch()));
        }
    }

    private interface RemoteBean {
        @Nullable
        String[] getFetch();

        @Nullable
        String[] getPush();

        @Nullable
        String[] getUrl();

        @Nullable
        String[] getPushUrl();

        @Nullable
        String[] getPuttyKeyFile();
    }

    private static class Url {
        private final String myName;
        private final UrlBean myUrlBean;

        private Url(String name, UrlBean urlBean) {
            myUrlBean = urlBean;
            myName = name;
        }

        @Nullable
        // null means to entry, i.e. nothing to substitute. Empty string means substituting everything
        public String getInsteadOf() {
            return myUrlBean.getInsteadOf();
        }

        @Nullable
        // null means to entry, i.e. nothing to substitute. Empty string means substituting everything
        public String getPushInsteadOf() {
            return myUrlBean.getPushInsteadOf();
        }
    }

    private interface UrlBean {
        @Nullable
        String getInsteadOf();

        @Nullable
        String getPushInsteadOf();
    }

    private static class BranchConfig {
        private final String myName;
        private final BranchBean myBean;

        public BranchConfig(String name, BranchBean bean) {
            myName = name;
            myBean = bean;
        }

        public String getName() {
            return myName;
        }

        public BranchBean getBean() {
            return myBean;
        }
    }

    private interface BranchBean {
        @Nullable
        String getRemote();

        @Nullable
        String getMerge();

        @Nullable
        String getRebase();
    }

    @Nonnull
    private static String[] notNull(@Nullable String[] s) {
        return s == null ? ArrayUtil.EMPTY_STRING_ARRAY : s;
    }

    @Nonnull
    private static Collection<String> nonNullCollection(@Nullable String[] array) {
        return array == null ? Collections.<String>emptyList() : new ArrayList<>(Arrays.asList(array));
    }
}