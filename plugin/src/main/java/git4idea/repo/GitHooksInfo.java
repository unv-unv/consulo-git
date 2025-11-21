package git4idea.repo;

public record GitHooksInfo(boolean isPreCommitHookAvailable, boolean isPrePushHookAvailable) {
}
