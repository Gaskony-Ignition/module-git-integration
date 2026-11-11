package com.operametrix.ignition.git;

import com.operametrix.ignition.git.managers.GitManager;
import com.operametrix.ignition.git.managers.SafePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * What the Designer is told when an RPC fails: the message with the gateway's install folders
 * taken out (it names {@code projects/x/...}, not {@code /usr/local/...}). The full message stays in
 * the gateway log.
 *
 * <p>A proxy rather than a catch in every method. It declares {@link GitScriptInterface} itself,
 * which is all the 8.3 {@code RpcDelegate} looks for on the object it is given.
 */
final class RpcErrors {

    private static final Logger logger = LoggerFactory.getLogger(RpcErrors.class);

    private RpcErrors() {
    }

    static GitScriptInterface wrap(GitScriptInterface target) {
        return (GitScriptInterface) Proxy.newProxyInstance(GitScriptInterface.class.getClassLoader(),
                new Class<?>[]{GitScriptInterface.class}, (proxy, method, args) -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        String message = cause.getMessage();
                        String shown = SafePaths.redact(message, GitManager.getDataFolderPath());
                        if (message == null || message.equals(shown)) {
                            throw cause;
                        }
                        logger.info("Designer call {} failed: {}", method.getName(), message);
                        // No cause attached: it would carry the unredacted message to the Designer.
                        throw new RuntimeException(shown);
                    }
                });
    }
}
