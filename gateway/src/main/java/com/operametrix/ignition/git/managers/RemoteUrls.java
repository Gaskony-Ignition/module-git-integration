package com.operametrix.ignition.git.managers;

import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which remote URLs the gateway may use. HTTPS and SSH always; a local folder, {@code file://},
 * {@code git://}, {@code http://} and a target on this machine or its link-local network only
 * when the Git policy's {@code allowInsecureRemotes} is on. A password written into a URL is never
 * accepted: it would sit in plain text in {@code .git/config}. Pure apart from
 * {@link #resolvesLocal}, so it is unit tested.
 */
public final class RemoteUrls {

    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    static final String INSECURE_HINT = " Only allowed when the gateway's Git policy allows insecure"
            + " remotes (Versioning > Policy).";

    private RemoteUrls() {
    }

    private static final Pattern HOSTNAME =
            Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._~+-]+");

    /**
     * A URL as JGit reads it, which is what decides where it connects: {@code file:/x} and
     * {@code /x} are local, {@code host:path} is SSH to host. Never a regex of our own beside it.
     *
     * @param scheme  lower case, or null (scp-like or local)
     * @param host    lower case without IPv6 brackets; "" when local
     * @param local   a folder on the gateway (a {@code file} scheme or host, or no host)
     * @param problem why the URL is unusable whatever the policy, or null
     */
    record Parsed(String scheme, String host, boolean local, boolean password, String problem) {}

    static Parsed parse(String url) {
        if (url == null || url.isBlank()) {
            return new Parsed(null, "", true, false, "A remote URL is required.");
        }
        org.eclipse.jgit.transport.URIish u;
        try {
            u = new org.eclipse.jgit.transport.URIish(url.trim());
        } catch (java.net.URISyntaxException e) {
            return new Parsed(null, "", false, false, "Not a valid remote URL.");
        }
        String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
        String rawHost = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if ("file".equals(scheme) || rawHost.isEmpty() || rawHost.equals("file")) {
            return new Parsed(scheme, "", true, u.getPass() != null, null);
        }
        String host = rawHost.startsWith("[") && rawHost.endsWith("]")
                ? rawHost.substring(1, rawHost.length() - 1) : rawHost;
        boolean validHost = host.contains(":") ? ipLiteral(host) : HOSTNAME.matcher(host).matches();
        // A backslash, or URL syntax in the user name, is read one way here and another by the
        // HTTP client (https://evil.example\.github.com, http://evil.example#@github.com).
        String problem = !validHost || url.indexOf('\\') >= 0
                || (u.getUser() != null && !USER.matcher(u.getUser()).matches())
                ? "Not a valid remote URL: the host is not a host name or IP address." : null;
        return new Parsed(scheme, host, false, u.getPass() != null, problem);
    }

    private static boolean ipLiteral(String v6) {
        try {
            return InetAddress.getByName("[" + v6 + "]") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Why {@code url} may not be a remote, or null when it may. */
    public static String problem(String url, boolean allowInsecure) {
        Parsed p = parse(url);
        if (p.problem() != null) {
            return p.problem();
        }
        if (p.password()) {
            return "The remote URL has a password in it. Take it out of the URL and save it as a"
                    + " credential instead.";
        }
        if (p.local()) {
            return allowInsecure ? null : "A local folder or file: URL is a folder on the gateway, not a"
                    + " remote server." + INSECURE_HINT;
        }
        String scheme = p.scheme() == null ? "ssh" : p.scheme();
        switch (scheme) {
            case "https", "ssh", "git+ssh", "ssh+git" -> {
            }
            case "http", "git" -> {
                if (!allowInsecure) {
                    return scheme + ":// sends everything unencrypted." + INSECURE_HINT;
                }
            }
            default -> {
                return "Remote URLs must be https:// or ssh:// (" + scheme + ":// is not supported).";
            }
        }
        if (!allowInsecure && isLocalTarget(p.host())) {
            return "The remote " + p.host() + " is this machine or its link-local network." + INSECURE_HINT;
        }
        return null;
    }

    /** What {@link #host} returns for a URL whose host cannot be trusted; matches no host pattern. */
    public static final String INVALID = "\u0000";

    /**
     * The host a URL contacts, as JGit reads it: lower case, IPv6 without brackets; "" for a local
     * folder, {@link #INVALID} for anything unusable.
     */
    public static String host(String url) {
        Parsed p = parse(url);
        return p.problem() != null && !p.local() ? INVALID : p.host();
    }

    /** {@code localhost}, or an IP literal that is loopback, link-local (169.254/16, fe80::/10) or any. */
    static boolean isLocalTarget(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost")) {
            return true;
        }
        if (!IPV4.matcher(h).matches() && !h.contains(":")) {
            return false;
        }
        try {
            // An IP literal is parsed, never looked up.
            return local(InetAddress.getByName(h));
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether a host name resolves to a local address; false when it does not resolve at all. */
    public static boolean resolvesLocal(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (local(a)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Unknown host: the transport fails on its own, with its own message.
        }
        return false;
    }

    private static boolean local(InetAddress a) {
        return a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress();
    }

    /** {@code url} with a password in its user info removed (the user name kept), or as it was. */
    public static String withoutPassword(String url) {
        if (url == null) {
            return null;
        }
        java.util.regex.Matcher m = SCHEME.matcher(url);
        if (!m.find()) {
            return url;
        }
        String rest = url.substring(m.end());
        String authority = rest.split("[/?#]", 2)[0];
        int at = authority.lastIndexOf('@');
        if (at < 0) {
            return url;
        }
        String userInfo = authority.substring(0, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0) {
            return url;
        }
        return url.substring(0, m.end()) + userInfo.substring(0, colon) + rest.substring(at);
    }
}
