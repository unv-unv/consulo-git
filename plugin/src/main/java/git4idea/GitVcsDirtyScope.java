package git4idea;

import consulo.project.Project;
import consulo.util.collection.MultiMap;
import consulo.util.collection.Sets;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.ProjectLevelVcsManager;
import consulo.versionControlSystem.change.ChangesUtil;
import consulo.versionControlSystem.change.VcsDirtyScopeBuilder;
import consulo.versionControlSystem.change.VcsModifiableDirtyScope;
import consulo.versionControlSystem.root.VcsRoot;
import consulo.versionControlSystem.util.RootDirtySet;
import consulo.versionControlSystem.util.VcsRootIterator;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * @author VISTALL
 * @since 2024-08-03
 */
public class GitVcsDirtyScope extends VcsModifiableDirtyScope implements VcsDirtyScopeBuilder {
    private final GitVcs myVcs;
    private final Project myProject;

    private final MultiMap<FilePath, VirtualFile> myDirtyRootsUnder = new MultiMap<>();
    private final Map<VirtualFile, RootDirtySet> myDirtyDirectories = new HashMap<>();
    private boolean myWasEverythingDirty;

    public GitVcsDirtyScope(GitVcs vcs, Project project) {
        myVcs = vcs;
        myProject = project;

        for (VirtualFile root : ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(vcs)) {
            VirtualFile parentFile = root.getParent();
            if (parentFile == null) {
                continue;
            }

            FilePath parentPath = VcsUtil.getFilePath(parentFile);
            while (parentPath != null) {
                myDirtyRootsUnder.putValue(parentPath, root);
                parentPath = parentPath.getParentPath();
            }
        }
    }

    @Override
    public Project getProject() {
        return myProject;
    }

    @Override
    public GitVcs getVcs() {
        return myVcs;
    }

    public Map<VirtualFile, RootDirtySet> getDirtySetsPerRoot() {
        Map<VirtualFile, RootDirtySet> map = new HashMap<>();
        for (Map.Entry<VirtualFile, RootDirtySet> entry : myDirtyDirectories.entrySet()) {
            map.put(entry.getKey(), entry.getValue().copy());
        }
        return map;
    }

    @Override
    public Collection<VirtualFile> getAffectedContentRoots() {
        return myDirtyDirectories.keySet();
    }

    @Override
    public Set<FilePath> getDirtyFiles() {
        return Set.of();
    }

    @Override
    public Set<FilePath> getDirtyFilesNoExpand() {
        return Set.of();
    }

    @Override
    public Set<FilePath> getRecursivelyDirtyDirectories() {
        Set<FilePath> result = Sets.newHashSet(ChangesUtil.CASE_SENSITIVE_FILE_PATH_HASHING_STRATEGY);
        for (var dirtySet : myDirtyDirectories.values()) {
            result.addAll(dirtySet.collectFilePaths());
        }
        return result;
    }

    @Override
    public void addDirtyPathFast(@Nonnull VirtualFile vcsRoot, @Nonnull FilePath filePath, boolean recursively) {
        RootDirtySet rootSet = createSetForRoot(vcsRoot);
        rootSet.markDirty(filePath);

        if (recursively) {
            for (VirtualFile root : myDirtyRootsUnder.get(filePath)) {
                RootDirtySet subRootSet = createSetForRoot(root);
                subRootSet.markEverythingDirty();
            }
        }
    }

    private RootDirtySet createSetForRoot(VirtualFile vcsRoot) {
        return myDirtyDirectories.computeIfAbsent(vcsRoot, root -> new RootDirtySet(VcsUtil.getFilePath(root), true));
    }

    @Override
    public void markEverythingDirty() {
        myWasEverythingDirty = true;
    }

    @Nonnull
    @Override
    public VcsModifiableDirtyScope pack() {
        GitVcsDirtyScope copy = new GitVcsDirtyScope(myVcs, myProject);
        copy.myWasEverythingDirty = myWasEverythingDirty;

        for (Map.Entry<VirtualFile, RootDirtySet> entry : myDirtyDirectories.entrySet()) {
            copy.myDirtyDirectories.put(entry.getKey(), entry.getValue().compact());
        }
        return copy;
    }

    @Override
    public void addDirtyDirRecursively(FilePath newcomer) {
        VcsRoot vcsRoot = ProjectLevelVcsManager.getInstance(myProject).getVcsRootObjectFor(newcomer);
        if (vcsRoot == null || vcsRoot.getVcs() != getVcs()) {
            return;
        }
        addDirtyPathFast(vcsRoot.getPath(), newcomer, true);
    }

    @Override
    public void addDirtyFile(FilePath newcomer) {
        VcsRoot vcsRoot = ProjectLevelVcsManager.getInstance(myProject).getVcsRootObjectFor(newcomer);
        if (vcsRoot == null || vcsRoot.getVcs() != getVcs()) {
            return;
        }
        addDirtyPathFast(vcsRoot.getPath(), newcomer, false);
    }

    @Override
    public void iterate(Predicate<? super FilePath> iterator) {
        VcsRootIterator.iterate(this, iterator);
    }

    @Override
    public void iterateExistingInsideScope(Predicate<? super VirtualFile> predicate) {
        VcsRootIterator.iterateExistingInsideScope(this, predicate);
    }

    @Override
    public boolean wasEveryThingDirty() {
        return myWasEverythingDirty;
    }

    @Override
    public boolean isEmpty() {
        return myDirtyDirectories.isEmpty();
    }

    @Override
    public boolean belongsTo(FilePath path) {
        VcsRoot vcsRoot = ProjectLevelVcsManager.getInstance(getProject()).getVcsRootObjectFor(path);
        if (vcsRoot == null || vcsRoot.getVcs() != getVcs()) {
            return false;
        }

        RootDirtySet rootSet = myDirtyDirectories.get(vcsRoot.getPath());
        return rootSet != null && rootSet.belongsTo(path);
    }


    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("GitVcsDirtyScope[");
        for (Map.Entry<VirtualFile, RootDirtySet> entry : myDirtyDirectories.entrySet()) {
            VirtualFile root = entry.getKey();
            RootDirtySet dirtyFiles = entry.getValue();

            result.append("Root: ").append(root).append(" -> ").append(dirtyFiles.collectFilePaths());
        }
        result.append("]");
        return result.toString();
    }
}
