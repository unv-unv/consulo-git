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
package git4idea;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import consulo.ui.image.Image;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Singleton;

/**
 * @author Kirill Likhodedov
 */
@Singleton
public class MessageManager {

  public static MessageManager getInstance(@Nonnull Project project) {
    return ServiceManager.getService(project, MessageManager.class);
  }

  public static int showYesNoDialog(Project project, String description, String title, String yesText, String noText, @Nullable Image icon) {
    return getInstance(project).doShowYesNoDialog(project, description, title, yesText, noText, icon);
  }

  @SuppressWarnings("MethodMayBeStatic")
  protected int doShowYesNoDialog(Project project, String description, String title, String yesText, String noText, @Nullable Image icon) {
    return Messages.showYesNoDialog(project, description, title, yesText, noText, icon);
  }
}
