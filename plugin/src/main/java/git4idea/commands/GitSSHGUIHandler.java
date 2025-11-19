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
package git4idea.commands;

import consulo.application.Application;
import consulo.credentialStorage.ui.PasswordSafePromptDialog;
import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.awt.DialogWrapper;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.UIUtil;
import git4idea.config.SSHConnectionSettings;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Swing GUI handler for the SSH events
 */
public class GitSSHGUIHandler {
    @Nonnull
    private final Project myProject;

    GitSSHGUIHandler(@Nonnull Project project) {
        myProject = project;
    }

    public boolean verifyServerHostKey(
        final String hostname,
        final int port,
        final String serverHostKeyAlgorithm,
        final String fingerprint,
        final boolean isNew
    ) {
        final LocalizeValue message = isNew
            ? GitLocalize.sshNewHostKey(hostname, port, fingerprint, serverHostKeyAlgorithm)
            : GitLocalize.sshChangedHostKey(hostname, port, fingerprint, serverHostKeyAlgorithm);
        final AtomicBoolean rc = new AtomicBoolean();
        Application.get().invokeAndWait(
            () -> rc.set(Messages.YES == Messages.showYesNoDialog(myProject, message.get(), GitLocalize.sshConfirmKeyTitile().get(), null)),
            Application.get().getAnyModalityState()
        );
        return rc.get();
    }

    @Nullable
    public String askPassphrase(final String username, final String keyPath, boolean resetPassword, final String lastError) {
        String error = processLastError(resetPassword, lastError);
        PasswordSafePromptDialog passwordSafePromptDialog = myProject.getInstance(PasswordSafePromptDialog.class);
        return passwordSafePromptDialog.askPassphrase(
            GitLocalize.sshAskPassphraseTitle().get(),
            GitLocalize.sshAskpassphraseMessage(keyPath, username).get(),
            GitSSHGUIHandler.class,
            "PASSPHRASE:" + keyPath,
            resetPassword,
            error
        );
    }

    /**
     * Process the last error
     *
     * @param resetPassword true, if last entered password was incorrect
     * @param lastError     the last error
     * @return the error to show on the password dialog or null
     */
    @Nullable
    private String processLastError(boolean resetPassword, final String lastError) {
        String error;
        if (lastError != null && lastError.length() != 0 && !resetPassword) {
            Application application = Application.get();
            application.invokeAndWait(() -> showError(lastError), application.getAnyModalityState());
            error = null;
        }
        else {
            error = lastError != null && lastError.length() == 0 ? null : lastError;
        }
        return error;
    }

    @RequiredUIAccess
    private void showError(final String lastError) {
        if (lastError.length() != 0) {
            Messages.showErrorDialog(myProject, lastError, GitLocalize.sshErrorTitle().get());
        }
    }

    /**
     * Reply to challenge in keyboard-interactive scenario
     *
     * @param username    a user name
     * @param name        a name of challenge
     * @param instruction a instructions
     * @param numPrompts  number of prompts
     * @param prompt      prompts
     * @param echo        true if the reply for corresponding prompt should be echoed
     * @param lastError   the last error
     * @return replies to the challenges
     */
    @SuppressWarnings({
        "UseOfObsoleteCollectionType",
        "unchecked"
    })
    public Vector<String> replyToChallenge(
        final String username,
        final String name,
        final String instruction,
        final int numPrompts,
        final Vector<String> prompt,
        final Vector<Boolean> echo,
        final String lastError
    ) {
        final AtomicReference<Vector<String>> rc = new AtomicReference<>();
        Application application = Application.get();
        application.invokeAndWait(
            () -> {
                showError(lastError);
                GitSSHKeyboardInteractiveDialog dialog =
                    new GitSSHKeyboardInteractiveDialog(name, numPrompts, instruction, prompt, echo, username);
                if (dialog.showAndGet()) {
                    rc.set(dialog.getResults());
                }
            },
            application.getAnyModalityState()
        );
        return rc.get();
    }

    /**
     * Ask password
     *
     * @param username      a user name
     * @param resetPassword true if the previous password supplied to the service was incorrect
     * @param lastError     the previous error  @return a password or null if dialog was cancelled.
     */
    @Nullable
    public String askPassword(final String username, boolean resetPassword, final String lastError) {
        String error = processLastError(resetPassword, lastError);
        PasswordSafePromptDialog passwordSafePromptDialog = myProject.getInstance(PasswordSafePromptDialog.class);
        return passwordSafePromptDialog.askPassword(
            GitLocalize.sshPasswordTitle().get(),
            GitLocalize.sshPasswordMessage(username).get(),
            GitSSHGUIHandler.class,
            "PASSWORD:" + username,
            resetPassword,
            error
        );
    }

    /**
     * Get last successful authentication method. The default implementation returns empty string
     * meaning that last authentication is unknown or failed.
     *
     * @param userName the user name
     * @return the successful authentication method
     */
    public String getLastSuccessful(String userName) {
        SSHConnectionSettings s = SSHConnectionSettings.getInstance();
        String rc = s.getLastSuccessful(userName);
        return rc == null ? "" : rc;
    }

