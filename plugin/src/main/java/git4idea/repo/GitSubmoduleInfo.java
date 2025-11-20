package git4idea.repo;

import jakarta.annotation.Nonnull;

public record GitSubmoduleInfo(@Nonnull String path, @Nonnull String url) {
}
