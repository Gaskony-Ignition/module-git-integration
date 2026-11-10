package com.operametrix.ignition.git.managers;

import com.operametrix.ignition.git.records.GitPolicy;
import com.operametrix.ignition.git.records.GitPolicyRecord;
import com.operametrix.ignition.git.records.ModeStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place every path asks whether the gateway's Git policy allows it: Designer RPCs, the
 * Versioning page's routes, the sync timer and the runner. The decisions are pure (policy + mode
 * in, refusal text out) so they are unit tested; the {@code check*} methods read the live policy.
 */
public final class PolicyGate {

    private PolicyGate() {
    }

    /** Thrown when the policy refuses; routes answer 403, RPCs show the message in the Designer. */
    public static final class Refused extends RuntimeException {
        private final String shortReason;

        public Refused(String message, String shortReason) {
            super(message);
            this.shortReason = shortReason;
        }

        /** For a menu item: "off in deployment mode Prod". */
        public String shortReason() {
            return shortReason;
        }
    }

    public enum Action {
        PUSH("pushing"),
        FORCE_PUSH("force pushing"),
        DESIGNER_COMMIT("committing from the Designer"),
        BRANCH_SWITCH("creating, switching or deleting branches"),
        LAYOUT_SWITCH("switching the repository layout");

        private final String what;

        Action(String what) {
            this.what = what;
        }
    }

    private static String where(String mode) {
        return mode == null ? "on this gateway" : "in deployment mode " + mode;
    }

    private static Refused refused(String mode, String what) {
        return new Refused("Not allowed " + where(mode) + ": " + what + " is turned off by the gateway's Git policy.",
                "off " + where(mode));
    }

    /** Why the policy refuses {@code action}, or null when it allows it. */
    public static Refused decide(GitPolicy p, String mode, Action action) {
        boolean allowed = switch (action) {
            case PUSH -> p.push();
            case FORCE_PUSH -> p.push() && p.forcePush();
            case DESIGNER_COMMIT -> p.commit();
            case BRANCH_SWITCH -> p.branches();
            case LAYOUT_SWITCH -> p.layout();
        };
        return allowed ? null : refused(mode, action.what);
    }

    /**
     * A delivery mode ({@code pull}, {@code replace}, {@code runner}; a project's {@code sync-pull},
     * {@code runner-release} and the rest map onto those). Off is always allowed: turning a
     * delivery off can never be what a policy guards against.
     */
    public static Refused decideDelivery(GitPolicy p, String mode, String delivery) {
        String kind = deliveryKind(delivery);
        if (kind.equals("off") || p.deliveries().contains(kind)) {
            return null;
        }
        return refused(mode, "delivery '" + kind + "'");
    }

    static String deliveryKind(String delivery) {
        String d = delivery == null ? "off" : delivery.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("runner")) {
            return "runner";
        }
        if (d.endsWith("replace")) {
            return "replace";
        }
        if (d.endsWith("pull")) {
            return "pull";
        }
        return "off";
    }

    /** Whether the policy lets the gateway contact {@code url}; an empty host list allows any. */
    public static Refused decideHost(GitPolicy p, String mode, String url) {
        List<String> hosts = p.hosts();
        if (hosts.isEmpty()) {
            return null;
        }
        String host = hostOf(url);
        for (String h : hosts) {
            if (hostMatches(h, host)) {
                return null;
            }
        }
        return new Refused("Not allowed " + where(mode) + ": the gateway's Git policy does not list "
                + (host.isEmpty() ? "a remote with no host (" + url + ")" : "the host " + host)
                + " in its allowed remote hosts.", "host not allowed " + where(mode));
    }

    // scheme://[user@]host[:port]/... or scp-like user@host:path
    private static final Pattern URL_HOST = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/]*@)?([^/:?#]*)");
    private static final Pattern SCP_HOST = Pattern.compile("^(?:[^@/]+@)?([^/:]+):(?!//)");

    /** The host a git URL contacts, lower case; "" for a local path or {@code file://}. */
    public static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        Matcher m = URL_HOST.matcher(u);
        if (m.find()) {
            return u.toLowerCase(Locale.ROOT).startsWith("file:") ? "" : m.group(1).toLowerCase(Locale.ROOT);
        }
        m = SCP_HOST.matcher(u);
        // A Windows drive letter ("C:\repo") is a path, not a host.
        if (m.find() && m.group(1).length() > 1) {
            return m.group(1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /** {@code github.com} matches exactly; {@code *.example.com} matches any subdomain. */
    static boolean hostMatches(String pattern, String host) {
        String p = pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty() || host.isEmpty()) {
            return false;
        }
        if (p.startsWith("*.")) {
            return host.endsWith(p.substring(1));
        }
        return p.equals(host);
    }

    /**
     * Whether a credential may be sent to {@code url}. {@code boundHost} is the host the credential
     * belongs to: the config remote's URL host, or an HTTPS credential's host field when that names
     * a host. A free-text label (no dot) binds nothing, as the field was only ever a label.
     *
     * @return the refusal text, or null when it may be sent
     */
    public static String credentialMismatch(String boundHost, String url) {
        String bound = boundHost == null ? "" : boundHost.trim().toLowerCase(Locale.ROOT);
        if (bound.isEmpty() || !(bound.contains(".") || bound.equals("localhost"))) {
            return null;
        }
        String host = hostOf(url);
        if (host.isEmpty() || hostMatches(bound, host)) {
            return null;
        }
        return "Refused to send the credential for " + bound + " to " + host + ". The remote's URL and"
                + " its credential belong to different hosts; save the remote again with the right"
                + " credential.";
    }

    // ── Live checks ───────────────────────────────────────────────────────────────────────────

    private static String mode() {
        try {
            return ModeStore.activeMode().orElse(null);
        } catch (LinkageError e) {
            // Unit tests run without the SDK on the classpath; there is no mode there.
            return null;
        }
    }

    public static void check(Action action) {
        Refused r = decide(GitPolicyRecord.get(), mode(), action);
        if (r != null) {
            throw r;
        }
    }

    public static void checkDelivery(String delivery) {
        Refused r = decideDelivery(GitPolicyRecord.get(), mode(), delivery);
        if (r != null) {
            throw r;
        }
    }

    public static void checkHost(String url) {
        Refused r = decideHost(GitPolicyRecord.get(), mode(), url);
        if (r != null) {
            throw r;
        }
    }

    /** "action\tshort reason" for each action the policy refuses; what the Designer greys out. */
    public static List<String> refusals() {
        GitPolicy p = GitPolicyRecord.get();
        String mode = mode();
        List<String> out = new ArrayList<>();
        for (Action a : Action.values()) {
            Refused r = decide(p, mode, a);
            if (r != null) {
                out.add(a.name() + "\t" + r.shortReason());
            }
        }
        return out;
    }

    // ── Commit trailer ───────────────────────────────────────────────────────────────────────

    public static final String TRAILER = "Gateway-Mode";

    /** Appends {@code Gateway-Mode: <mode>} once; a message that has one already is left alone. */
    public static String withTrailer(String message, String mode) {
        if (mode == null || mode.isBlank()) {
            return message;
        }
        String m = message == null ? "" : message;
        for (String line : m.split("\n")) {
            if (line.startsWith(TRAILER + ":")) {
                return m;
            }
        }
        String body = m.endsWith("\n") ? m.substring(0, m.length() - 1) : m;
        return body + "\n\n" + TRAILER + ": " + mode;
    }

    /** {@link #withTrailer} with the gateway's active mode; unchanged outside a gateway. */
    public static String trailer(String message) {
        return withTrailer(message, mode());
    }
}
