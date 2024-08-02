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
package git4idea.util;

import consulo.project.Project;
import consulo.versionControlSystem.change.VcsFreezingProcess;

import jakarta.annotation.Nonnull;

/**
 * Executes an action surrounding it with freezing-unfreezing of the ChangeListManager
 * and blocking/unblocking save/sync on frame de/activation.
 */
public class GitFreezingProcess extends VcsFreezingProcess {
  public GitFreezingProcess(@Nonnull Project project, @Nonnull String operationTitle, @Nonnull Runnable runnable) {
    super(project, "Local changes are not available until Git " + operationTitle + " is finished.", runnable);
  }
}
