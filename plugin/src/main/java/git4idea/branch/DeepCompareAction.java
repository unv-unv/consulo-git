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
package git4idea.branch;

import consulo.application.dumb.DumbAware;
import consulo.ide.impl.idea.vcs.log.data.VcsLogBranchFilterImpl;
import consulo.ide.impl.idea.vcs.log.ui.filter.BranchPopupBuilder;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.RelativePoint;
import consulo.ui.ex.action.*;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.ui.ex.popup.ListPopup;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.log.*;
import consulo.versionControlSystem.log.util.VcsLogUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;

public class DeepCompareAction extends ToggleAction implements DumbAware {
  @Override
  public boolean isSelected(AnActionEvent e) {
    Project project = e.getData(Project.KEY);
    VcsLogUi ui = e.getData(VcsLogUi.KEY);
    if (project == null || ui == null) {
      return false;
    }
    return DeepComparator.getInstance(project, ui).hasHighlightingOrInProgress();
  }

  @Nonnull
  @Override
  public ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  @RequiredUIAccess
  @Override
  public void setSelected(AnActionEvent e, boolean selected) {
    Project project = e.getData(Project.KEY);
    final VcsLogUi ui = e.getData(VcsLogUi.KEY);
    final VcsLogDataProvider dataProvider = e.getData(VcsLogDataProvider.KEY);
    if (project == null || ui == null || dataProvider == null) {
      return;
    }
    final DeepComparator dc = DeepComparator.getInstance(project, ui);
    if (selected) {
      VcsLogUtil.triggerUsage(e);

      VcsLogBranchFilter branchFilter = ui.getFilterUi().getFilters().getBranchFilter();
      String singleBranchName = branchFilter != null ? VcsLogUtil.getSingleFilteredBranch(branchFilter, ui.getDataPack().getRefs()) : null;
      if (singleBranchName == null) {
        selectBranchAndPerformAction(ui.getDataPack(), e, selectedBranch -> {
          ui.getFilterUi().setFilter(VcsLogBranchFilterImpl.fromBranch(selectedBranch));
          dc.highlightInBackground(selectedBranch, dataProvider);
        }, getAllVisibleRoots(ui));
        return;
      }
      dc.highlightInBackground(singleBranchName, dataProvider);
    }
    else {
      dc.stopAndUnhighlight();
    }
  }

  private static void selectBranchAndPerformAction(@Nonnull VcsLogDataPack dataPack,
                                                   @Nonnull AnActionEvent event,
                                                   @Nonnull final Consumer<String> consumer,
                                                   @Nonnull Collection<VirtualFile> visibleRoots) {
    ActionGroup actionGroup = new BranchPopupBuilder(dataPack, visibleRoots, null) {
      @Nonnull
      @Override
      protected AnAction createAction(@Nonnull String name) {
        return new DumbAwareAction(LocalizeValue.of(name)) {
          @RequiredUIAccess
          @Override
          public void actionPerformed(@Nonnull AnActionEvent e) {
            consumer.accept(name);
          }
        };
      }
    }.build();
    ListPopup popup = JBPopupFactory.getInstance()
                                    .createActionGroupPopup("Select branch to compare",
                                                            actionGroup,
                                                            event.getDataContext(),
                                                            false,
                                                            false,
                                                            false,
                                                            null,
                                                            -1,
                                                            null);
    InputEvent inputEvent = event.getInputEvent();
    if (inputEvent instanceof MouseEvent) {
      popup.show(new RelativePoint((MouseEvent)inputEvent));
    }
    else {
      popup.showInBestPositionFor(event.getDataContext());
    }
  }

  @Override
  public void update(@Nonnull AnActionEvent e) {
    super.update(e);
    Project project = e.getData(Project.KEY);
    VcsLogUi ui = e.getData(VcsLogUi.KEY);
    e.getPresentation().setEnabledAndVisible(project != null && ui != null &&
                                               hasGitRoots(project, getAllVisibleRoots(ui)));
  }

  private static boolean hasGitRoots(@Nonnull Project project, @Nonnull Set<VirtualFile> roots) {
    final GitRepositoryManager manager = project.getInstance(GitRepositoryManager.class);
    return ContainerUtil.exists(roots, root -> manager.getRepositoryForRoot(root) != null);
  }

  @Nonnull
  private static Set<VirtualFile> getAllVisibleRoots(@Nonnull VcsLogUi ui) {
    return VcsLogUtil.getAllVisibleRoots(ui.getDataPack().getLogProviders().keySet(),
                                         ui.getFilterUi().getFilters().getRootFilter(),
                                         ui.getFilterUi().getFilters().getStructureFilter());
  }
}
