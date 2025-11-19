/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

import consulo.component.ProcessCanceledException;
import consulo.container.plugin.PluginManager;
import consulo.http.HttpProxyManager;
import consulo.ide.ServiceManager;
import consulo.logging.Logger;
import consulo.platform.Platform;
import consulo.process.ExecutionException;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.local.EnvironmentUtil;
import consulo.project.Project;
import consulo.proxy.EventDispatcher;
import consulo.util.collection.ContainerUtil;
import consulo.util.dataholder.Key;
import consulo.util.io.FileUtil;
import consulo.util.lang.ObjectUtil;
import consulo.util.lang.StringUtil;
import consulo.versionControlSystem.FilePath;
import consulo.versionControlSystem.ProcessEventListener;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsLocaleHelper;
import consulo.versionControlSystem.util.VcsFileUtil;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import git4idea.GitVcs;
import git4idea.config.GitExecutableManager;
import git4idea.config.GitVcsApplicationSettings;
import git4idea.config.GitVcsSettings;
import git4idea.config.GitVersionSpecialty;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jetbrains.git4idea.rt.http.GitAskPassXmlRpcHandler;
import org.jetbrains.git4idea.rt.ssh.GitSSHHandler;
import org.jetbrains.git4idea.ssh.GitXmlRpcSshService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

import static git4idea.commands.GitCommand.LockingPolicy.WRITE;
import static java.util.Collections.singletonList;

/**
 * A handler for git commands
 */
public abstract class GitHandler {
    protected static final Logger LOG = Logger.getInstance(GitHandler.class);
    protected static final Logger OUTPUT_LOG = Logger.getInstance("#output." + GitHandler.class.getName());
    private static final Logger TIME_LOG = Logger.getInstance("#time." + GitHandler.class.getName());

    @Nonnull
    protected final Project myProject;
    protected final GitCommand myCommand;

    private final HashSet<Integer> myIgnoredErrorCodes = new HashSet<>(); // Error codes that are ignored for the handler
    private final List<VcsException> myErrors = Collections.synchronizedList(new ArrayList<VcsException>());
    private final List<String> myLastOutput = Collections.synchronizedList(new ArrayList<String>());
    private final int LAST_OUTPUT_SIZE = 5;
    final GeneralCommandLine myCommandLine;
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    Process myProcess;

    private boolean myStdoutSuppressed; // If true, the standard output is not copied to version control console
    private boolean myStderrSuppressed; // If true, the standard error is not copied to version control console
    private final File myWorkingDirectory;

    private boolean myEnvironmentCleanedUp = true;
    // the flag indicating that environment has been cleaned up, by default is true because there is nothing to clean
    private UUID mySshHandler;
    private UUID myHttpHandler;
    private Predicate<OutputStream> myInputProcessor; // The processor for stdin

    // if true process might be cancelled
    // note that access is safe because it accessed in unsynchronized block only after process is started, and it does not change after that
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    private boolean myIsCancellable = true;

    private Integer myExitCode; // exit code or null if exit code is not yet available

    @Nonnull
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    private Charset myCharset = StandardCharsets.UTF_8; // Character set to use for IO

    private final EventDispatcher<ProcessEventListener> myListeners = EventDispatcher.create(ProcessEventListener.class);
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    protected boolean mySilent; // if true, the command execution is not logged in version control view

    protected final GitVcs myVcs;
    private final Map<String, String> myEnv;
    private GitVcsApplicationSettings myAppSettings;
    private GitVcsSettings myProjectSettings;

    private long myStartTime; // git execution start timestamp
    private static final long LONG_TIME = 10 * 1000;
    @Nullable
    private Collection<String> myUrls;
    @Nullable
    private String myPuttyKey;
    private boolean myHttpAuthFailed;

