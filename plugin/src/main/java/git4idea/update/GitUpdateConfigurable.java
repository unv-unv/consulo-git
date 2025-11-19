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
package git4idea.update;

import consulo.configurable.Configurable;
import consulo.configurable.ConfigurationException;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.annotation.RequiredUIAccess;
import git4idea.config.GitVcsSettings;
import jakarta.annotation.Nonnull;

import javax.swing.*;

/**
 * Configurable for the update session
 */
public class GitUpdateConfigurable implements Configurable {
    private final GitVcsSettings mySettings;
    private GitUpdateOptionsPanel myPanel;

    /**
     * The constructor
     *
     * @param settings the settings object
     */
    public GitUpdateConfigurable(GitVcsSettings settings) {
        mySettings = settings;
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public LocalizeValue getDisplayName() {
        return GitLocalize.updateOptionsDisplayName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getHelpTopic() {
        return "reference.VersionControl.Git.UpdateProject";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiredUIAccess
    public JComponent createComponent() {
        myPanel = new GitUpdateOptionsPanel();
        return myPanel.getPanel();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiredUIAccess
    public boolean isModified() {
        return myPanel.isModified(mySettings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiredUIAccess
    public void apply() throws ConfigurationException {
        myPanel.applyTo(mySettings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiredUIAccess
    public void reset() {
        myPanel.updateFrom(mySettings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RequiredUIAccess
    public void disposeUIResources() {
        myPanel = null;
    }
}
