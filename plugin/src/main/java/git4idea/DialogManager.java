package git4idea;

import consulo.annotation.component.ComponentScope;
import consulo.annotation.component.ServiceAPI;
import consulo.annotation.component.ServiceImpl;
import consulo.ide.ServiceManager;
import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.Messages;
import consulo.ui.image.Image;
import jakarta.inject.Singleton;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Use {@link DialogManager#show(DialogWrapper) DialogManager.show(DialogWrapper)} instead of {@link DialogWrapper#show()}
 * to make the code testable:
 * in the test environment such calls will be transferred to the TestDialogManager and can be handled by tests;
 * in the production environment they will be simply delegated to DialogWrapper#show().
 *
 * @author Kirill Likhodedov
 */
@Singleton
@ServiceAPI(ComponentScope.APPLICATION)
@ServiceImpl
public class DialogManager {
  public static void show(@Nonnull DialogWrapper dialog) {
    dialogManager().showDialog(dialog);
  }

  public static int showMessage(@Nonnull final String description,
                                @Nonnull final String title,
                                @Nonnull final String[] options,
                                final int defaultButtonIndex,
                                final int focusedButtonIndex,
                                @Nullable final Image icon,
                                @Nullable final DialogWrapper.DoNotAskOption dontAskOption) {
    return dialogManager().showMessageDialog(description, title, options, defaultButtonIndex, focusedButtonIndex, icon, dontAskOption);
  }

  public static int showOkCancelDialog(@Nonnull Project project,
                                       @Nonnull String message,
                                       @Nonnull String title,
                                       @Nonnull String okButtonText,
                                       @Nonnull String cancelButtonText,
                                       @Nullable Image icon) {
    return dialogManager().showMessageDialog(project, message, title, new String[]{
      okButtonText,
      cancelButtonText
    }, 0, icon);
  }

  public static int showYesNoCancelDialog(@Nonnull Project project,
                                          @Nonnull String message,
                                          @Nonnull String title,
                                          @Nonnull String yesButtonText,
                                          @Nonnull String noButtonText,
                                          @Nonnull String cancelButtonText,
                                          @Nullable Image icon) {
    return dialogManager().showMessageDialog(project, message, title, new String[]{
      yesButtonText,
      noButtonText,
      cancelButtonText
    }, 0, icon);
  }

  protected void showDialog(@Nonnull DialogWrapper dialog) {
    dialog.show();
  }

  protected int showMessageDialog(@Nonnull Project project,
                                  @Nonnull String message,
                                  @Nonnull String title,
                                  @Nonnull String[] options,
                                  int defaultButtonIndex,
                                  @Nullable Image icon) {
    return Messages.showDialog(project, message, title, options, defaultButtonIndex, icon);
  }

  protected int showMessageDialog(@Nonnull String description,
                                  @Nonnull String title,
                                  @Nonnull String[] options,
                                  int defaultButtonIndex,
                                  int focusedButtonIndex,
                                  @Nullable Image icon,
                                  @Nullable DialogWrapper.DoNotAskOption dontAskOption) {
    return Messages.showDialog(description, title, options, defaultButtonIndex, focusedButtonIndex, icon, dontAskOption);
  }

  @Nonnull
  private static DialogManager dialogManager() {
    return ServiceManager.getService(DialogManager.class);
  }
}
