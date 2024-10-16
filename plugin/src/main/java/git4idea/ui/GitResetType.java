package git4idea.ui;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.ex.awt.SimpleListCellRenderer;
import git4idea.commands.GitLineHandler;
import jakarta.annotation.Nonnull;

/**
* @author UNV
* @since 2024-10-16
*/
enum GitResetType {
    SOFT("--soft", GitLocalize.resetTypeSoft()),
    MIXED("--mixed", GitLocalize.resetTypeMixed()),
    HARD("--hard", GitLocalize.resetTypeHard());

    public static final SimpleListCellRenderer<GitResetType> LIST_CELL_RENDERER =
        SimpleListCellRenderer.<GitResetType>create("", resetType -> resetType.getTitle().get());

    private final String myParams;
    private final LocalizeValue myTitle;

    GitResetType(@Nonnull String params, @Nonnull LocalizeValue title) {
        myParams = params;
        myTitle = title;
    }

    public void addParametersTo(GitLineHandler handler) {
        handler.addParameters(myParams);
    }

    @Nonnull
    public LocalizeValue getTitle() {
        return myTitle;
    }
}