    /**
     * A constructor
     *
     * @param project   a project
     * @param directory a process directory
     * @param command   a command to execute (if empty string, the parameter is ignored)
     */
    protected GitHandler(@Nonnull Project project, @Nonnull File directory, @Nonnull GitCommand command) {
        myProject = project;
        myCommand = command;
        myAppSettings = GitVcsApplicationSettings.getInstance();
        myProjectSettings = GitVcsSettings.getInstance(myProject);
        myEnv = new HashMap<>(EnvironmentUtil.getEnvironmentMap());
        myVcs = ObjectUtil.assertNotNull(GitVcs.getInstance(project));
        myWorkingDirectory = directory;
        myCommandLine = new GeneralCommandLine();
        if (myAppSettings != null) {
            myCommandLine.setExePath(GitExecutableManager.getInstance().getPathToGit(project));
        }
        myCommandLine.setWorkDirectory(myWorkingDirectory);
        if (GitVersionSpecialty.CAN_OVERRIDE_GIT_CONFIG_FOR_COMMAND.existsIn(myVcs.getVersion())) {
            myCommandLine.addParameters("-c", "core.quotepath=false");
        }
        myCommandLine.addParameter(command.name());
        myStdoutSuppressed = true;
        mySilent = myCommand.lockingPolicy() == GitCommand.LockingPolicy.READ;
    }

    /**
     * A constructor
     *
     * @param project a project
     * @param vcsRoot a process directory
     * @param command a command to execute
     */
    protected GitHandler(Project project, VirtualFile vcsRoot, GitCommand command) {
        this(project, VirtualFileUtil.virtualToIoFile(vcsRoot), command);
    }

    /**
     * @return multicaster for listeners
     */
    protected ProcessEventListener listeners() {
        return myListeners.getMulticaster();
    }

    /**
     * Add error code to ignored list
     *
     * @param code the code to ignore
     */
    public void ignoreErrorCode(int code) {
        myIgnoredErrorCodes.add(code);
    }

    /**
     * Check if error code should be ignored
     *
     * @param code a code to check
     * @return true if error code is ignorable
     */
    public boolean isIgnoredErrorCode(int code) {
        return myIgnoredErrorCodes.contains(code);
    }

    /**
     * add error to the error list
     *
     * @param ex an error to add to the list
     */
    public void addError(VcsException ex) {
        myErrors.add(ex);
    }

    public void addLastOutput(String line) {
        if (myLastOutput.size() < LAST_OUTPUT_SIZE) {
            myLastOutput.add(line);
        }
        else {
            myLastOutput.add(0, line);
            Collections.rotate(myLastOutput, -1);
        }
    }

    public List<String> getLastOutput() {
        return myLastOutput;
    }

    /**
     * @return unmodifiable list of errors.
     */
    public List<VcsException> errors() {
        return Collections.unmodifiableList(myErrors);
    }

    /**
     * @return a context project
     */
    public Project project() {
        return myProject;
    }

    /**
     * @return the current working directory
     */
    public File workingDirectory() {
        return myWorkingDirectory;
    }

    /**
     * @return the current working directory
     */
    public VirtualFile workingDirectoryFile() {
        VirtualFile file = LocalFileSystem.getInstance().findFileByIoFile(workingDirectory());
        if (file == null) {
            throw new IllegalStateException("The working directly should be available: " + workingDirectory());
        }
        return file;
    }

    public void setPuttyKey(@Nullable String key) {
        myPuttyKey = key;
    }

    public void setUrl(@Nonnull String url) {
        setUrls(singletonList(url));
    }

    public void setUrls(@Nonnull Collection<String> urls) {
        myUrls = urls;
    }

    protected boolean isRemote() {
        return myUrls != null;
    }

    /**
     * Add listener to handler
     *
     * @param listener a listener
     */
    protected void addListener(ProcessEventListener listener) {
        myListeners.addListener(listener);
    }

    /**
     * End option parameters and start file paths. The method adds {@code "--"} parameter.
     */
    public void endOptions() {
        myCommandLine.addParameter("--");
    }

    /**
     * Add string parameters
     *
     * @param parameters a parameters to add
     */
    @SuppressWarnings({"WeakerAccess"})
    public void addParameters(@Nonnull String... parameters) {
        addParameters(Arrays.asList(parameters));
    }

    /**
     * Add parameters from the list
     *
     * @param parameters the parameters to add
     */
    public void addParameters(List<String> parameters) {
        checkNotStarted();
        for (String parameter : parameters) {
            myCommandLine.addParameter(escapeParameterIfNeeded(parameter));
        }
    }

