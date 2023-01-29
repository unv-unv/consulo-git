/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.git.config;

import consulo.application.AllIcons;
import consulo.application.progress.Task;
import consulo.disposer.Disposable;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.localize.LocalizeValue;
import consulo.process.cmd.ParametersListUtil;
import consulo.project.Project;
import consulo.ui.*;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.border.BorderPosition;
import consulo.ui.border.BorderStyle;
import consulo.ui.ex.FileChooserTextBoxBuilder;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;
import consulo.ui.util.LabeledBuilder;
import consulo.util.collection.ContainerUtil;
import consulo.versionControlSystem.distributed.DvcsBundle;
import consulo.versionControlSystem.distributed.branch.DvcsSyncSettings;
import git4idea.GitVcs;
import git4idea.config.*;
import git4idea.repo.GitRepositoryManager;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Git VCS configuration panel
 */
public class GitVcsPanel {
  private final GitVcsApplicationSettings myAppSettings;
  private final GitVcs myVcs;

  private final VerticalLayout myRootPanel;
  private final FileChooserTextBoxBuilder.Controller myGitField;
  private final ComboBox<GitVcsApplicationSettings.SshExecutable> mySSHExecutableComboBox; // Type of SSH executable to use
  private final CheckBox myAutoUpdateIfPushRejected;
  private final CheckBox mySyncControl;
  private final CheckBox myAutoCommitOnCherryPick;
  private final CheckBox myWarnAboutCrlf;
  private final CheckBox myWarnAboutDetachedHead;
  private final CheckBox myEnableForcePush;
  private final TextBoxWithExpandAction myProtectedBranchesButton;
  private final Label myProtectedBranchesLabel;
  private final ComboBox<UpdateMethod> myUpdateMethodComboBox;

  @Nonnull
  private final Project myProject;

  @RequiredUIAccess
  public GitVcsPanel(@Nonnull Project project, @Nonnull Disposable uiDisposable) {
    myProject = project;
    myVcs = GitVcs.getInstance(project);
    myAppSettings = GitVcsApplicationSettings.getInstance();
    myRootPanel = VerticalLayout.create();

    FileChooserTextBoxBuilder gitPathBuilder = FileChooserTextBoxBuilder.create(project);
    gitPathBuilder.uiDisposable(uiDisposable);
    gitPathBuilder.dialogTitle(GitLocalize.findGitTitle());
    gitPathBuilder.dialogDescription(GitLocalize.findGitDescription());
    gitPathBuilder.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor());

    myAutoUpdateIfPushRejected = CheckBox.create(LocalizeValue.localizeTODO("Auto-update if &push of the current branch was rejected"));
    myEnableForcePush = CheckBox.create(LocalizeValue.localizeTODO("Allow &force push"));
    mySyncControl = CheckBox.create(DvcsBundle.message("sync.setting"));
    myAutoCommitOnCherryPick = CheckBox.create(LocalizeValue.localizeTODO("Commit automatically on cherry-pick"));
    myWarnAboutCrlf = CheckBox.create(LocalizeValue.localizeTODO("Warn if &CRLF line separators are about to be committed"));
    myWarnAboutDetachedHead = CheckBox.create(LocalizeValue.localizeTODO("Warn when committing in detached HEAD or during rebase"));

    myGitField = gitPathBuilder.build();

    mySSHExecutableComboBox = ComboBox.create(GitVcsApplicationSettings.SshExecutable.values());
    mySSHExecutableComboBox.setValue(GitVcsApplicationSettings.SshExecutable.IDEA_SSH);
    mySSHExecutableComboBox.setTextRender(sshExecutable -> {
      switch (sshExecutable) {
        case IDEA_SSH:
          return GitLocalize.gitVcsConfigSshModeIdea();
        case NATIVE_SSH:
          return GitLocalize.gitVcsConfigSshModeNative();
        case PUTTY:
          return GitLocalize.gitVcsConfigSshModePutty();
        default:
          throw new IllegalArgumentException(sshExecutable.name());
      }
    });

