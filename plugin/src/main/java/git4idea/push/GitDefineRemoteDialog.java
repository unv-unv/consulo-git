/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package git4idea.push;

import consulo.application.progress.ProgressManager;
import consulo.component.ProcessCanceledException;
import consulo.localize.LocalizeValue;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.*;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.validators.GitRefNameValidator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

class GitDefineRemoteDialog extends DialogWrapper {
    private static final Logger LOG = LoggerFactory.getLogger(GitDefineRemoteDialog.class);

    @Nonnull
    private final GitRepository myRepository;
    @Nonnull
    private final Git myGit;

    @Nonnull
    private final JTextField myRemoteName;
    @Nonnull
    private final JTextField myRemoteUrl;

    GitDefineRemoteDialog(@Nonnull GitRepository repository, @Nonnull Git git) {
        super(repository.getProject());
        myRepository = repository;
        myGit = git;
        myRemoteName = new JTextField(GitRemote.ORIGIN_NAME, 20);
        myRemoteUrl = new JTextField(20);
        setTitle(LocalizeValue.localizeTODO("Define Remote"));
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel defineRemoteComponent = new JPanel(new GridBagLayout());
        GridBag gb = new GridBag()
            .setDefaultAnchor(GridBagConstraints.LINE_START)
            .setDefaultInsets(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP, 0, 0);
        defineRemoteComponent.add(new JBLabel("Name:"), gb.nextLine().next().anchor(GridBagConstraints.EAST));
        defineRemoteComponent.add(myRemoteName, gb.next());
        defineRemoteComponent.add(
            new JBLabel("URL: "),
            gb.nextLine().next().anchor(GridBagConstraints.EAST).insets(0, UIUtil.DEFAULT_HGAP, UIUtil.DEFAULT_VGAP, 0)
        );
        defineRemoteComponent.add(myRemoteUrl, gb.next());
        return defineRemoteComponent;
    }

    @Nonnull
    String getRemoteName() {
        return StringUtil.notNullize(myRemoteName.getText()).trim();
    }

    @Nonnull
    String getRemoteUrl() {
        return StringUtil.notNullize(myRemoteUrl.getText()).trim();
    }

    @Nullable
    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myRemoteUrl;
    }

    @Override
    @RequiredUIAccess
    protected void doOKAction() {
        LocalizeValue error = validateRemoteUnderModal(getRemoteName(), getRemoteUrl());
        if (error != LocalizeValue.empty()) {
            Messages.showErrorDialog(myRepository.getProject(), error.get(), "Invalid Remote");
        }
        else {
            super.doOKAction();
        }
    }

    @Nonnull
    private LocalizeValue validateRemoteUnderModal(@Nonnull String name, @Nonnull String url) throws ProcessCanceledException {
        if (url.isEmpty()) {
            LOG.warn("Invalid remote. Name: {}, URL: {}, error: {}", name, url, "URL can't be empty");
            return LocalizeValue.localizeTODO("URL can't be empty");
        }
        if (!GitRefNameValidator.getInstance().checkInput(name)) {
            LOG.warn("Invalid remote. Name: {}, URL: {}, error: {}", name, url, "Remote name contains illegal characters");
            return LocalizeValue.localizeTODO("Remote name contains illegal characters");
        }

        return ProgressManager.getInstance().runProcessWithProgressSynchronously(
            () -> {
                GitCommandResult result =
                    myGit.lsRemote(myRepository.getProject(), VirtualFileUtil.virtualToIoFile(myRepository.getRoot()), url);
                if (!result.success()) {
                    LOG.warn(
                        "Invalid remote. Name: {}, URL: {}, error: {}",
                        name,
                        url,
                        "Remote URL test failed: " + result.getErrorOutputAsHtmlString()
                    );
                    return LocalizeValue.join(LocalizeValue.localizeTODO("Remote URL test failed: "), result.getErrorOutputAsHtmlValue());
                }
                else {
                    return LocalizeValue.empty();
                }
            },
            LocalizeValue.localizeTODO("Checking URL..."),
            true,
            myRepository.getProject()
        );
    }
}