    @Nonnull
    private String escapeParameterIfNeeded(@Nonnull String parameter) {
        if (escapeNeeded(parameter)) {
            return parameter.replaceAll("\\^", "^^^^");
        }
        return parameter;
    }

    private boolean escapeNeeded(@Nonnull String parameter) {
        return Platform.current().os().isWindows() && isCmd() && parameter.contains("^");
    }

    private boolean isCmd() {
        return myAppSettings.getPathToGit().toLowerCase().endsWith("cmd");
    }

    @Nonnull
    private String unescapeCommandLine(@Nonnull String commandLine) {
        if (escapeNeeded(commandLine)) {
            return commandLine.replaceAll("\\^\\^\\^\\^", "^");
        }
        return commandLine;
    }

    /**
     * Add file path parameters. The parameters are made relative to the working directory
     *
     * @param parameters a parameters to add
     * @throws IllegalArgumentException if some path is not under root.
     */
    public void addRelativePaths(@Nonnull FilePath... parameters) {
        addRelativePaths(Arrays.asList(parameters));
    }

    /**
     * Add file path parameters. The parameters are made relative to the working directory
     *
     * @param filePaths a parameters to add
     * @throws IllegalArgumentException if some path is not under root.
     */
    @SuppressWarnings({"WeakerAccess"})
    public void addRelativePaths(@Nonnull Collection<FilePath> filePaths) {
        checkNotStarted();
        for (FilePath path : filePaths) {
            myCommandLine.addParameter(VcsFileUtil.relativePath(myWorkingDirectory, path));
        }
    }

    /**
     * Add virtual file parameters. The parameters are made relative to the working directory
     *
     * @param files a parameters to add
     * @throws IllegalArgumentException if some path is not under root.
     */
    @SuppressWarnings({"WeakerAccess"})
    public void addRelativeFiles(@Nonnull Collection<VirtualFile> files) {
        checkNotStarted();
        for (VirtualFile file : files) {
            myCommandLine.addParameter(VcsFileUtil.relativePath(myWorkingDirectory, file));
        }
    }

    /**
     * Adds "--progress" parameter. Usable for long operations, such as clone or fetch.
     *
     * @return is "--progress" parameter supported by this version of Git.
     */
    public boolean addProgressParameter() {
        if (GitVersionSpecialty.ABLE_TO_USE_PROGRESS_IN_REMOTE_COMMANDS.existsIn(myVcs.getVersion())) {
            addParameters("--progress");
            return true;
        }
        return false;
    }

    /**
     * check that process is not started yet
     *
     * @throws IllegalStateException if process has been already started
     */
    private void checkNotStarted() {
        if (isStarted()) {
            throw new IllegalStateException("The process has been already started");
        }
    }

    /**
     * check that process is started
     *
     * @throws IllegalStateException if process has not been started
     */
    protected final void checkStarted() {
        if (!isStarted()) {
            throw new IllegalStateException("The process is not started yet");
        }
    }

    /**
     * @return true if process is started
     */
    public final synchronized boolean isStarted() {
        return myProcess != null;
    }

    /**
     * Set new value of cancellable flag (by default true)
     *
     * @param value a new value of the flag
     */
    public void setCancellable(boolean value) {
        checkNotStarted();
        myIsCancellable = value;
    }

    /**
     * @return cancellable state
     */
    public boolean isCancellable() {
        return myIsCancellable;
    }

    /**
     * Start process
     */
    public synchronized void start() {
        checkNotStarted();

        try {
            myStartTime = System.currentTimeMillis();
            if (!myProject.isDefault() && !mySilent && (myVcs != null)) {
                myVcs.showCommandLine("[" + stringifyWorkingDir() + "] " + printableCommandLine());
                LOG.info("[" + stringifyWorkingDir() + "] " + printableCommandLine());
            }
            else {
                LOG.debug("[" + stringifyWorkingDir() + "] " + printableCommandLine());
            }

            // setup environment
            if (isRemote()) {
                switch (myProjectSettings.getAppSettings().getSshExecutableType()) {
                    case IDEA_SSH:
                        setupSshAuthenticator();
                        break;
                    case NATIVE_SSH:
                        setupHttpAuthenticator();
                        break;
                    case PUTTY:
                        setupPuttyAuthenticator();
                        break;
                }
            }
            setUpLocale();
            unsetGitTrace();
            myCommandLine.getEnvironment().clear();
            myCommandLine.getEnvironment().putAll(myEnv);
            // start process
            myProcess = startProcess();
            startHandlingStreams();
        }
        catch (ProcessCanceledException pce) {
            cleanupEnv();
        }
        catch (Throwable t) {
            if (!myProject.getApplication().isUnitTestMode() || !myProject.isDisposed()) {
                LOG.error(t); // will surely happen if called during unit test disposal, because the working dir is simply removed then
            }
            cleanupEnv();
            myListeners.getMulticaster().startFailed(t);
        }
    }

