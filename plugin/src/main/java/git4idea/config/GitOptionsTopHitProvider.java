/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

import consulo.annotation.component.ExtensionImpl;
import consulo.ide.ServiceManager;
import consulo.ide.impl.idea.ide.ui.OptionsTopHitProvider;
import consulo.ide.impl.idea.ide.ui.PublicMethodBasedOptionDescription;
import consulo.ide.impl.idea.ide.ui.search.BooleanOptionDescription;
import consulo.project.Project;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.VcsDescriptor;
import consulo.versionControlSystem.distributed.branch.DvcsSyncSettings;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * @author Sergey.Malenkov
 */
@ExtensionImpl
public final class GitOptionsTopHitProvider extends OptionsTopHitProvider {
    @Override
    public String getId() {
        return "vcs";
    }

    @Nonnull
    @Override
    public Collection<BooleanOptionDescription> getOptions(@Nullable Project project) {
        if (project != null) {
            for (VcsDescriptor descriptor : ProjectLevelVcsManager.getInstance(project).getAllVcss()) {
                if ("Git".equals(descriptor.getDisplayName())) {
                    final GitVcsSettings settings = GitVcsSettings.getInstance(project);
                    ArrayList<BooleanOptionDescription> options = new ArrayList<>();
                    options.add(option(
                        project,
                        "Git: Commit automatically on cherry-pick",
                        "isAutoCommitOnCherryPick",
                        "setAutoCommitOnCherryPick"
                    ));
                    options.add(option(
                        project,
                        "Git: Auto-update if push of the current branch was rejected",
                        "autoUpdateIfPushRejected",
                        "setAutoUpdateIfPushRejected"
                    ));
                    GitRepositoryManager manager = ServiceManager.getService(project, GitRepositoryManager.class);
                    if (manager != null && manager.moreThanOneRoot()) {
                        options.add(new BooleanOptionDescription("Git: Control repositories synchronously", "vcs.Git") {
                            @Override
                            public boolean isOptionEnabled() {
                                return settings.getSyncSetting() == DvcsSyncSettings.Value.SYNC;
                            }

                            @Override
                            public void setOptionState(boolean enabled) {
                                settings.setSyncSetting(enabled ? DvcsSyncSettings.Value.SYNC : DvcsSyncSettings.Value.DONT_SYNC);
                            }
                        });
                    }
                    options.add(option(
                        project,
                        "Git: Warn if CRLF line separators are about to be committed",
                        "warnAboutCrlf",
                        "setWarnAboutCrlf"
                    ));
                    options.add(option(
                        project,
                        "Git: Warn when committing in detached HEAD or during rebase",
                        "warnAboutDetachedHead",
                        "setWarnAboutDetachedHead"
                    ));
                    options.add(option(project, "Git: Allow force push", "isForcePushAllowed", "setForcePushAllowed"));
                    return Collections.unmodifiableCollection(options);
                }
            }
        }
        return Collections.emptyList();
    }

    private static BooleanOptionDescription option(final Project project, String option, String getter, String setter) {
        return new PublicMethodBasedOptionDescription(option, "vcs.Git", getter, setter) {
            @Override
            public Object getInstance() {
                return GitVcsSettings.getInstance(project);
            }
        };
    }
}
