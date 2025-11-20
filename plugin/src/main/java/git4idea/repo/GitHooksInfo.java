package git4idea.repo;

public record GitHooksInfo(boolean preCommitHookAvailable, boolean prePushHookAvailable) {
}
