/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package git4idea.rebase;

import consulo.git.localize.GitLocalize;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.util.io.FileUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.config.GitConfigUtil;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * The dialog used for the unstructured information from git rebase.
 */
public class GitRebaseUnstructuredEditor extends DialogWrapper {
    /**
     * The text with information from the GIT
     */
    private JTextArea myTextArea;
    /**
     * The root panel of the dialog
     */
    private JPanel myPanel;
    /**
     * The label that contains the git root path
     */
    private JLabel myGitRootLabel;
    /**
     * The file encoding
     */
    private final String encoding;
    /**
     * The file being edited
     */
    private final File myFile;

    /**
     * The constructor
     *
     * @param project the context project
     * @param root    the Git root
     * @param path    the path to edit
     * @throws IOException if there is an IO problem
     */
    protected GitRebaseUnstructuredEditor(Project project, VirtualFile root, String path) throws IOException {
        super(project, true);
        setTitle(GitLocalize.rebaseUnstructuredEditorTitle());
        setOKButtonText(GitLocalize.rebaseUnstructuredEditorButton());
        myGitRootLabel.setText(root.getPresentableUrl());
        encoding = GitConfigUtil.getCommitEncoding(project, root);
        myFile = new File(path);
        myTextArea.setText(Files.readString(myFile.toPath(), Charset.forName(encoding)));
        myTextArea.setCaretPosition(0);
        init();
    }

    /**
     * Save content to the file
     *
     * @throws IOException if there is an IO problem
     */
    public void save() throws IOException {
        FileUtil.writeToFile(myFile, myTextArea.getText().getBytes(encoding));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected JComponent createCenterPanel() {
        return myPanel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDimensionServiceKey() {
        return getClass().getName();
    }

    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myTextArea;
    }
}
