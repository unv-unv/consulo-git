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

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.project.Project;
import org.jetbrains.git4idea.rt.http.GitAskPassApp;
import org.jetbrains.git4idea.rt.http.GitAskPassXmlRpcHandler;
import org.jetbrains.git4idea.ssh.GitXmlRpcHandlerService;
import org.jetbrains.git4idea.util.ScriptGenerator;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.UUID;

/**
 * Provides the authentication mechanism for Git HTTP connections.
 */
@ServiceAPI(ComponentScope.APPLICATION)
public abstract class GitHttpAuthService extends GitXmlRpcHandlerService<GitHttpAuthenticator> {

  protected GitHttpAuthService() {
    super("intellij-git-askpass", GitAskPassXmlRpcHandler.HANDLER_NAME, GitAskPassApp.class);
  }

  @Override
  protected void customizeScriptGenerator(@Nonnull ScriptGenerator generator) {
  }

  @Nonnull
  @Override
  protected Object createRpcRequestHandlerDelegate() {
    return new InternalRequestHandlerDelegate();
  }

  /**
   * Creates new {@link GitHttpAuthenticator} that will be requested to handle username and password requests from Git.
   */
  @Nonnull
  public abstract GitHttpAuthenticator createAuthenticator(@Nonnull Project project,
                                                           @Nonnull GitCommand command,
                                                           @Nonnull Collection<String> urls);

  /**
   * Internal handler implementation class, it is made public to be accessible via XML RPC.
   */
  public class InternalRequestHandlerDelegate implements GitAskPassXmlRpcHandler {
    @Nonnull
    @Override
    public String askUsername(String token, @Nonnull String url) {
      return getHandler(UUID.fromString(token)).askUsername(url);
    }

    @Nonnull
    @Override
    public String askPassword(String token, @Nonnull String url) {
      return getHandler(UUID.fromString(token)).askPassword(url);
    }
  }

}
