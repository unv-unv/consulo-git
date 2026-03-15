/**
 * @author VISTALL
 * @since 2026-03-15
 */
module com.intellij.git.rt {
    requires static jakarta.annotation;
    
    requires trilead.ssh2;
    requires xmlrpc.common;
    requires xmlrpc.client;

    exports org.jetbrains.git4idea.rt;
    exports org.jetbrains.git4idea.rt.http;
    exports org.jetbrains.git4idea.rt.ssh;
}