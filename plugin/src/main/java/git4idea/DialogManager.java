package git4idea;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import javax.swing.Icon;

import javax.annotation.Nullable;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;

/**
 * Use {@link DialogManager#show(DialogWrapper) DialogManager.show(DialogWrapper)} instead of {@link DialogWrapper#show()}
 * to make the code testable:
 * in the test environment such calls will be transferred to the TestDialogManager and can be handled by tests;
 * in the production environment they will be simply delegated to DialogWrapper#show().
 *
 * @author Kirill Likhodedov
 */
@Singleton
public class DialogManager
{
	public static void show(@Nonnull DialogWrapper dialog)
	{
		dialogManager().showDialog(dialog);
	}

	public static int showMessage(@Nonnull final String description,
			@Nonnull final String title,
			@Nonnull final String[] options,
			final int defaultButtonIndex,
			final int focusedButtonIndex,
			@Nullable final Icon icon,
			@Nullable final DialogWrapper.DoNotAskOption dontAskOption)
	{
		return dialogManager().showMessageDialog(description, title, options, defaultButtonIndex, focusedButtonIndex, icon, dontAskOption);
	}

	public static int showOkCancelDialog(@Nonnull Project project, @Nonnull String message, @Nonnull String title, @Nonnull String okButtonText, @Nonnull String cancelButtonText, @Nullable Icon icon)
	{
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
			@Nullable Icon icon)
	{
		return dialogManager().showMessageDialog(project, message, title, new String[]{
				yesButtonText,
				noButtonText,
				cancelButtonText
		}, 0, icon);
	}

	protected void showDialog(@Nonnull DialogWrapper dialog)
	{
		dialog.show();
	}

	protected int showMessageDialog(@Nonnull Project project, @Nonnull String message, @Nonnull String title, @Nonnull String[] options, int defaultButtonIndex, @Nullable Icon icon)
	{
		return Messages.showDialog(project, message, title, options, defaultButtonIndex, icon);
	}

	protected int showMessageDialog(@Nonnull String description,
			@Nonnull String title,
			@Nonnull String[] options,
			int defaultButtonIndex,
			int focusedButtonIndex,
			@Nullable Icon icon,
			@Nullable DialogWrapper.DoNotAskOption dontAskOption)
	{
		return Messages.showDialog(description, title, options, defaultButtonIndex, focusedButtonIndex, icon, dontAskOption);
	}

	@Nonnull
	private static DialogManager dialogManager()
	{
		return ServiceManager.getService(DialogManager.class);
	}
}
