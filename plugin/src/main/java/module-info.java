/**
 * @author VISTALL
 * @since 2023-01-28
 */
module com.intellij.git {
    requires consulo.ide.api;
    requires ini4j;
    requires com.intellij.git.rt;
    requires consulo.util.nodep;

    requires org.apache.commons.codec;
    requires org.slf4j;
    requires trilead.ssh2;
    requires xmlrpc.client;
    requires xmlrpc.common;
    requires ws.commons.util;

    // TODO remove
    requires java.desktop;
    requires forms.rt;

    // serializing state
    opens git4idea.config to consulo.util.xml.serializer;
    opens git4idea.push to consulo.util.xml.serializer;
    // reflect action creating
    opens git4idea.actions to consulo.component.impl;
    opens git4idea.reset to consulo.component.impl;
    opens git4idea.log to consulo.component.impl;
    opens git4idea.branch to consulo.component.impl;
    opens git4idea.ui.branch to consulo.component.impl;

    exports consulo.git;
    exports consulo.git.config;
    exports consulo.git.icon;
    exports consulo.git.localize;
    exports git4idea;
    exports git4idea.actions;
    exports git4idea.annotate;
    exports git4idea.attributes;
    exports git4idea.branch;
    exports git4idea.changes;
    exports git4idea.checkin;
    exports git4idea.checkout;
    exports git4idea.cherrypick;
    exports git4idea.commands;
    exports git4idea.config;
    exports git4idea.crlf;
    exports git4idea.diff;
    exports git4idea.history;
    exports git4idea.history.browser;
    exports git4idea.history.wholeTree;
    exports git4idea.i18n;
    exports git4idea.log;
    exports git4idea.merge;
    exports git4idea.push;
    exports git4idea.rebase;
    exports git4idea.remote;
    exports git4idea.repo;
    exports git4idea.reset;
    exports git4idea.rollback;
    exports git4idea.roots;
    exports git4idea.settings;
    exports git4idea.stash;
    exports git4idea.status;
    exports git4idea.ui;
    exports git4idea.ui.branch;
    exports git4idea.update;
    exports git4idea.util;
    exports git4idea.validators;
    exports git4idea.vfs;
    exports org.jetbrains.git4idea.ssh;
    exports org.jetbrains.git4idea.util;
}