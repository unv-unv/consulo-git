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
package git4idea.actions;

import consulo.project.Project;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.action.StandardVcsGroup;
import git4idea.GitVcs;

/**
 * Git VCS menu
 */
public class GitMenu extends StandardVcsGroup {
    @Override
    public AbstractVcs getVcs(Project project) {
        return GitVcs.getInstance(project);
    }

    @Override
    public String getVcsName(final Project project) {
        return GitVcs.NAME;
    }
}