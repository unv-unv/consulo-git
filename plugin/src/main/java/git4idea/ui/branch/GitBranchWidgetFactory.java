package git4idea.ui.branch;

import consulo.annotation.component.ExtensionImpl;
import consulo.disposer.Disposer;
import consulo.project.Project;
import consulo.project.ui.wm.StatusBar;
import consulo.project.ui.wm.StatusBarWidget;
import consulo.project.ui.wm.StatusBarWidgetFactory;
import git4idea.i18n.GitBundle;

import javax.annotation.Nonnull;

@ExtensionImpl(id = "gitWidget", order = "after codeStyleWidget,before readOnlyWidget")
public class GitBranchWidgetFactory implements StatusBarWidgetFactory {
  @Override
  @Nonnull
  public String getId() {
    return GitBranchWidget.ID;
  }

  @Override
  @Nonnull
  public String getDisplayName() {
    return GitBundle.message("git.status.bar.widget.name");
  }

  @Override
  public boolean isAvailable(@Nonnull Project project) {
    //return !GitRepositoryManager.getInstance(project).getRepositories().isEmpty();
    return true;
  }

  @Override
  @Nonnull
  public StatusBarWidget createWidget(@Nonnull Project project) {
    return new GitBranchWidget(project);
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Override
  public void disposeWidget(@Nonnull StatusBarWidget widget) {
    Disposer.dispose(widget);
  }

  @Override
  public boolean canBeEnabledOn(@Nonnull StatusBar statusBar) {
    return true;
  }
}
