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

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
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
import java.awt.*;
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
        String ch = "[\\p{ASCII}&&[\\p{Graph}]&&[^@:/]]";
        String host = ch + "+(?:\\." + ch + "+)*";
        String path = "/?" + ch + "+(?:/" + ch + "+)*/?";
        String all = "(?:" + ch + "+@)?" + host + ":" + path;
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
        $$$setupUI$$$();
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
            fcd.getTitleValue(),
            fcd.getDescriptionValue(),
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

        DocumentListener updateOkButtonListener = new DocumentAdapter() {
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

    @RequiredUIAccess
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
     * That way, we have a hack here: if http response asked for a password, then the url is at least valid and existent, and we consider
     * that the test passed.
     */
    @RequiredUIAccess
    private boolean test(String url) {
        GitLineHandlerPasswordRequestAware handler =
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
        GitRememberedInputs rememberedInputs = GitRememberedInputs.getInstance();
        myRepositoryURL.setHistory(ArrayUtil.toObjectArray(rememberedInputs.getVisitedUrls(), String.class));
        myRepositoryURL.addDocumentListener(new consulo.document.event.DocumentAdapter() {
            @Override
            public void documentChanged(consulo.document.event.DocumentEvent e) {
                // enable test button only if something is entered in repository URL
                String url = getCurrentUrlText();
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

    public void prependToHistory(String item) {
        myRepositoryURL.prependItem(item);
    }

    public void rememberSettings() {
        GitRememberedInputs rememberedInputs = GitRememberedInputs.getInstance();
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
    private static String defaultDirectoryName(String url) {
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

    /**
     * Method generated by Consulo GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        myRootPanel = new JPanel();
        myRootPanel.setLayout(new GridLayoutManager(5, 4, JBUI.emptyInsets(), -1, -1));
        JLabel label1 = new JLabel();
        this.$$$loadLabelText$$$(label1, GitLocalize.cloneRepositoryUrl().get());
        myRootPanel.add(
            label1,
            new GridConstraints(
                0,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        Spacer spacer1 = new Spacer();
        myRootPanel.add(
            spacer1,
            new GridConstraints(
                4,
                0,
                1,
                1,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_VERTICAL,
                1,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                null,
                null,
                null,
                0,
                false
            )
        );
        Spacer spacer2 = new Spacer();
        myRootPanel.add(
            spacer2,
            new GridConstraints(
                4,
                1,
                1,
                3,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                1,
                null,
                null,
                null,
                0,
                false
            )
        );
        myRootPanel.add(
            myRepositoryURL,
            new GridConstraints(
                0,
                1,
                1,
                2,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                new Dimension(150, -1),
                null,
                0,
                false
            )
        );
        JLabel label2 = new JLabel();
        this.$$$loadLabelText$$$(label2, GitLocalize.cloneParentDir().get());
        myRootPanel.add(
            label2,
            new GridConstraints(
                2,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myParentDirectory = new TextFieldWithBrowseButton();
        myRootPanel.add(
            myParentDirectory,
            new GridConstraints(
                2,
                1,
                1,
                3,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        JLabel label3 = new JLabel();
        this.$$$loadLabelText$$$(label3, GitLocalize.cloneDirName().get());
        myRootPanel.add(
            label3,
            new GridConstraints(
                3,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myTestButton = new JButton();
        this.$$$loadButtonText$$$(myTestButton, GitLocalize.cloneTest().get());
        myRootPanel.add(
            myTestButton,
            new GridConstraints(
                0,
                3,
                1,
                1,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myDirectoryName = new JTextField();
        myRootPanel.add(
            myDirectoryName,
            new GridConstraints(
                3,
                1,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                new Dimension(150, -1),
                null,
                0,
                false
            )
        );
        Spacer spacer3 = new Spacer();
        myRootPanel.add(
            spacer3,
            new GridConstraints(
                3,
                2,
                1,
                2,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                1,
                null,
                null,
                null,
                0,
                false
            )
        );
        myPuttyLabel = new JLabel();
        this.$$$loadLabelText$$$(myPuttyLabel, GitLocalize.cloneRepositoryPuttyKey().get());
        myRootPanel.add(
            myPuttyLabel,
            new GridConstraints(
                1,
                0,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        myPuttyKeyChooser = new TextFieldWithBrowseButton();
        myRootPanel.add(
            myPuttyKeyChooser,
            new GridConstraints(
                1,
                1,
                1,
                3,
                GridConstraints.ANCHOR_CENTER,
                GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null,
                0,
                false
            )
        );
        label1.setLabelFor(myRepositoryURL);
        label3.setLabelFor(myDirectoryName);
    }

    private void $$$loadLabelText$$$(JLabel component, String text) {
        StringBuilder result = new StringBuilder();
        boolean haveMnemonic = false;
        char mnemonic = '\0';
        int mnemonicIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') {
                i++;
                if (i == text.length()) {
                    break;
                }
                if (!haveMnemonic && text.charAt(i) != '&') {
                    haveMnemonic = true;
                    mnemonic = text.charAt(i);
                    mnemonicIndex = result.length();
                }
            }
            result.append(text.charAt(i));
        }
        component.setText(result.toString());
        if (haveMnemonic) {
            component.setDisplayedMnemonic(mnemonic);
            component.setDisplayedMnemonicIndex(mnemonicIndex);
        }
    }

    private void $$$loadButtonText$$$(AbstractButton component, String text) {
        StringBuilder result = new StringBuilder();
        boolean haveMnemonic = false;
        char mnemonic = '\0';
        int mnemonicIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') {
                i++;
                if (i == text.length()) {
                    break;
                }
                if (!haveMnemonic && text.charAt(i) != '&') {
                    haveMnemonic = true;
                    mnemonic = text.charAt(i);
                    mnemonicIndex = result.length();
                }
            }
            result.append(text.charAt(i));
        }
        component.setText(result.toString());
        if (haveMnemonic) {
            component.setMnemonic(mnemonic);
            component.setDisplayedMnemonicIndex(mnemonicIndex);
        }
    }

    public JComponent $$$getRootComponent$$$() {
        return myRootPanel;
    }
}
