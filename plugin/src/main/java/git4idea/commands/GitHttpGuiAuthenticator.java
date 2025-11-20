/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package git4idea.commands;

import consulo.application.Application;
import consulo.credentialStorage.AuthData;
import consulo.credentialStorage.AuthenticationData;
import consulo.credentialStorage.PasswordSafe;
import consulo.credentialStorage.ui.AuthDialog;
import consulo.credentialStorage.ui.PasswordSafePromptDialog;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.util.collection.ContainerUtil;
import consulo.util.io.URLUtil;
import consulo.util.io.UriUtil;
import consulo.util.lang.Couple;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.Pair;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ref.SimpleReference;
import git4idea.remote.GitHttpAuthDataProvider;
import git4idea.remote.GitRememberedInputs;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * <p>Handles "ask username" and "ask password" requests from Git:
 * shows authentication dialog in the GUI, waits for user input and returns the credentials supplied by the user.</p>
 * <p>If user cancels the dialog, empty string is returned.</p>
 * <p>If no username is specified in the URL, Git queries for the username and for the password consecutively.
 * In this case to avoid showing dialogs twice, the component asks for both credentials at once,
 * and remembers the password to provide it to the Git process during the next request without requiring user interaction.</p>
 * <p>New instance of the GitAskPassGuiHandler should be created for each session, i. e. for each remote operation call.</p>
 *
 * @author Kirill Likhodedov
 */
class GitHttpGuiAuthenticator implements GitHttpAuthenticator {
    private static final Logger LOG = Logger.getInstance(GitHttpGuiAuthenticator.class);
    private static final Class<GitHttpAuthenticator> PASS_REQUESTER = GitHttpAuthenticator.class;

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final String myTitle;
    @Nonnull
    private final Collection<String> myUrlsFromCommand;

    @Nullable
    private String myPassword;
    @Nullable
    private String myPasswordKey;
    @Nullable
    private String myUnifiedUrl;
    @Nullable
    private String myLogin;
    private boolean mySaveOnDisk;
    @Nullable
    private GitHttpAuthDataProvider myDataProvider;
    private boolean myWasCancelled;

    GitHttpGuiAuthenticator(@Nonnull Project project, @Nonnull GitCommand command, @Nonnull Collection<String> url) {
        myProject = project;
        myTitle = "Git " + StringUtil.capitalize(command.name());
        myUrlsFromCommand = url;
    }

    @Override
    @Nonnull
    public String askPassword(@Nonnull String url) {
        LOG.debug("askPassword. url=" + url + ", passwordKnown=" + (myPassword != null) + ", wasCancelled=" + myWasCancelled);
        if (myPassword != null) {  // already asked in askUsername
            return myPassword;
        }
        if (myWasCancelled) { // already pressed cancel in askUsername
            return "";
        }
        myUnifiedUrl = getUnifiedUrl(url);
        Pair<GitHttpAuthDataProvider, AuthData> authData = findBestAuthData(getUnifiedUrl(url));
        if (authData != null && authData.second.getPassword() != null) {
            String password = authData.second.getPassword();
            myDataProvider = authData.first;
            myPassword = password;
            LOG.debug(
                "askPassword. dataProvider=" + getCurrentDataProviderName() +
                    ", unifiedUrl= " + getUnifiedUrl(url) +
                    ", login=" + authData.second.getLogin() +
                    ", passwordKnown=" + (password != null)
            );
            return password;
        }

        myPasswordKey = getUnifiedUrl(url);
        PasswordSafePromptDialog passwordSafePromptDialog = myProject.getInstance(PasswordSafePromptDialog.class);
        String password = passwordSafePromptDialog.askPassword(
            myTitle,
            "Enter the password for " + getDisplayableUrl(url),
            PASS_REQUESTER,
            myPasswordKey,
            false,
            null
        );
        LOG.debug("askPassword. Password was asked and returned: " + (password == null ? "NULL" : password.isEmpty() ? "EMPTY" : "NOT EMPTY"));
        if (password == null) {
            myWasCancelled = true;
            return "";
        }
        // Password is stored in the safe in PasswordSafePromptDialog.askPassword,
        // but it is not the right behavior (incorrect password is stored too because of that) and should be fixed separately.
        // We store it here manually, to let it work after that behavior is fixed.
        myPassword = password;
        myDataProvider = new GitDefaultHttpAuthDataProvider(); // workaround: askPassword remembers the password even it is not correct
        return password;
    }

    @Nonnull
    @Override
    @RequiredUIAccess
    public String askUsername(@Nonnull String url) {
        myUnifiedUrl = getUnifiedUrl(url);
        Pair<GitHttpAuthDataProvider, AuthData> authData = findBestAuthData(getUnifiedUrl(url));
        String login = null;
        String password = null;
        if (authData != null) {
            login = authData.second.getLogin();
            password = authData.second.getPassword();
            myDataProvider = authData.first;
        }
        LOG.debug("askUsername. dataProvider=" + getCurrentDataProviderName() + ", unifiedUrl= " + getUnifiedUrl(url) +
            ", login=" + login + ", passwordKnown=" + (password != null));
        if (login != null && password != null) {
            myPassword = password;
            return login;
        }

        AuthenticationData data = showAuthDialog(getDisplayableUrl(url), login);
        LOG.debug("askUsername. Showed dialog:" + (data == null ? "OK" : "Cancel"));
        if (data == null) {
            myWasCancelled = true;
            return "";
        }

        // remember values to store in the database afterwards, if authentication succeeds
        myPassword = new String(data.getPassword());
        myLogin = data.getLogin();
        mySaveOnDisk = data.isRememberPassword();
        myPasswordKey = makeKey(myUnifiedUrl, myLogin);

        return myLogin;
    }

