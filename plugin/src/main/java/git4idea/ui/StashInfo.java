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
package git4idea.ui;

import consulo.git.localize.GitLocalize;
import consulo.localize.LocalizeValue;
import consulo.util.lang.xml.XmlStringUtil;

/**
 * Information about one stash.
 */
public class StashInfo {
    private final String myStash; // stash codename (stash@{1})
    private final String myBranch;
    private final String myMessage;
    private final LocalizeValue myText; // The formatted text representation

    public StashInfo(String stash, String branch, String message) {
        myStash = stash;
        myBranch = branch;
        myMessage = message;
        myText = GitLocalize.unstashStashesItem(
            XmlStringUtil.escapeText(stash),
            XmlStringUtil.escapeText(branch),
            XmlStringUtil.escapeText(message)
        );
    }

    @Override
    public String toString() {
        return myText.get();
    }

    public String getStash() {
        return myStash;
    }

    public String getBranch() {
        return myBranch;
    }

    public String getMessage() {
        return myMessage;
    }

    public String getText() {
        return myText.get();
    }
}
