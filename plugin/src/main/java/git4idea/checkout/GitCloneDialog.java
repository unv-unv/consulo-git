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
package git4idea.checkout;

import consulo.container.boot.ContainerPathManager;
import consulo.fileChooser.FileChooserDescriptor;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.git.localize.GitLocalize;
import consulo.language.editor.ui.awt.EditorComboBox;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.*;
import consulo.ui.ex.awt.event.DocumentAdapter;
import consulo.util.collection.ArrayUtil;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandlerPasswordRequestAware;
import git4idea.commands.GitTask;
import git4idea.commands.GitTaskResult;
import git4idea.config.GitVcsApplicationSettings;
import git4idea.remote.GitRememberedInputs;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * A dialog for the git clone options
 *
 * @author Constantine.Plotnikov
 */
public class GitCloneDialog extends DialogWrapper {
    /**
     * The pattern for SSH URL-s in form [user@]host:path
     */
    private static final Pattern SSH_URL_PATTERN;

    static {
        // TODO make real URL pattern
        final String ch = "[\\p{ASCII}&&[\\p{Graph}]&&[^@:/]]";
        final String host = ch + "+(?:\\." + ch + "+)*";
        final String path = "/?" + ch + "+(?:/" + ch + "+)*/?";
        final String all = "(?:" + ch + "+@)?" + host + ":" + path;
        SSH_URL_PATTERN = Pattern.compile(all);
    }

    private JPanel myRootPanel;
    private EditorComboBox myRepositoryURL;
    private TextFieldWithBrowseButton myParentDirectory;
    private JButton myTestButton; // test repository
    private JTextField myDirectoryName;
    private TextFieldWithBrowseButton myPuttyKeyChooser;
    private JLabel myPuttyLabel;

    private String myTestURL; // the repository URL at the time of the last test
    private Boolean myTestResult; // the test result of the last test or null if not tested
    private String myDefaultDirectoryName = "";
    private final Project myProject;

    public GitCloneDialog(Project project) {
        super(project, true);
        myProject = project;
        setTitle(GitLocalize.cloneTitle());
        setOKButtonText(GitLocalize.cloneButton());

        myPuttyKeyChooser.setVisible(GitVcsApplicationSettings.getInstance()
            .getSshExecutableType() == GitVcsApplicationSettings.SshExecutable.PUTTY);
        myPuttyLabel.setVisible(myPuttyKeyChooser.isVisible());
        init();
        initListeners();
    }

    public String getSourceRepositoryURL() {
        return getCurrentUrlText();
    }

    public String getParentDirectory() {
        return myParentDirectory.getText();
    }

    public String getPuttyKeyFile() {
        return StringUtil.nullize(myPuttyKeyChooser.getText());
    }

    public String getDirectoryName() {
        return myDirectoryName.getText();
    }