    /**
     * Set last successful authentication method
     *
     * @param userName the user name
     * @param method   the authentication method, the empty string if authentication process failed.
     * @param error    the error to show to user in case when authentication process failed.
     */
    public void setLastSuccessful(String userName, String method, final String error) {
        SSHConnectionSettings s = SSHConnectionSettings.getInstance();
        s.setLastSuccessful(userName, method);
        if (error != null && error.length() != 0) {
            UIUtil.invokeLaterIfNeeded(() -> showError(error));
        }
    }

    @SuppressWarnings({"UseOfObsoleteCollectionType"})
    private class GitSSHKeyboardInteractiveDialog extends DialogWrapper {
        /**
         * input fields
         */
        JTextComponent[] inputs;
        /**
         * root panel
         */
        JPanel contents;
        /**
         * number of prompts
         */
        private final int myNumPrompts;
        /**
         * Instructions
         */
        private final String myInstruction;
        /**
         * Prompts
         */
        private final Vector<String> myPrompt;
        /**
         * Array of echo values
         */
        private final Vector<Boolean> myEcho;
        /**
         * A name of user
         */
        private final String myUserName;

        public GitSSHKeyboardInteractiveDialog(
            String name,
            final int numPrompts,
            final String instruction,
            final Vector<String> prompt,
            final Vector<Boolean> echo,
            final String userName
        ) {
            super(myProject, true);
            myNumPrompts = numPrompts;
            myInstruction = instruction;
            myPrompt = prompt;
            myEcho = echo;
            myUserName = userName;
            setTitle(GitLocalize.sshKeyboardInteractiveTitle(name));
            init();
            setResizable(true);
            setModal(true);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected JComponent createCenterPanel() {
            if (contents == null) {
                int line = 0;
                contents = new JPanel(new GridBagLayout());
                inputs = new JTextComponent[myNumPrompts];
                GridBagConstraints c;
                Insets insets = new Insets(1, 1, 1, 1);
                if (myInstruction.length() != 0) {
                    JLabel instructionLabel = new JLabel(myInstruction);
                    c = new GridBagConstraints();
                    c.insets = insets;
                    c.gridx = 0;
                    c.gridy = line;
                    c.gridwidth = 2;
                    c.weightx = 1;
                    c.fill = GridBagConstraints.HORIZONTAL;
                    c.anchor = GridBagConstraints.WEST;
                    line++;
                    contents.add(instructionLabel, c);
                }
                c = new GridBagConstraints();
                c.insets = insets;
                c.anchor = GridBagConstraints.WEST;
                c.gridx = 0;
                c.gridy = line;
                contents.add(new JLabel(GitLocalize.sshKeyboardInteractiveUsername().get()), c);
                c = new GridBagConstraints();
                c.insets = insets;
                c.gridx = 1;
                c.gridy = line;
                c.gridwidth = 1;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.anchor = GridBagConstraints.WEST;
                contents.add(new JLabel(myUserName), c);
                line++;
                for (int i = 0; i < myNumPrompts; i++) {
                    c = new GridBagConstraints();
                    c.insets = insets;
                    c.anchor = GridBagConstraints.WEST;
                    c.gridx = 0;
                    c.gridy = line;
                    JLabel promptLabel = new JLabel(myPrompt.get(i));
                    contents.add(promptLabel, c);
                    c = new GridBagConstraints();
                    c.insets = insets;
                    c.gridx = 1;
                    c.gridy = line;
                    c.gridwidth = 1;
                    c.weightx = 1;
                    c.fill = GridBagConstraints.HORIZONTAL;
                    c.anchor = GridBagConstraints.WEST;
                    inputs[i] = myEcho.get(i) ? new JTextField(32) : new JPasswordField(32);
                    contents.add(inputs[i], c);
                    line++;
                }
                c = new GridBagConstraints();
                c.gridx = 0;
                c.gridy = line;
                c.gridwidth = 1;
                c.weighty = 1;
                c.fill = GridBagConstraints.VERTICAL;
                c.anchor = GridBagConstraints.CENTER;
                contents.add(new JPanel(), c);
            }
            return contents;
        }

        /**
         * {@inheritDoc}
         */
        @Nonnull
        @Override
        protected Action[] createActions() {
            return new Action[]{
                getOKAction(),
                getCancelAction()
            };
        }

        /**
         * @return text entered at prompt
         */
        @SuppressWarnings({"UseOfObsoleteCollectionType"})
        public Vector<String> getResults() {
            Vector<String> rc = new Vector<>(myNumPrompts);
            for (int i = 0; i < myNumPrompts; i++) {
                rc.add(inputs[i].getText());
            }
            return rc;
        }

        @Override
        @RequiredUIAccess
        public JComponent getPreferredFocusedComponent() {
            return inputs.length > 0 ? inputs[0] : super.getPreferredFocusedComponent();
        }
    }
}
