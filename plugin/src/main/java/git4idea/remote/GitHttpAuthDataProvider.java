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
package git4idea.remote;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ExtensionAPI;
import consulo.component.extension.ExtensionPointName;
import consulo.credentialStorage.AuthData;
import git4idea.commands.GitHttpAuthenticator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Provides authentication information to the {@link GitHttpAuthenticator} on attempt to connect an HTTP remote.
 * Useful for reusing Github credentials stored in the settings to connect the github remote (IDEA-87530).
 *
 * @author Kirill Likhodedov
 */
@ExtensionAPI(ComponentScope.APPLICATION)
public interface GitHttpAuthDataProvider {
    ExtensionPointName<GitHttpAuthDataProvider> EP_NAME = ExtensionPointName.create(GitHttpAuthDataProvider.class);

    @Nullable
    AuthData getAuthData(@Nonnull String url);

    void forgetPassword(@Nonnull String url);
}