    private void setUpLocale() {
        myEnv.putAll(VcsLocaleHelper.getDefaultLocaleEnvironmentVars("git"));
    }

    private void unsetGitTrace() {
        myEnv.put("GIT_TRACE", "0");
    }

    private void setupHttpAuthenticator() throws IOException {
        GitHttpAuthService service = ServiceManager.getService(GitHttpAuthService.class);
        myEnv.put(GitAskPassXmlRpcHandler.GIT_ASK_PASS_ENV, service.getScriptPath().getPath());
        GitHttpAuthenticator httpAuthenticator = service.createAuthenticator(myProject, myCommand, ObjectUtil.assertNotNull(myUrls));
        myHttpHandler = service.registerHandler(httpAuthenticator, myProject);
        myEnvironmentCleanedUp = false;
        myEnv.put(GitAskPassXmlRpcHandler.GIT_ASK_PASS_HANDLER_ENV, myHttpHandler.toString());
        int port = service.getXmlRcpPort();
        myEnv.put(GitAskPassXmlRpcHandler.GIT_ASK_PASS_PORT_ENV, Integer.toString(port));
        LOG.debug(String.format("handler=%s, port=%s", myHttpHandler, port));
        addAuthListener(httpAuthenticator);
    }

    private void setupPuttyAuthenticator() {
        Collection<String> urls = ObjectUtil.assertNotNull(myUrls);
        String url = ContainerUtil.getFirstItem(urls);

        GitRemoteProtocol remoteProtocol = GitRemoteProtocol.fromUrl(url);
        if (remoteProtocol != null) {
            myEnv.put(GitSSHHandler.GIT_SSH_ENV, new File(PluginManager.getPluginPath(Git.class), "putty/plink.exe").getAbsolutePath());
            StringBuilder builder = new StringBuilder();
            builder.append("-noagent ");
            if (myPuttyKey != null) {
                builder.append("-i ").append(FileUtil.toSystemDependentName(myPuttyKey));
            }
            else {
                throw new ProcessCanceledException();
            }
            myEnv.put("PLINK_ARGS", builder.toString());
        }
    }

    private void setupSshAuthenticator() throws IOException {
        GitXmlRpcSshService ssh = ServiceManager.getService(GitXmlRpcSshService.class);
        myEnv.put(GitSSHHandler.GIT_SSH_ENV, ssh.getScriptPath().getPath());
        mySshHandler = ssh.registerHandler(new GitSSHGUIHandler(myProject), myProject);
        myEnvironmentCleanedUp = false;
        myEnv.put(GitSSHHandler.SSH_HANDLER_ENV, mySshHandler.toString());
        int port = ssh.getXmlRcpPort();
        myEnv.put(GitSSHHandler.SSH_PORT_ENV, Integer.toString(port));
        LOG.debug(String.format("handler=%s, port=%s", mySshHandler, port));

        HttpProxyManager httpProxyManager = HttpProxyManager.getInstance();
        boolean useHttpProxy =
            httpProxyManager.isHttpProxyEnabled() && !isSshUrlExcluded(httpProxyManager, ObjectUtil.assertNotNull(myUrls));
        myEnv.put(GitSSHHandler.SSH_USE_PROXY_ENV, String.valueOf(useHttpProxy));

        if (useHttpProxy) {
            myEnv.put(GitSSHHandler.SSH_PROXY_HOST_ENV, StringUtil.notNullize(httpProxyManager.getProxyHost()));
            myEnv.put(GitSSHHandler.SSH_PROXY_PORT_ENV, String.valueOf(httpProxyManager.getProxyPort()));
            boolean proxyAuthentication = httpProxyManager.isProxyAuthenticationEnabled();
            myEnv.put(GitSSHHandler.SSH_PROXY_AUTHENTICATION_ENV, String.valueOf(proxyAuthentication));

            if (proxyAuthentication) {
                myEnv.put(GitSSHHandler.SSH_PROXY_USER_ENV, StringUtil.notNullize(httpProxyManager.getProxyLogin()));
                myEnv.put(GitSSHHandler.SSH_PROXY_PASSWORD_ENV, StringUtil.notNullize(httpProxyManager.getPlainProxyPassword()));
            }
        }
    }

