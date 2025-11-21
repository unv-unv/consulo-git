package git4idea.repo;

import jakarta.annotation.Nonnull;

public record GitSubmoduleInfo(@Nonnull String path, @Nonnull String url) {
    @Deprecated
    @Nonnull
    public String getPath() {
        return path();
    }

    @Deprecated
    @Nonnull
    public String getUrl() {
        return url();
    }
}