    /**
     * Init components
     */
    private void initListeners() {
        FileChooserDescriptor singleFileDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor();
        myPuttyKeyChooser.addActionListener(new ComponentWithBrowseButton.BrowseFolderActionListener<>(
            singleFileDescriptor.getTitle(),
            singleFileDescriptor.getDescription(),
            myPuttyKeyChooser,
            myProject,
            singleFileDescriptor,
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        ));

        FileChooserDescriptor fcd = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withShowFileSystemRoots(true)
            .withTitleValue(GitLocalize.cloneDestinationDirectoryTitle())
            .withDescriptionValue(GitLocalize.cloneDestinationDirectoryDescription())
            .withHideIgnored(false);

        myParentDirectory.addActionListener(new ComponentWithBrowseButton.BrowseFolderActionListener<>(
            fcd.getTitle(),
            fcd.getDescription(),
            myParentDirectory,
            myProject,
            fcd,
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        ) {
            @Override
            protected VirtualFile getInitialFile() {
                // suggest project base directory only if nothing is typed in the component.
                String text = getComponentText();
                if (text.length() == 0) {
                    VirtualFile file = myProject.getBaseDir();
                    if (file != null) {
                        return file;
                    }
                }
                return super.getInitialFile();
            }
        });

        final DocumentListener updateOkButtonListener = new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                updateButtons();
            }
        };
        myParentDirectory.getChildComponent().getDocument().addDocumentListener(updateOkButtonListener);
        String parentDir = GitRememberedInputs.getInstance().getCloneParentDir();
        if (StringUtil.isEmptyOrSpaces(parentDir)) {
            parentDir = ContainerPathManager.get().getDocumentsDir().getPath();
        }
        myParentDirectory.setText(parentDir);

        myDirectoryName.getDocument().addDocumentListener(updateOkButtonListener);

        myTestButton.addActionListener(e -> test());

        setOKActionEnabled(false);
        myTestButton.setEnabled(false);
    }

    private void test() {
        myTestURL = getCurrentUrlText();
        boolean testResult = test(myTestURL);

        if (testResult) {
            Messages.showInfoMessage(
                myTestButton,
                GitLocalize.cloneTestSuccessMessage(myTestURL).get(),
                GitLocalize.cloneTestConnectionTitle().get()
            );
            myTestResult = Boolean.TRUE;
        }
        else {
            myTestResult = Boolean.FALSE;
        }
        updateButtons();
    }

    /*
     * JGit doesn't have ls-remote command independent from repository yet.
     * That way, we have a hack here: if http response asked for a password, then the url is at least valid and existant, and we consider
     * that the test passed.
     */
    private boolean test(String url) {
        final GitLineHandlerPasswordRequestAware handler =
            new GitLineHandlerPasswordRequestAware(myProject, new File("."), GitCommand.LS_REMOTE);
        handler.setPuttyKey(getPuttyKeyFile());
        handler.setUrl(url);
        handler.addParameters(url, "master");
        GitTask task = new GitTask(myProject, handler, GitLocalize.cloneTesting(url));
        GitTaskResult result = task.executeModal();
        boolean authFailed = handler.hadAuthRequest();
        return result.isOK() || authFailed;
    }

    /**
     * Check fields and display error in the wrapper if there is a problem
     */
    private void updateButtons() {
        if (!checkRepositoryURL()) {
            return;
        }
        if (!checkDestination()) {
            return;
        }
        clearErrorText();
        setOKActionEnabled(true);
    }

    /**
     * Check destination directory and set appropriate error text if there are problems
     *
     * @return true if destination components are OK.
     */
    private boolean checkDestination() {
        if (myParentDirectory.getText().length() == 0 || myDirectoryName.getText().length() == 0) {
            clearErrorText();
            setOKActionEnabled(false);
            return false;
        }
        File file = new File(myParentDirectory.getText(), myDirectoryName.getText());
        if (file.exists()) {
            setErrorText(GitLocalize.cloneDestinationExistsError(file));
            setOKActionEnabled(false);
            return false;
        }
        else if (!file.getParentFile().exists()) {
            setErrorText(GitLocalize.cloneParentMissingError(file.getParent()));
            setOKActionEnabled(false);
            return false;
        }
        return true;
    }

    /**
     * Check repository URL and set appropriate error text if there are problems
     *
     * @return true if repository URL is OK.
     */
    private boolean checkRepositoryURL() {
        String repository = getCurrentUrlText();
        if (repository.isEmpty()) {
            clearErrorText();
            setOKActionEnabled(false);
            return false;
        }
        if (myTestResult != null && repository.equals(myTestURL)) {
            if (!myTestResult) {
                setErrorText(GitLocalize.cloneTestFailedError());
                setOKActionEnabled(false);
                return false;
            }
            else {
                return true;
            }
        }
        try {
            if (new URI(repository).isAbsolute()) {
                return true;
            }
        }
        catch (URISyntaxException urlExp) {
            // do nothing
        }
        // check if ssh url pattern
        if (SSH_URL_PATTERN.matcher(repository).matches()) {
            return true;
        }
        try {
            File file = new File(repository);
            if (file.exists()) {
                if (!file.isDirectory()) {
                    setErrorText(GitLocalize.cloneUrlIsNotDirectoryError());
                    setOKActionEnabled(false);
                }
                return true;
            }
        }
        catch (Exception fileExp) {
            // do nothing
        }
        setErrorText(GitLocalize.cloneInvalidUrl());
        setOKActionEnabled(false);
        return false;
    }

    private String getCurrentUrlText() {
        return myRepositoryURL.getText().trim();
    }

    private void createUIComponents() {
        myRepositoryURL = new EditorComboBox("");
        final GitRememberedInputs rememberedInputs = GitRememberedInputs.getInstance();
        myRepositoryURL.setHistory(ArrayUtil.toObjectArray(rememberedInputs.getVisitedUrls(), String.class));
        myRepositoryURL.addDocumentListener(new consulo.document.event.DocumentAdapter() {
            @Override
            public void documentChanged(consulo.document.event.DocumentEvent e) {
                // enable test button only if something is entered in repository URL
                final String url = getCurrentUrlText();
                myTestButton.setEnabled(url.length() != 0);
                if (myDefaultDirectoryName.equals(myDirectoryName.getText()) || myDirectoryName.getText().length() == 0) {
                    // modify field if it was unmodified or blank
                    myDefaultDirectoryName = defaultDirectoryName(url);
                    myDirectoryName.setText(myDefaultDirectoryName);
                }
                updateButtons();
            }
        });
    }

    public void prependToHistory(final String item) {
        myRepositoryURL.prependItem(item);
    }

    public void rememberSettings() {
        final GitRememberedInputs rememberedInputs = GitRememberedInputs.getInstance();
        rememberedInputs.addUrl(getSourceRepositoryURL());
        rememberedInputs.setCloneParentDir(getParentDirectory());
        rememberedInputs.setPuttyKey(getPuttyKeyFile());
    }

    /**
     * Get default name for checked out directory
     *
     * @param url an URL to checkout
     * @return a default repository name
     */
    private static String defaultDirectoryName(final String url) {
        String nonSystemName;
        if (url.endsWith("/" + GitUtil.DOT_GIT) || url.endsWith(File.separator + GitUtil.DOT_GIT)) {
            nonSystemName = url.substring(0, url.length() - 5);
        }
        else {
            if (url.endsWith(GitUtil.DOT_GIT)) {
                nonSystemName = url.substring(0, url.length() - 4);
            }
            else {
                nonSystemName = url;
            }
        }
        int i = nonSystemName.lastIndexOf('/');
        if (i == -1 && File.separatorChar != '/') {
            i = nonSystemName.lastIndexOf(File.separatorChar);
        }
        return i >= 0 ? nonSystemName.substring(i + 1) : "";
    }

    @Override
    protected JComponent createCenterPanel() {
        return myRootPanel;
    }

    @Override
    protected String getDimensionServiceKey() {
        return "GitCloneDialog";
    }

    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myRepositoryURL;
    }

    @Override
    protected String getHelpId() {
        return "reference.VersionControl.Git.CloneRepository";
    }
}
