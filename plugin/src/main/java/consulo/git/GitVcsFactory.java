package consulo.git;

import consulo.annotation.component.ExtensionImpl;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.versionControlSystem.AbstractVcs;
import consulo.versionControlSystem.VcsFactory;
import git4idea.GitVcs;
import git4idea.annotate.GitAnnotationProvider;
import git4idea.commands.Git;
import git4idea.config.GitExecutableManager;
import git4idea.config.GitVcsApplicationSettings;
import git4idea.config.GitVcsSettings;
import git4idea.diff.GitDiffProvider;
import git4idea.history.GitHistoryProvider;
import git4idea.rollback.GitRollbackEnvironment;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import jakarta.annotation.Nonnull;

/**
 * @author VISTALL
 * @since 29/01/2023
 */
@ExtensionImpl
public class GitVcsFactory implements VcsFactory {
  private static final LocalizeValue Git = LocalizeValue.localizeTODO("Git");

  private final Project myProject;
  private final Provider<Git> myGit;
  private final Provider<GitAnnotationProvider> myGitAnnotationProvider;
  private final Provider<GitDiffProvider> myGitDiffProvider;
  private final Provider<GitHistoryProvider> myGitHistoryProvider;
  private final Provider<GitRollbackEnvironment> myGitRollbackEnvironment;
  private final Provider<GitVcsApplicationSettings> myGitVcsApplicationSettings;
  private final Provider<GitVcsSettings> myGitVcsSettings;
  private final Provider<GitExecutableManager> myGitExecutableManager;

  @Inject
  public GitVcsFactory(@Nonnull Project project,
                       @Nonnull Provider<Git> git,
                       @Nonnull Provider<GitAnnotationProvider> gitAnnotationProvider,
                       @Nonnull Provider<GitDiffProvider> gitDiffProvider,
                       @Nonnull Provider<GitHistoryProvider> gitHistoryProvider,
                       @Nonnull Provider<GitRollbackEnvironment> gitRollbackEnvironment,
                       @Nonnull Provider<GitVcsApplicationSettings> gitSettings,
                       @Nonnull Provider<GitVcsSettings> gitProjectSettings,
                       @Nonnull Provider<GitExecutableManager> gitExecutableManager) {
    myProject = project;
    myGit = git;
    myGitAnnotationProvider = gitAnnotationProvider;
    myGitDiffProvider = gitDiffProvider;
    myGitHistoryProvider = gitHistoryProvider;
    myGitRollbackEnvironment = gitRollbackEnvironment;
    myGitVcsApplicationSettings = gitSettings;
    myGitVcsSettings = gitProjectSettings;
    myGitExecutableManager = gitExecutableManager;
  }

  @Nonnull
  @Override
  public String getId() {
    // wrong - but all code based on name not id
    return GitVcs.NAME;
  }

  @Nonnull
  @Override
  public LocalizeValue getDisplayName() {
    return Git;
  }

  @Nonnull
  @Override
  public String getAdministrativeAreaName() {
    return ".git";
  }

  @Nonnull
  @Override
  public AbstractVcs<?> createVcs() {
    return new GitVcs(myProject,
                      myGit.get(),
                      myGitAnnotationProvider.get(),
                      myGitDiffProvider.get(),
                      myGitHistoryProvider.get(),
                      myGitRollbackEnvironment.get(),
                      myGitVcsApplicationSettings.get(),
                      myGitVcsSettings.get(),
                      myGitExecutableManager.get());
  }
}
