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
package git4idea.config;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.component.persist.*;
import consulo.ide.ServiceManager;
import consulo.project.Project;
import jakarta.annotation.Nonnull;
import jakarta.inject.Singleton;

import jakarta.annotation.Nullable;

/**
 * The application wide settings for the git
 */
@Singleton
@State(
    name = "Git.Application.Settings",
    storages = {
        @Storage(file = StoragePathMacros.APP_CONFIG + "/git.xml", roamingType = RoamingType.PER_OS),
        @Storage(file = StoragePathMacros.APP_CONFIG + "/vcs.xml", deprecated = true),
    }
)
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class GitVcsApplicationSettings implements PersistentStateComponent<GitVcsApplicationSettings.State> {
    private State myState = new State();

    /**
     * Kinds of SSH executable to be used with the git
     */
    public enum SshExecutable {
        IDEA_SSH,
        NATIVE_SSH,
        PUTTY
    }

    public static class State {
        public String myPathToGit = null;
        public SshExecutable SSH_EXECUTABLE = null;
    }

    public static GitVcsApplicationSettings getInstance() {
        return ServiceManager.getService(GitVcsApplicationSettings.class);
    }

    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(State state) {
        myState = state;
    }

    /**
     * @deprecated use {@link #getSavedPathToGit()} to get the path from settings if there's any
     * or use {@link GitExecutableManager#getPathToGit()}/{@link GitExecutableManager#getPathToGit(Project)} to get git executable with
     * auto-detection
     */
    @Nonnull
    @Deprecated
    public String getPathToGit() {
        return GitExecutableManager.getInstance().getPathToGit();
    }

    @Nullable
    public String getSavedPathToGit() {
        return myState.myPathToGit;
    }

    public void setPathToGit(String pathToGit) {
        myState.myPathToGit = pathToGit;
    }

    public void setIdeaSsh(@Nonnull SshExecutable executable) {
        myState.SSH_EXECUTABLE = executable;
    }

    @Nullable
    @Deprecated
    public SshExecutable getIdeaSsh() {
        return getSshExecutableType();
    }

    @Nonnull
    public SshExecutable getSshExecutableType() {
        if (myState.SSH_EXECUTABLE == null) {
            myState.SSH_EXECUTABLE = SshExecutable.IDEA_SSH;
        }
        return myState.SSH_EXECUTABLE;
    }
}
