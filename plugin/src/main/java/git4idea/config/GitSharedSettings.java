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

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.component.persist.PersistentStateComponent;
import consulo.component.persist.State;
import consulo.component.persist.Storage;
import consulo.component.persist.StoragePathMacros;
import consulo.util.collection.ContainerUtil;
import jakarta.inject.Singleton;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
@State(name = "GitSharedSettings", storages = @Storage(file = StoragePathMacros.PROJECT_CONFIG_DIR + "/vcs.xml"))
@ServiceAPI(ComponentScope.PROJECT)
@ServiceImpl
public class GitSharedSettings implements PersistentStateComponent<GitSharedSettings.State> {
  public static class State {
    public List<String> FORCE_PUSH_PROHIBITED_PATTERNS = ContainerUtil.newArrayList("master");
  }

  private State myState = new State();

  @Nullable
  @Override
  public State getState() {
    return myState;
  }

  @Override
  public void loadState(State state) {
    myState = state;
  }

  @Nonnull
  public List<String> getForcePushProhibitedPatterns() {
    return Collections.unmodifiableList(myState.FORCE_PUSH_PROHIBITED_PATTERNS);
  }

  public void setForcePushProhibitedPatters(@Nonnull List<String> patterns) {
    myState.FORCE_PUSH_PROHIBITED_PATTERNS = new ArrayList<String>(patterns);
  }

  public boolean isBranchProtected(@Nonnull String branch) {
    // let "master" match only "master" and not "any-master-here" by default
    return getForcePushProhibitedPatterns().stream().anyMatch(pattern -> branch.matches("^" + pattern + "$"));
  }
}
