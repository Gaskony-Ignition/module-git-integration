package com.operametrix.ignition.git.automation;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.operametrix.ignition.git.records.GitRunnerRecord;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The bearer-token gate shared by the two routes a runner calls.
 *
 * <p>Both routes are open (no session, no CSRF token), so this is the whole of their access
 * control. It runs before anything is read from the body: a release upload is up to 256 MB, and
 * an unauthenticated caller must not be able to make the gateway stream that to disk.
 */
public final class RunnerAuth {

    private static final Logger logger = LoggerFactory.getLogger(RunnerAuth.class);

    /**
     * When a runner last authenticated successfully, and on which route. The Runner tab uses it to
     * stop asking someone to install a runner that demonstrably already exists — a call that got
     * this far proves a runner exists, reaches this gateway and holds the right token, which is
     * more than GitHub's own runners API could tell us without an admin-scoped credential.
     *
     * <p>Deliberately in memory and not on the config record: a runner calls on every deploy, and
     * persisting each one would put a config commit in the versioning history per deploy. The
     * cost is that a gateway restart forgets it, so the page words the empty case as "not since
     * this gateway started" rather than as proof that no runner exists.
     */
    private static volatile long lastCallAt;
    private static volatile String lastCallKind = "";

    private RunnerAuth() {
    }

    /** Millis of the last authenticated runner call, or 0 if there has been none. */
    public static long lastCallAt() {
        return lastCallAt;
    }

    /** {@code "release"} or {@code "sync"} — which route that last call was. */
    public static String lastCallKind() {
        return lastCallKind;
    }

    /**
     * Null when the caller may proceed; otherwise the response body, with the status already set.
     * A gateway with the runner off answers 404, indistinguishable from one without this module.
     *
     * @param kind the route being called, recorded for the Runner tab when the call is allowed
     */
    static String reject(RequestContext req, HttpServletResponse resp, String kind) {
        GitRunnerRecord cfg = GitRunnerRecord.get();
        if (!cfg.isEnabled() || !cfg.hasToken()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "{}";
        }
        byte[] expected = cfg.tokenBytes();
        try {
            if (!tokenValid(req, expected)) {
                // Deliberately not logged with the offered token.
                logger.warn("Rejected a runner request with a bad or missing token.");
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return "{\"error\":\"bad token\"}";
            }
        } finally {
            if (expected != null) {
                Arrays.fill(expected, (byte) 0);
            }
        }
        lastCallAt = System.currentTimeMillis();
        lastCallKind = kind;
        return null;
    }

    /** Constant-time comparison of the bearer token, so a wrong guess leaks no timing. */
    private static boolean tokenValid(RequestContext req, byte[] expected) {
        if (expected == null) {
            return false;
        }
        String offered = header(req, "Authorization");
        if (offered == null) {
            return false;
        }
        offered = offered.trim();
        if (offered.regionMatches(true, 0, "Bearer ", 0, 7)) {
            offered = offered.substring(7).trim();
        }
        return MessageDigest.isEqual(offered.getBytes(StandardCharsets.UTF_8), expected);
    }

    /** Reads a small body; throws {@link IllegalStateException} past {@code max} bytes. */
    static byte[] readBody(RequestContext req, int max) throws Exception {
        try (InputStream in = req.getRequest().getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                if (out.size() > max) {
                    throw new IllegalStateException("body too large");
                }
            }
            return out.toByteArray();
        }
    }

    private static String header(RequestContext req, String name) {
        try {
            return req.getRequest().getHeader(name);
        } catch (Exception e) {
            return null;
        }
    }
}
