/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
import consulo.execution.ExecutableValidator;
import consulo.ide.setting.ShowSettingsUtil;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import git4idea.i18n.GitBundle;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import jakarta.annotation.Nonnull;

import java.util.Collections;

/**
 * Project service that is used to check whether currently set git executable is valid (just calls 'git version' and parses the output),
 * and to display notification to the user proposing to fix the project set up.
 *
 * @author Kirill Likhodedov
 */
@Singleton
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitExecutableValidator extends ExecutableValidator {
    @Inject
    public GitExecutableValidator(@Nonnull Project project) {
        super(
            project,
            GitBundle.message("git.executable.notification.title"),
            GitBundle.message("git.executable.notification.description")
        );
    }

    @Override
    protected String getCurrentExecutable() {
        return GitExecutableManager.getInstance().getPathToGit(myProject);
    }

    @Override
    protected void showSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(null, GitVcsConfigurable.class);
    }

    @Override
    public boolean isExecutableValid(@Nonnull String executable) {
        return doCheckExecutable(executable, Collections.singletonList("--version"));
    }

    /**
     * Checks if git executable is valid. If not (which is a common case for low-level vcs exceptions), shows the
     * notification. Otherwise throws the exception.
     * This is to be used in catch-clauses
     *
     * @param e exception which was thrown.
     * @throws VcsException if git executable is valid.
     */
    public void showNotificationOrThrow(VcsException e) throws VcsException {
        if (checkExecutableAndNotifyIfNeeded()) {
            throw e;
        }
    }
}