    protected static boolean isSshUrlExcluded(@Nonnull HttpProxyManager httpProxyManager, @Nonnull Collection<String> urls) {
        return ContainerUtil.exists(urls, url -> !httpProxyManager.isHttpProxyEnabledForUrl(url));
    }

    private void addAuthListener(@Nonnull final GitHttpAuthenticator authenticator) {
        // TODO this code should be located in GitLineHandler, and the other remote code should be move there as well
        if (this instanceof GitLineHandler lineHandler) {
            lineHandler.addLineListener(new GitLineHandlerAdapter() {
                @Override
                public void onLineAvailable(String line, Key outputType) {
                    String lowerCaseLine = line.toLowerCase();
                    if (lowerCaseLine.contains("authentication failed") || lowerCaseLine.contains("403 forbidden")) {
                        LOG.debug("auth listener: auth failure detected: " + line);
                        myHttpAuthFailed = true;
                    }
                }

                @Override
                public void processTerminated(int exitCode) {
                    LOG.debug("auth listener: process terminated. auth failed=" + myHttpAuthFailed + ", cancelled=" + authenticator.wasCancelled());
                    if (authenticator.wasCancelled()) {
                        myHttpAuthFailed = false;
                    }
                    else if (myHttpAuthFailed) {
                        authenticator.forgetPassword();
                    }
                    else {
                        authenticator.saveAuthData();
                    }
                }
            });
        }
    }

    public boolean hasHttpAuthFailed() {
        return myHttpAuthFailed;
    }

    protected abstract Process startProcess() throws ExecutionException;

    /**
     * Start handling process output streams for the handler.
     */
    protected abstract void startHandlingStreams();

    /**
     * @return a command line with full path to executable replace to "git"
     */
    public String printableCommandLine() {
        return unescapeCommandLine(myCommandLine.getCommandLineString("git"));
    }

    /**
     * Cancel activity
     */
    public synchronized void cancel() {
        checkStarted();
        if (!myIsCancellable) {
            throw new IllegalStateException("The process is not cancellable.");
        }
        destroyProcess();
    }

    /**
     * Destroy process
     */
    public abstract void destroyProcess();

    /**
     * @return exit code for process if it is available
     */
    public synchronized int getExitCode() {
        if (myExitCode == null) {
            throw new IllegalStateException("Exit code is not yet available");
        }
        return myExitCode;
    }

    /**
     * @param exitCode a exit code for process
     */
    protected synchronized void setExitCode(int exitCode) {
        if (myExitCode == null) {
            myExitCode = exitCode;
        }
        else {
            LOG.info("Not setting exit code " + exitCode + ", because it was already set to " + myExitCode);
        }
    }

    /**
     * Cleanup environment
     */
    protected synchronized void cleanupEnv() {
        if (myEnvironmentCleanedUp) {
            return;
        }
        if (mySshHandler != null) {
            ServiceManager.getService(GitXmlRpcSshService.class).unregisterHandler(mySshHandler);
        }
        if (myHttpHandler != null) {
            ServiceManager.getService(GitHttpAuthService.class).unregisterHandler(myHttpHandler);
        }
        myEnvironmentCleanedUp = true;
    }

    /**
     * Wait for process termination
     */
    public void waitFor() {
        checkStarted();
        try {
            if (myInputProcessor != null && myProcess != null) {
                myInputProcessor.test(myProcess.getOutputStream());
            }
        }
        finally {
            waitForProcess();
        }
    }

    /**
     * Wait for process
     */
    protected abstract void waitForProcess();

