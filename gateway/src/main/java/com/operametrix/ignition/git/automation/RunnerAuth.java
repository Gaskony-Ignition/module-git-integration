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
final class RunnerAuth {

    private static final Logger logger = LoggerFactory.getLogger(RunnerAuth.class);

    private RunnerAuth() {
    }

    /**
     * Null when the caller may proceed; otherwise the response body, with the status already set.
     * A gateway with the runner off answers 404, indistinguishable from one without this module.
     */
    static String reject(RequestContext req, HttpServletResponse resp) {
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
