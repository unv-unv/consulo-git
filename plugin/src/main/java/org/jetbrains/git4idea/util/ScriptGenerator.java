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
package org.jetbrains.git4idea.util;

import consulo.platform.Platform;
import consulo.util.collection.ContainerUtil;
import consulo.util.io.ClassPathUtil;
import consulo.util.io.FileUtil;
import consulo.util.io.NioFiles;
import consulo.util.io.URLUtil;
import consulo.util.lang.Pair;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Script generator utility class. It uses to generate a temporary scripts that
 * are removed after application ends.
 */
public class ScriptGenerator {
    /**
     * The extension of the ssh script name
     */
    public static final String SCRIPT_EXT = Platform.current().os().isWindows() ? ".bat" : ".sh";
    /**
     * The script prefix
     */
    private final String myPrefix;
    /**
     * The scripts may class
     */
    private final Class myMainClass;
    /**
     * The class paths for the script
     */
    private final List<String> myPaths = new ArrayList<>();
    /**
     * The internal parameters for the script
     */
    private final List<String> myInternalParameters = new ArrayList<>();

    /**
     * A constructor
     *
     * @param prefix    the script prefix
     * @param mainClass the script main class
     */
    public ScriptGenerator(String prefix, Class mainClass) {
        myPrefix = prefix;
        myMainClass = mainClass;
        addClasses(myMainClass);
    }

    /**
     * Add jar or directory that contains the class to the classpath
     *
     * @param classes classes which sources will be added
     * @return this script generator
     */
    public ScriptGenerator addClasses(Class... classes) {
        for (Class<?> c : classes) {
            addPath(getJarPathForClass(c));
        }
        return this;
    }

    @Nullable
    public static String getJarPathForClass(@Nonnull Class aClass) {
        try {
            CodeSource codeSource = aClass.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                URL location = codeSource.getLocation();
                if (location != null) {
                    URI uri = location.toURI();
                    Pair<String, String> pair = URLUtil.splitJarUrl(uri.toURL().toString());
                    if (pair == null) {
                        // FIXME [VISTALL] our classloader return wrong uri
                        return uri.getPath();
                    }
                    return pair.getFirst();
                }
            }

            throw new IllegalArgumentException(aClass.getName());
        }
        catch (URISyntaxException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Add path to class path. The methods checks if the path has been already added to the classpath.
     *
     * @param path the path to add
     */
    private void addPath(String path) {
        if (!myPaths.contains(path)) {
            // the size of path is expected to be quite small, so no optimization is done here
            myPaths.add(path);
        }
    }

    /**
     * Add source for the specified resource
     *
     * @param base     the resource base
     * @param resource the resource name
     * @return this script generator
     */
    public ScriptGenerator addResource(Class base, String resource) {
        addPath(getJarForResource(base, resource));
        return this;
    }

    /**
     * Add internal parameters for the script
     *
     * @param parameters internal parameters
     * @return this script generator
     */
    public ScriptGenerator addInternal(String... parameters) {
        ContainerUtil.addAll(myInternalParameters, parameters);
        return this;
    }

    /**
     * Generate script according to specified parameters
     *
     * @return the path to generated script
     * @throws IOException if there is a problem with creating script
     */
    @SuppressWarnings({"HardCodedStringLiteral"})
    public File generate() throws IOException {
        File scriptPath = FileUtil.createTempFile(myPrefix, SCRIPT_EXT);
        scriptPath.deleteOnExit();
        PrintWriter out = new PrintWriter(new FileWriter(scriptPath));
        try {
            boolean isWindows = Platform.current().os().isWindows();
            out.println(isWindows ? "@echo off" : "#!/bin/sh");
            out.println(commandLine() + (isWindows ? " %*" : " \"$@\""));
        }
        finally {
            out.close();
        }
        NioFiles.setExecutable(scriptPath.toPath());
        return scriptPath;
    }

    /**
     * @return a command line for the the executable program
     */
    public String commandLine() {
        StringBuilder cmd = new StringBuilder();
        cmd.append('\"').append(System.getProperty("java.home")).append(File.separatorChar).append("bin").append(File.separatorChar)
            .append("java\" -cp \"");
        boolean first = true;
        for (String p : myPaths) {
            if (!first) {
                cmd.append(File.pathSeparatorChar);
            }
            else {
                first = false;
            }
            cmd.append(p);
        }
        cmd.append("\" ");
        cmd.append(myMainClass.getName());
        for (String p : myInternalParameters) {
            cmd.append(' ');
            cmd.append(p);
        }
        String line = cmd.toString();
        if (Platform.current().os().isWindows()) {
            line = line.replace('\\', '/');
        }
        return line;
    }

    /**
     * Get path for resources.jar
     *
     * @param context a context class
     * @param res     a resource
     * @return a path to classpath entry
     */
    @SuppressWarnings({"SameParameterValue"})
    public static String getJarForResource(Class context, String res) {
        String resourceRoot = ClassPathUtil.getResourceRoot(context, res);
        return new File(resourceRoot).getAbsoluteFile().getAbsolutePath();
    }
}
