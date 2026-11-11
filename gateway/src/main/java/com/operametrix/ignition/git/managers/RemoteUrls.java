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

    /** Why {@code url} may not be a remote, or null when it may. */
    public static String problem(String url, boolean allowInsecure) {
        if (url == null || url.isBlank()) {
            return "A remote URL is required.";
        }
        String u = url.trim();
        java.util.regex.Matcher m = SCHEME.matcher(u);
        String host;
        if (m.find()) {
            String scheme = m.group(1).toLowerCase(Locale.ROOT);
            String authority = u.substring(m.end()).split("[/?#]", 2)[0];
            int at = authority.lastIndexOf('@');
            if (at >= 0 && authority.substring(0, at).contains(":")) {
                return "The remote URL has a password in it. Take it out of the URL and save it as a"
                        + " credential instead.";
            }
            host = hostOf(at >= 0 ? authority.substring(at + 1) : authority);
            switch (scheme) {
                case "https", "ssh", "git+ssh", "ssh+git" -> {
                }
                case "http", "git" -> {
                    if (!allowInsecure) {
                        return scheme + ":// sends everything unencrypted." + INSECURE_HINT;
                    }
                }
                case "file" -> {
                    if (!allowInsecure) {
                        return "A file:// remote is a folder on the gateway, not a remote server."
                                + INSECURE_HINT;
                    }
                }
                default -> {
                    return "Remote URLs must be https:// or ssh:// (" + scheme + ":// is not supported).";
                }
            }
        } else {
            host = PolicyGate.hostOf(u);
            if (host.isEmpty() && !allowInsecure) {
                return "A local folder is not a remote server." + INSECURE_HINT;
            }
        }
        if (!allowInsecure && isLocalTarget(host)) {
            return "The remote " + host + " is this machine or its link-local network." + INSECURE_HINT;
        }
        return null;
    }

    /** The host of an authority ({@code host}, {@code host:port}, {@code [v6]:port}), lower case. */
    static String hostOf(String hostPort) {
        String h = hostPort;
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end < 0 ? h.substring(1) : h.substring(1, end);
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0) {
                h = h.substring(0, colon);
            }
        }
        return h.toLowerCase(Locale.ROOT);
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
