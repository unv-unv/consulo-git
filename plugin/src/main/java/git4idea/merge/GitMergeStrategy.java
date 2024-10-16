package git4idea.merge;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.ex.awt.SimpleListCellRenderer;
import git4idea.commands.GitLineHandler;
import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * @author UNV
 * @since 2024-10-16
 */
public enum GitMergeStrategy {
    DEFAULT(GitLocalize.mergeDefaultStrategy()),
    RESOLVE("resolve"),
    RECURSIVE("recursive"),
    OCTOPUS("octopus"),
    OURS("ours"),
    SUBTREE("subtree");

    public static final SimpleListCellRenderer<GitMergeStrategy> LIST_CELL_RENDERER =
        SimpleListCellRenderer.<GitMergeStrategy>create("", strategy -> strategy.getTitle().get());

    public static final List<GitMergeStrategy> NO_BRANCH_STRATEGIES = List.of(DEFAULT);

    public static final List<GitMergeStrategy> SINGLE_BRANCH_STRATEGIES = List.of(
        DEFAULT,
        RESOLVE,
        RECURSIVE,
        OCTOPUS,
        OURS,
        SUBTREE
    );

    public static final List<GitMergeStrategy> MULTI_BRANCH_STRATEGIES = List.of(
        DEFAULT,
        OCTOPUS,
        OURS
    );

    private final LocalizeValue myTitle;
    private final String myStrategy;

    GitMergeStrategy(@Nonnull LocalizeValue title) {
        myTitle = title;
        myStrategy = null;
    }

    GitMergeStrategy(@Nonnull String strategy) {
        myTitle = LocalizeValue.of(strategy);
        myStrategy = strategy;
    }

    public void addParametersTo(GitLineHandler handler) {
        if (myStrategy != null) {
            handler.addParameters("--strategy", myStrategy);
        }
    }

    @Nonnull
    public LocalizeValue getTitle() {
        return myTitle;
    }

    /**
     * Get a list of merge strategies for the specified branch count
     *
     * @param branchCount a number of branches to merge
     * @return an array of strategy names
     */
    public static List<GitMergeStrategy> getMergeStrategies(int branchCount) {
        if (branchCount < 0) {
            throw new IllegalArgumentException("Branch count must be non-negative: " + branchCount);
        }
        return switch (branchCount) {
            case 0 -> NO_BRANCH_STRATEGIES;
            case 1 -> SINGLE_BRANCH_STRATEGIES;
            default -> MULTI_BRANCH_STRATEGIES;
        };
    }
}
