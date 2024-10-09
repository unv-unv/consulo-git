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

import consulo.application.Application;
import consulo.project.Project;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.UIUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.util.VcsUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import git4idea.i18n.GitBundle;
import git4idea.rollback.GitRollbackEnvironment;
import git4idea.util.GitUIUtil;
import git4idea.util.StringScanner;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dialog that displays locally modified files during update process
 */
public class GitUpdateLocallyModifiedDialog extends DialogWrapper {
    /**
     * The rescan button
     */
    private JButton myRescanButton;
    /**
     * The list of files to revert
     */
    private JList myFilesList;

    private JLabel myDescriptionLabel;
    /**
     * The git root label
     */
    private JLabel myGitRoot;
    /**
     * The root panel
     */
    private JPanel myRootPanel;
    /**
     * The collection with locally modified files
     */
    private final List<String> myLocallyModifiedFiles;

    /**
     * The constructor
     *
     * @param project              the current project
     * @param root                 the vcs root
     * @param locallyModifiedFiles the collection of locally modified files to use
     */
    protected GitUpdateLocallyModifiedDialog(final Project project, final VirtualFile root, List<String> locallyModifiedFiles) {
        super(project, true);
        myLocallyModifiedFiles = locallyModifiedFiles;
        setTitle(GitBundle.message("update.locally.modified.title"));
        myGitRoot.setText(root.getPresentableUrl());
        myFilesList.setModel(new DefaultListModel());
        setOKButtonText(GitBundle.message("update.locally.modified.revert"));
        syncListModel();
        myRescanButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                myLocallyModifiedFiles.clear();
                try {
                    scanFiles(project, root, myLocallyModifiedFiles);
                }
                catch (VcsException ex) {
                    GitUIUtil.showOperationError(project, ex, "Checking for locally modified files");
                }
            }
        });
        myDescriptionLabel.setText(GitBundle.message("update.locally.modified.message", Application.get().getName().get()));
        init();
    }

    /**
     * Refresh list model according to the current content of the collection
     */
    private void syncListModel() {
        DefaultListModel listModel = (DefaultListModel)myFilesList.getModel();
        listModel.removeAllElements();
        for (String p : myLocallyModifiedFiles) {
            listModel.addElement(p);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected JComponent createCenterPanel() {
        return myRootPanel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDimensionServiceKey() {
        return getClass().getName();
    }

    /**
     * Scan working tree and detect locally modified files
     *
     * @param project the project to scan
     * @param root    the root to scan
     * @param files   the collection with files
     * @throws VcsException if there problem with running git or working tree is dirty in unsupported way
     */
    private static void scanFiles(Project project, VirtualFile root, List<String> files) throws VcsException {
        String rootPath = root.getPath();
        GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.DIFF);
        h.addParameters("--name-status");
        h.setSilent(true);
        h.setStdoutSuppressed(true);
        StringScanner s = new StringScanner(h.run());
        while (s.hasMoreData()) {
            if (s.isEol()) {
                s.line();
                continue;
            }
            if (s.tryConsume("M\t")) {
                String path = rootPath + "/" + GitUtil.unescapePath(s.line());
                files.add(path);
            }
            else {
                throw new VcsException("Working tree is dirty in unsupported way: " + s.line());
            }
        }
    }


    /**
     * Show the dialog if needed
     *
     * @param project the project
     * @param root    the vcs root
     * @return true if showing is not needed or operation completed successfully
     */
    public static boolean showIfNeeded(final Project project, final VirtualFile root) {
        final ArrayList<String> files = new ArrayList<>();
        try {
            scanFiles(project, root, files);
            final AtomicBoolean rc = new AtomicBoolean(true);
            if (!files.isEmpty()) {
                UIUtil.invokeAndWaitIfNeeded(new Runnable() {
                    public void run() {
                        GitUpdateLocallyModifiedDialog d = new GitUpdateLocallyModifiedDialog(project, root, files);
                        d.show();
                        rc.set(d.isOK());
                    }
                });
                if (rc.get()) {
                    if (!files.isEmpty()) {
                        revertFiles(project, root, files);
                    }
                }
            }
            return rc.get();
        }
        catch (final VcsException e) {
            UIUtil.invokeAndWaitIfNeeded(new Runnable() {
                public void run() {
                    GitUIUtil.showOperationError(project, e, "Checking for locally modified files");
                }
            });
            return false;
        }
    }

    /**
     * Revert files from the list
     *
     * @param project the project
     * @param root    the vcs root
     * @param files   the files to revert
     */
    private static void revertFiles(Project project, VirtualFile root, ArrayList<String> files) throws VcsException {
        // TODO consider deleted files
        GitRollbackEnvironment rollback = GitRollbackEnvironment.getInstance(project);
        ArrayList<FilePath> list = new ArrayList<>(files.size());
        for (String p : files) {
            list.add(VcsUtil.getFilePath(p));
        }
        rollback.revert(root, list);
    }
}
