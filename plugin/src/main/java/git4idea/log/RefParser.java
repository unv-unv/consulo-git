package git4idea.log;

import consulo.versionControlSystem.log.Hash;
import consulo.versionControlSystem.log.VcsLogObjectsFactory;
import consulo.versionControlSystem.log.VcsRef;
import consulo.versionControlSystem.log.base.HashImpl;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.repo.GitRepositoryReader;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TODO: remove when tags are supported by the {@link GitRepositoryReader}.
 *
 * @author erokhins
 */
class RefParser {
    private final VcsLogObjectsFactory myFactory;

    public RefParser(VcsLogObjectsFactory factory) {
        myFactory = factory;
    }

    // e25b7d8f (HEAD, refs/remotes/origin/master, refs/remotes/origin/HEAD, refs/heads/master)
    public List<VcsRef> parseCommitRefs(@Nonnull String input, @Nonnull VirtualFile root) {
        int firstSpaceIndex = input.indexOf(' ');
        if (firstSpaceIndex < 0) {
            return Collections.emptyList();
        }
        String strHash = input.substring(0, firstSpaceIndex);
        Hash hash = HashImpl.build(strHash);
        String refPaths = input.substring(firstSpaceIndex + 2, input.length() - 1);
        String[] longRefPaths = refPaths.split(", ");
        List<VcsRef> refs = new ArrayList<>();
        for (String longRefPatch : longRefPaths) {
            VcsRef ref = createRef(hash, longRefPatch, root);
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    @Nullable
    private static String getRefName(@Nonnull String longRefPath, @Nonnull String startPatch) {
        String tagPrefix = "tag: ";
        if (longRefPath.startsWith(tagPrefix)) {
            longRefPath = longRefPath.substring(tagPrefix.length());
        }
        if (longRefPath.startsWith(startPatch)) {
            return longRefPath.substring(startPatch.length());
        }
        else {
            return null;
        }
    }

    // example input: fb29c80 refs/tags/92.29
    @Nullable
    private VcsRef createRef(@Nonnull Hash hash, @Nonnull String longRefPath, @Nonnull VirtualFile root) {
        String name = getRefName(longRefPath, "refs/tags/");
        if (name != null) {
            return myFactory.createRef(hash, name, GitRefManager.TAG, root);
        }

        return null;
    }
}