    @Nullable
    @RequiredUIAccess
    private AuthenticationData showAuthDialog(String url, String login) {
        SimpleReference<AuthenticationData> dialog = SimpleReference.create();
        Application application = Application.get();
        application.invokeAndWait(
            () -> {
                AuthDialog authDialog = myProject.getInstance(AuthDialog.class);
                AuthenticationData data = authDialog.show(myTitle, "Enter credentials for " + url, login, null, true);
                dialog.set(data);
            },
            application.getAnyModalityState()
        );
        return dialog.get();
    }

    @Override
    public void saveAuthData() {
        // save login and url
        if (myUnifiedUrl != null && myLogin != null) {
            GitRememberedInputs.getInstance().addUrl(myUnifiedUrl, myLogin);
        }

        // save password
        if (myPasswordKey != null && myPassword != null && mySaveOnDisk) {
            PasswordSafe passwordSafe = PasswordSafe.getInstance();
            passwordSafe.storePassword(myProject, PASS_REQUESTER, myPasswordKey, myPassword);
        }
    }

    @Override
    public void forgetPassword() {
        LOG.debug("forgetPassword. dataProvider=" + getCurrentDataProviderName() + ", unifiedUrl=" + myUnifiedUrl);
        if (myDataProvider != null && myUnifiedUrl != null) {
            myDataProvider.forgetPassword(myUnifiedUrl);
        }
    }

    @Nullable
    private String getCurrentDataProviderName() {
        return myDataProvider == null ? null : myDataProvider.getClass().getName();
    }

    @Override
    public boolean wasCancelled() {
        return myWasCancelled;
    }

    /**
     * Get the URL to display to the user in the authentication dialog.
     */
    @Nonnull
    private String getDisplayableUrl(@Nullable String urlFromGit) {
        return !StringUtil.isEmptyOrSpaces(urlFromGit) ? urlFromGit : findPresetHttpUrl();
    }

    /**
     * Get the URL to be used as the authentication data identifier in the password safe and the settings.
     */
    @Nonnull
    private String getUnifiedUrl(@Nullable String urlFromGit) {
        return changeHttpsToHttp(StringUtil.isEmptyOrSpaces(urlFromGit) ? findPresetHttpUrl() : urlFromGit);
    }

    @Nonnull
    private String findPresetHttpUrl() {
        return ObjectUtil.chooseNotNull(
            ContainerUtil.find(
                myUrlsFromCommand,
                url -> {
                    String scheme = UriUtil.splitScheme(url).getFirst();
                    return scheme.startsWith("http");
                }
            ),
            ContainerUtil.getFirstItem(myUrlsFromCommand)
        );
    }

    /**
     * If the url scheme is HTTPS, store it as HTTP in the database, not to make user enter and remember same credentials twice.
     */
    @Nonnull
    private static String changeHttpsToHttp(@Nonnull String url) {
        String prefix = "https";
        if (url.startsWith(prefix)) {
            return "http" + url.substring(prefix.length());
        }
        return url;
    }

    // return the first that knows username + password; otherwise return the first that knows just the username
    @Nullable
    private Pair<GitHttpAuthDataProvider, AuthData> findBestAuthData(@Nonnull String url) {
        Pair<GitHttpAuthDataProvider, AuthData> candidate = null;
        for (GitHttpAuthDataProvider provider : getProviders()) {
            AuthData data = provider.getAuthData(url);
            if (data != null) {
                Pair<GitHttpAuthDataProvider, AuthData> pair = Pair.create(provider, data);
                if (data.getPassword() != null) {
                    return pair;
                }
                if (candidate == null) {
                    candidate = pair;
                }
            }
        }
        return candidate;
    }

    @Nonnull
    private List<GitHttpAuthDataProvider> getProviders() {
        List<GitHttpAuthDataProvider> providers = new ArrayList<>();
        providers.add(new GitDefaultHttpAuthDataProvider());
        providers.addAll(Application.get().getExtensionList(GitHttpAuthDataProvider.class));
        return providers;
    }

    /**
     * Makes the password database key for the URL: inserts the login after the scheme: http://login@url.
     */
    @Nonnull
    private static String makeKey(@Nonnull String url, @Nullable String login) {
        if (login == null) {
            return url;
        }
        Couple<String> pair = UriUtil.splitScheme(url);
        String scheme = pair.getFirst();
        if (!StringUtil.isEmpty(scheme)) {
            return scheme + URLUtil.SCHEME_SEPARATOR + login + "@" + pair.getSecond();
        }
        return login + "@" + url;
    }

    public class GitDefaultHttpAuthDataProvider implements GitHttpAuthDataProvider {

        @Nullable
        @Override
        public AuthData getAuthData(@Nonnull String url) {
            String userName = getUsername(url);
            String key = makeKey(url, userName);
            PasswordSafe passwordSafe = PasswordSafe.getInstance();
            String password = passwordSafe.getPassword(myProject, PASS_REQUESTER, key);
            return new AuthData(StringUtil.notNullize(userName), password);
        }

        @Nullable
        private String getUsername(@Nonnull String url) {
            return GitRememberedInputs.getInstance().getUserNameForUrl(url);
        }

        @Override
        public void forgetPassword(@Nonnull String url) {
            String key = myPasswordKey != null ? myPasswordKey : makeKey(url, getUsername(url));
            PasswordSafe.getInstance().storePassword(myProject, PASS_REQUESTER, key, null);
        }
    }
}
