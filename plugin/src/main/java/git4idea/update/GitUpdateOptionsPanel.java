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

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import consulo.application.Application;
import consulo.git.localize.GitLocalize;
import consulo.ui.RadioButton;
import consulo.ui.StaticPosition;
import consulo.ui.ValueGroups;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awtUnsafe.TargetAWT;
import consulo.ui.layout.*;
import git4idea.config.GitVcsSettings;
import git4idea.config.UpdateMethod;
import git4idea.i18n.GitBundle;
import jakarta.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;
import java.util.ResourceBundle;

/**
 * Update options panel
 */
public class GitUpdateOptionsPanel {
    private VerticalLayout myPanel;

    private RadioButton myBranchDefaultRadioButton;
    private RadioButton myForceRebaseRadioButton;
    private RadioButton myForceMergeRadioButton;
    private RadioButton myStashRadioButton;
    private RadioButton myShelveRadioButton;

    @RequiredUIAccess
    public GitUpdateOptionsPanel() {
        myPanel = VerticalLayout.create();

        myShelveRadioButton = RadioButton.create(GitLocalize.updateOptionsSaveShelve());
        myShelveRadioButton.setToolTipText(GitLocalize.updateOptionsSaveShelveTooltip(Application.get().getName()));
        myForceMergeRadioButton = RadioButton.create(GitLocalize.updateOptionsTypeMerge());
        myForceMergeRadioButton.setToolTipText(GitLocalize.updateOptionsTypeMergeTooltip());
        myForceRebaseRadioButton = RadioButton.create(GitLocalize.updateOptionsTypeRebase());
        myForceRebaseRadioButton.setToolTipText(GitLocalize.updateOptionsTypeRebaseTooltip());
        myBranchDefaultRadioButton = RadioButton.create(GitLocalize.updateOptionsTypeDefault());
        myBranchDefaultRadioButton.setValue(true);
        myBranchDefaultRadioButton.setToolTipText(GitLocalize.updateOptionsTypeDefaultTooltip());
        myStashRadioButton = RadioButton.create(GitLocalize.updateOptionsSaveStash());
        myStashRadioButton.setValue(true);
        myStashRadioButton.setToolTipText(GitLocalize.updateOptionsSaveStashTooltip());
        myShelveRadioButton.setValue(false);

        VerticalLayout leftGroup = VerticalLayout.create();
        leftGroup.add(myForceMergeRadioButton);
        leftGroup.add(myForceRebaseRadioButton);
        leftGroup.add(myBranchDefaultRadioButton);

        myPanel.add(LabeledLayout.create(GitLocalize.updateOptionsType(), leftGroup));

        VerticalLayout rightGroup = VerticalLayout.create();
        rightGroup.add(myStashRadioButton);
        rightGroup.add(myShelveRadioButton);

        myPanel.add(LabeledLayout.create(GitLocalize.updateOptionsSaveBeforeUpdate(), rightGroup));

        ValueGroups.boolGroup().add(myBranchDefaultRadioButton).add(myForceRebaseRadioButton).add(myForceMergeRadioButton);

        ValueGroups.boolGroup().add(myStashRadioButton).add(myShelveRadioButton);
    }

    @Nonnull
    public JComponent getPanel() {
        return (JComponent) TargetAWT.to(myPanel);
    }

    @RequiredUIAccess
    public boolean isModified(GitVcsSettings settings) {
        UpdateMethod type = getUpdateType();
        return type != settings.getUpdateType() || updateSaveFilesPolicy() != settings.updateChangesPolicy();
    }

    /**
     * @return get policy value from selected radio buttons
     */
    @RequiredUIAccess
    private GitVcsSettings.UpdateChangesPolicy updateSaveFilesPolicy() {
        return UpdatePolicyUtils.getUpdatePolicy(myStashRadioButton, myShelveRadioButton);
    }

    /**
     * @return get the currently selected update type
     */
    @RequiredUIAccess
    private UpdateMethod getUpdateType() {
        UpdateMethod type = null;
        if (myForceRebaseRadioButton.getValueOrError()) {
            type = UpdateMethod.REBASE;
        }
        else if (myForceMergeRadioButton.getValueOrError()) {
            type = UpdateMethod.MERGE;
        }
        else if (myBranchDefaultRadioButton.getValueOrError()) {
            type = UpdateMethod.BRANCH_DEFAULT;
        }
        assert type != null;
        return type;
    }

    /**
     * Save configuration to settings object
     */
    @RequiredUIAccess
    public void applyTo(GitVcsSettings settings) {
        settings.setUpdateType(getUpdateType());
        settings.setUpdateChangesPolicy(updateSaveFilesPolicy());
    }

    /**
     * Update panel according to settings
     */
    @RequiredUIAccess
    public void updateFrom(GitVcsSettings settings) {
        switch (settings.getUpdateType()) {
            case REBASE:
                myForceRebaseRadioButton.setValue(true);
                break;
            case MERGE:
                myForceMergeRadioButton.setValue(true);
                break;
            case BRANCH_DEFAULT:
                myBranchDefaultRadioButton.setValue(true);
                break;
            default:
                assert false : "Unknown value of update type: " + settings.getUpdateType();
        }
        UpdatePolicyUtils.updatePolicyItem(settings.updateChangesPolicy(), myStashRadioButton, myShelveRadioButton);
    }
}