    myUpdateMethodComboBox = ComboBox.create(UpdateMethod.values());
    myUpdateMethodComboBox.selectFirst();

    Button testButton = Button.create(GitLocalize.cloneTest());
    testButton.addClickListener(e -> testConnection());
    final GitRepositoryManager repositoryManager = ServiceManager.getService(project, GitRepositoryManager.class);
    mySyncControl.setVisible(repositoryManager.moreThanOneRoot());

    myProtectedBranchesLabel = Label.create(LocalizeValue.localizeTODO("Protected branches:"));
    myProtectedBranchesButton = TextBoxWithExpandAction.create(AllIcons.Actions.ShowViewer,
                                                               "Protected Branches",
                                                               ParametersListUtil.COLON_LINE_PARSER,
                                                               ParametersListUtil.COLON_LINE_JOINER);

    myEnableForcePush.addValueListener(e -> {
      myProtectedBranchesButton.setEnabled(myEnableForcePush.getValueOrError());
      myProtectedBranchesLabel.setEnabled(myEnableForcePush.getValueOrError());
    });

    myRootPanel.add(DockLayout.create().left(Label.create(GitLocalize.gitVcsConfigPathLabel())).center(myGitField).right(testButton));
    myRootPanel.add(LabeledBuilder.sided(GitLocalize.gitVcsConfigSshMode(), mySSHExecutableComboBox));
    myRootPanel.add(mySyncControl);
    myRootPanel.add(myAutoCommitOnCherryPick);
    myRootPanel.add(myWarnAboutCrlf);
    myRootPanel.add(myWarnAboutDetachedHead);
    Component updateMethodLabeled = LabeledBuilder.sided(LocalizeValue.localizeTODO("Update method:"), myUpdateMethodComboBox);
    updateMethodLabeled.addBorder(BorderPosition.LEFT, BorderStyle.EMPTY, 18);
    myRootPanel.add(updateMethodLabeled);
    myRootPanel.add(myAutoUpdateIfPushRejected);
    myRootPanel.add(DockLayout.create()
                              .left(myEnableForcePush)
                              .right(HorizontalLayout.create(5).add(myProtectedBranchesLabel).add(myProtectedBranchesButton)));
  }

  /**
   * Test availability of the connection
   */
  @RequiredUIAccess
  private void testConnection() {
    final String executable = getCurrentExecutablePath();
    if (myAppSettings != null) {
      myAppSettings.setPathToGit(executable);
    }

    UIAccess uiAccess = UIAccess.current();

    Task.Backgroundable.queue(myProject, "Checking git version...", true, indicator ->
    {
      final GitVersion version;
      try {
        version = GitVersion.identifyVersion(executable);
      }
      catch (Exception e) {
        uiAccess.give(() -> Alerts.okInfo(LocalizeValue.of(e.getLocalizedMessage()))
                                  .title(GitLocalize.findGitErrorTitle())
                                  .showAsync(myRootPanel));
        return;
      }

      uiAccess.give(() ->
                    {
                      Alert<Object> alert;
                      if (version.isSupported()) {
                        alert = Alerts.okInfo(String.format("Git version is %s", version.toString()));
                      }
                      else {
                        alert = Alerts.okWarning(String.format(
                          "This version is unsupported, and some plugin functionality could fail to work. The minimal supported version is '%s'",
                          GitVersion.MIN));
                      }

                      alert = alert.title(GitLocalize.findGitSuccessTitle());
                      alert.showAsync(myRootPanel);
                    });
    });
  }

  @RequiredUIAccess
  private String getCurrentExecutablePath() {
    return myGitField.getValue().trim();
  }

  /**
   * @return the configuration panel
   */
  public VerticalLayout getPanel() {
    return myRootPanel;
  }

  /**
   * Load settings into the configuration panel
   *
   * @param settings the settings to load
   */
  @RequiredUIAccess
  public void load(@Nonnull GitVcsSettings settings, @Nonnull GitSharedSettings sharedSettings) {
    myGitField.setValue(settings.getAppSettings().getPathToGit());
    mySSHExecutableComboBox.setValue(settings.getAppSettings().getSshExecutableType());
    myAutoUpdateIfPushRejected.setValue(settings.autoUpdateIfPushRejected());
    mySyncControl.setValue(settings.getSyncSetting() == DvcsSyncSettings.Value.SYNC);
    myAutoCommitOnCherryPick.setValue(settings.isAutoCommitOnCherryPick());
    myWarnAboutCrlf.setValue(settings.warnAboutCrlf());
    myWarnAboutDetachedHead.setValue(settings.warnAboutDetachedHead());
    myEnableForcePush.setValue(settings.isForcePushAllowed());
    myUpdateMethodComboBox.setValue(settings.getUpdateType());
    myProtectedBranchesButton.setValue(ParametersListUtil.COLON_LINE_JOINER.apply(sharedSettings.getForcePushProhibitedPatterns()));
  }

  /**
   * Check if fields has been modified with respect to settings object
   *
   * @param settings the settings to load
   */
  @RequiredUIAccess
  public boolean isModified(@Nonnull GitVcsSettings settings, @Nonnull GitSharedSettings sharedSettings) {
    return !settings.getAppSettings().getPathToGit().equals(getCurrentExecutablePath()) ||
      (settings.getAppSettings().getSshExecutableType() != mySSHExecutableComboBox.getValueOrError()) ||
      !settings.autoUpdateIfPushRejected() == myAutoUpdateIfPushRejected.getValueOrError() ||
      ((settings.getSyncSetting() == DvcsSyncSettings.Value.SYNC) != mySyncControl.getValueOrError() ||
        settings.isAutoCommitOnCherryPick() != myAutoCommitOnCherryPick.getValueOrError() ||
        settings.warnAboutCrlf() != myWarnAboutCrlf.getValueOrError() ||
        settings.warnAboutDetachedHead() != myWarnAboutDetachedHead.getValueOrError() ||
        settings.isForcePushAllowed() != myEnableForcePush.getValueOrError() ||
        settings.getUpdateType() != myUpdateMethodComboBox.getValueOrError() ||
        !ContainerUtil.sorted(sharedSettings.getForcePushProhibitedPatterns()).equals(ContainerUtil.sorted
          (getProtectedBranchesPatterns())));
  }

  /**
   * Save configuration panel state into settings object
   *
   * @param settings the settings object
   */
  @RequiredUIAccess
  public void save(@Nonnull GitVcsSettings settings, GitSharedSettings sharedSettings) {
    settings.getAppSettings().setPathToGit(getCurrentExecutablePath());
    myVcs.checkVersion();
    settings.getAppSettings().setIdeaSsh(mySSHExecutableComboBox.getValueOrError());
    settings.setAutoUpdateIfPushRejected(myAutoUpdateIfPushRejected.getValueOrError());

    settings.setSyncSetting(mySyncControl.getValueOrError() ? DvcsSyncSettings.Value.SYNC : DvcsSyncSettings.Value.DONT_SYNC);
    settings.setAutoCommitOnCherryPick(myAutoCommitOnCherryPick.getValueOrError());
    settings.setWarnAboutCrlf(myWarnAboutCrlf.getValueOrError());
    settings.setWarnAboutDetachedHead(myWarnAboutDetachedHead.getValueOrError());
    settings.setForcePushAllowed(myEnableForcePush.getValueOrError());
    settings.setUpdateType(myUpdateMethodComboBox.getValueOrError());
    sharedSettings.setForcePushProhibitedPatters(getProtectedBranchesPatterns());
  }

  @Nonnull
  @RequiredUIAccess
  private List<String> getProtectedBranchesPatterns() {
    return ParametersListUtil.COLON_LINE_PARSER.apply(myProtectedBranchesButton.getValueOrError());
  }
}
