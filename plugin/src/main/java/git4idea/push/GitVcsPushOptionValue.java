package git4idea.push;

import consulo.versionControlSystem.distributed.push.VcsPushOptionValue;

public record GitVcsPushOptionValue(GitPushTagMode pushTagMode, boolean isSkipHook) implements VcsPushOptionValue {
    @Deprecated
    public GitPushTagMode getPushTagMode() {
        return pushTagMode();
    }
}
