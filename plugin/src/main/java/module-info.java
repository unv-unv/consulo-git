/**
 * @author VISTALL
 * @since 28/01/2023
 */
module com.intellij.git {
  requires consulo.ide.api;
  requires ini4j;
  requires com.intellij.git.rt;
  requires consulo.util.nodep;

  requires com.google.common;
  requires trilead.ssh2;
  requires xmlrpc.client;
  requires xmlrpc.common;
  requires ws.commons.util;
  requires org.apache.commons.codec;

  // TODO remove
  requires java.desktop;
  requires consulo.ide.impl;
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
}