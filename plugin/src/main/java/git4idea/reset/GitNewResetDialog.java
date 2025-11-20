/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package git4idea.reset;

import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.ex.awt.*;
import consulo.util.lang.StringUtil;
import consulo.util.lang.xml.XmlStringUtil;
import consulo.versionControlSystem.distributed.DvcsUtil;
import consulo.versionControlSystem.log.VcsFullCommitDetails;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

import static consulo.versionControlSystem.distributed.DvcsUtil.getShortRepositoryName;

public class GitNewResetDialog extends DialogWrapper {

    private static final String DIALOG_ID = "git.new.reset.dialog";

    @Nonnull
    private final Project myProject;
    @Nonnull
    private final Map<GitRepository, VcsFullCommitDetails> myCommits;
    @Nonnull
    private final GitResetMode myDefaultMode;
    @Nonnull
    private final ButtonGroup myButtonGroup;

    private RadioButtonEnumModel<GitResetMode> myEnumModel;

    protected GitNewResetDialog(
        @Nonnull Project project,
        @Nonnull Map<GitRepository, VcsFullCommitDetails> commits,
        @Nonnull GitResetMode defaultMode
    ) {
        super(project);
        myProject = project;
        myCommits = commits;
        myDefaultMode = defaultMode;
        myButtonGroup = new ButtonGroup();

        init();
        setTitle(LocalizeValue.localizeTODO("Git Reset"));
        setOKButtonText(LocalizeValue.localizeTODO("Reset"));
        setOKButtonMnemonic('R');
        setResizable(false);
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBag gb = new GridBag()
            .setDefaultAnchor(GridBagConstraints.LINE_START)
            .setDefaultInsets(0, UIUtil.DEFAULT_HGAP, UIUtil.LARGE_VGAP, 0);

        String description = prepareDescription(myProject, myCommits);
        panel.add(new JBLabel(XmlStringUtil.wrapInHtml(description)), gb.nextLine().next().coverLine());

        String explanation = "This will reset the current branch head to the selected commit, <br/>" +
            "and update the working tree and the index according to the selected mode:";
        panel.add(new JBLabel(XmlStringUtil.wrapInHtml(explanation), UIUtil.ComponentStyle.SMALL), gb.nextLine().next().coverLine());

        for (GitResetMode mode : GitResetMode.values()) {
            JBRadioButton button = new JBRadioButton(mode.getName());
            button.setMnemonic(mode.getName().charAt(0));
            myButtonGroup.add(button);
            panel.add(button, gb.nextLine().next());
            panel.add(new JBLabel(XmlStringUtil.wrapInHtml(mode.getDescription()), UIUtil.ComponentStyle.SMALL), gb.next());
        }

        myEnumModel = RadioButtonEnumModel.bindEnum(GitResetMode.class, myButtonGroup);
        myEnumModel.setSelected(myDefaultMode);
        return panel;
    }

    @Nullable
    @Override
    protected String getHelpId() {
        return DIALOG_ID;
    }

    @Nonnull
    private static String prepareDescription(@Nonnull Project project, @Nonnull Map<GitRepository, VcsFullCommitDetails> commits) {
        if (commits.size() == 1 && !isMultiRepo(project)) {
            Map.Entry<GitRepository, VcsFullCommitDetails> entry = commits.entrySet().iterator().next();
            return String.format("%s -> %s", getSourceText(entry.getKey()), getTargetText(entry.getValue()));
        }

        StringBuilder desc = new StringBuilder("");
        for (Map.Entry<GitRepository, VcsFullCommitDetails> entry : commits.entrySet()) {
            GitRepository repository = entry.getKey();
            VcsFullCommitDetails commit = entry.getValue();
            desc.append(String.format(
                "%s in %s -> %s<br/>",
                getSourceText(repository),
                getShortRepositoryName(repository),
                getTargetText(commit)
            ));
        }
        return desc.toString();
    }

    @Nonnull
    private static String getTargetText(@Nonnull VcsFullCommitDetails commit) {
        String commitMessage = StringUtil.escapeXml(StringUtil.shortenTextWithEllipsis(commit.getSubject(), 20, 0));
        return String.format("<code><b>%s</b> \"%s\"</code> by <code>%s</code>", commit.getId().toShortString(), commitMessage,
            commit.getAuthor().getName()
        );
    }

    @Nonnull
    private static String getSourceText(@Nonnull GitRepository repository) {
        String currentRevision = repository.getCurrentRevision();
        assert currentRevision != null;
        String text = repository.getCurrentBranch() == null
            ? "HEAD (" + DvcsUtil.getShortHash(currentRevision) + ")"
            : repository.getCurrentBranch().getName();
        return "<b>" + text + "</b>";
    }

    private static boolean isMultiRepo(@Nonnull Project project) {
        return GitRepositoryManager.getInstance(project).moreThanOneRoot();
    }

    @Nonnull
    public GitResetMode getResetMode() {
        return myEnumModel.getSelected();
    }

}
