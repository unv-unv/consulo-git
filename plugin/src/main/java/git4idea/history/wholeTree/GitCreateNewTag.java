/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package git4idea.history.wholeTree;

import consulo.git.localize.GitLocalize;
import consulo.ide.ServiceManager;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.InputValidator;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import consulo.util.lang.StringUtil;
import git4idea.branch.GitBrancher;
import git4idea.repo.GitRepository;

import java.util.Collections;

/**
 * @author Irina.Chernushina
 * @since 2011-10-26
 */
public class GitCreateNewTag {
    private final Project myProject;
    private final GitRepository myRepository;
    private final String myReference;
    private final Runnable myCallInAwtAfterExecution;

    public GitCreateNewTag(Project project, GitRepository repository, String reference, Runnable callInAwtAfterExecution) {
        myProject = project;
        myRepository = repository;
        myReference = reference;
        myCallInAwtAfterExecution = callInAwtAfterExecution;
    }

    @RequiredUIAccess
    public void execute() {
        final String name = Messages.showInputDialog(
            myProject,
            GitLocalize.gitNewTagDialogTagNameLabel().get(),
            GitLocalize.gitNewTagDialogTitle(myReference).get(),
            UIUtil.getQuestionIcon(),
            "",
            new InputValidator() {
                @Override
                @RequiredUIAccess
                public boolean checkInput(String inputString) {
                    return !StringUtil.isEmpty(inputString) && !StringUtil.containsWhitespaces(inputString);
                }

                @Override
                @RequiredUIAccess
                public boolean canClose(String inputString) {
                    return !StringUtil.isEmpty(inputString) && !StringUtil.containsWhitespaces(inputString);
                }
            }
        );
        if (name != null) {
            GitBrancher brancher = ServiceManager.getService(myProject, GitBrancher.class);
            brancher.createNewTag(name, myReference, Collections.singletonList(myRepository), myCallInAwtAfterExecution);
        }
    }
}
