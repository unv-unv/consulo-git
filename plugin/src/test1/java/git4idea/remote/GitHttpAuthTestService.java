/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

import consulo.project.Project;
import consulo.ui.ModalityState;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;

import git4idea.commands.GitCommand;
import git4idea.commands.GitHttpAuthService;
import git4idea.commands.GitHttpAuthenticator;

/**
 * @author Kirill Likhodedov
 */
@Singleton
public class GitHttpAuthTestService extends GitHttpAuthService {

  @Nonnull
  private GitHttpAuthenticator myAuthenticator = new GitHttpAuthenticator() {
    @Nonnull
    @Override
    public String askPassword(@Nonnull String url) {
      throw new IllegalStateException("Authenticator was not registered");
    }

    @Nonnull
    @Override
    public String askUsername(@Nonnull String url) {
      throw new IllegalStateException("Authenticator was not registered");
    }

    @Override
    public void saveAuthData() {
      throw new IllegalStateException("Authenticator was not registered");
    }

    @Override
    public void forgetPassword() {
      throw new IllegalStateException("Authenticator was not registered");
    }
  };

  @Nonnull
  @Override
  public GitHttpAuthenticator createAuthenticator(@Nonnull Project project, @Nullable ModalityState state, @Nonnull GitCommand command,
                                                  @Nonnull String url) {
    return myAuthenticator;
  }

  public void register(@Nonnull GitHttpAuthenticator authenticator) {
    myAuthenticator = authenticator;
  }

}