    /**
     * Set silent mode. When handler is silent, it does not logs command in version control console.
     * Note that this option also suppresses stderr and stdout copying.
     *
     * @param silent a new value of the flag
     * @see #setStderrSuppressed(boolean)
     * @see #setStdoutSuppressed(boolean)
     */
    @SuppressWarnings({"SameParameterValue"})
    public void setSilent(boolean silent) {
        checkNotStarted();
        mySilent = silent;
        if (silent) {
            setStderrSuppressed(true);
            setStdoutSuppressed(true);
        }
    }

    /**
     * @return a character set to use for IO
     */
    @Nonnull
    public Charset getCharset() {
        return myCharset;
    }

    /**
     * Set character set for IO
     *
     * @param charset a character set
     */
    @SuppressWarnings({"SameParameterValue"})
    public void setCharset(@Nonnull Charset charset) {
        myCharset = charset;
    }

    /**
     * @return true if standard output is not copied to the console
     */
    public boolean isStdoutSuppressed() {
        return myStdoutSuppressed;
    }

    /**
     * Set flag specifying if stdout should be copied to the console
     *
     * @param stdoutSuppressed true if output is not copied to the console
     */
    public void setStdoutSuppressed(boolean stdoutSuppressed) {
        checkNotStarted();
        myStdoutSuppressed = stdoutSuppressed;
    }

    /**
     * @return true if standard output is not copied to the console
     */
    public boolean isStderrSuppressed() {
        return myStderrSuppressed;
    }

    /**
     * Set flag specifying if stderr should be copied to the console
     *
     * @param stderrSuppressed true if error output is not copied to the console
     */
    public void setStderrSuppressed(boolean stderrSuppressed) {
        checkNotStarted();
        myStderrSuppressed = stderrSuppressed;
    }

    /**
     * Set environment variable
     *
     * @param name  the variable name
     * @param value the variable value
     */
    public void setEnvironment(String name, String value) {
        myEnv.put(name, value);
    }

    /**
     * @return true if the command line is too big
     */
    public boolean isLargeCommandLine() {
        return myCommandLine.getCommandLineString().length() > VcsFileUtil.FILE_PATH_LIMIT;
    }

    public void runInCurrentThread(@Nullable Runnable postStartAction) {
        //LOG.assertTrue(!ApplicationManager.getApplication().isDispatchThread(), "Git process should never start in the dispatch thread.");

        GitVcs vcs = GitVcs.getInstance(myProject);
        if (vcs == null) {
            return;
        }

        if (WRITE == myCommand.lockingPolicy()) {
            // need to lock only write operations: reads can be performed even when a write operation is going on
            vcs.getCommandLock().writeLock().lock();
        }
        try {
            start();
            if (isStarted()) {
                if (postStartAction != null) {
                    postStartAction.run();
                }
                waitFor();
            }
        }
        finally {
            if (WRITE == myCommand.lockingPolicy()) {
                vcs.getCommandLock().writeLock().unlock();
            }

            logTime();
        }
    }

    @Nonnull
    private String stringifyWorkingDir() {
        String basePath = myProject.getBasePath();
        if (basePath != null) {
            String relPath = FileUtil.getRelativePath(basePath, FileUtil.toSystemIndependentName(myWorkingDirectory.getPath()), '/');
            if (".".equals(relPath)) {
                return myWorkingDirectory.getName();
            }
            else if (relPath != null) {
                return FileUtil.toSystemDependentName(relPath);
            }
        }
        return myWorkingDirectory.getPath();
    }

    private void logTime() {
        if (myStartTime > 0) {
            long time = System.currentTimeMillis() - myStartTime;
            if (!TIME_LOG.isDebugEnabled() && time > LONG_TIME) {
                LOG.info(String.format(
                    "git %s took %s ms. Command parameters: %n%s",
                    myCommand,
                    time,
                    myCommandLine.getCommandLineString()
                ));
            }
            else {
                TIME_LOG.debug(String.format("git %s took %s ms", myCommand, time));
            }
        }
        else {
            LOG.debug(String.format("git %s finished.", myCommand));
        }
    }

    @Override
    public String toString() {
        return myCommandLine.toString();
    }
}
