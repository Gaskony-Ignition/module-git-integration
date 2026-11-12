package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The data-directory {@code .gitignore}: its template and how the Git ignore tab decides a path.
 * No gateway context, so the tree's tick logic is unit tested (the 3.10.1 anchored-rule trap).
 */
public final class IgnoreRules {

    private IgnoreRules() {
    }

    /**
     * Only {@code config/} by default. Other modules keep whole runtimes under the data root (the
     * Python3 module's CPython distributions, Playwright's browsers), so a data root versioned by
     * default swept tens of thousands of files into the repository.
     */
    public static final String ROOT_RULES_COMMENT =
            "# Only config/ is versioned by default; tick a data-root path on the Git ignore tab to add it.";

    /** The 3.10.0 rule that kept every record of this module out of the repository. */
    public static final String OLD_RECORDS_RULE = "**/com.operametrix.ignition.git/";

    /** Since 4.1.0: the same, apart from the Git policy. */
    public static final List<String> RECORDS_RULES = List.of(
            "**/com.operametrix.ignition.git/*",
            "!**/com.operametrix.ignition.git/git-policy/");

    /**
     * Rewrites the 3.10.0 records line into {@link #RECORDS_RULES} in place. Only that exact managed
     * line is touched; every other line, and a file that no longer has it, is left as it is.
     *
     * @return the new lines, or null when there is nothing to change
     */
    public static List<String> versionPolicy(List<String> lines) {
        if (lines.stream().anyMatch(l -> l.trim().equals(RECORDS_RULES.get(1)))) {
            return null;
        }
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(OLD_RECORDS_RULE)) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return null;
        }
        List<String> out = new java.util.ArrayList<>(lines);
        out.remove(at);
        out.addAll(at, RECORDS_RULES);
        return out;
    }

    /** Based on Inductive Automation's version-control-guide template, plus the root rules. */
    public static final List<String> TEMPLATE = List.of(
            "# Ignition data-directory config-as-code — managed by the Git module",
            "**/db/*",
            "**/metricsdb/*",
            "**/autobackup/*",
            "**/db_backup_sqlite.idb",
            "**/valueStore.idb",
            // SQLite write-ahead-log sidecars. Without these the baseline `git add .` races the
            // tag value store: the directory scan lists valueStore.idb-wal, SQLite checkpoints and
            // deletes it, and the add dies with FileNotFoundException — so init could never
            // complete on a gateway that was actually running. Ignoring the .idb itself is not
            // enough; the sidecars are separate paths.
            "**/*-wal",
            "**/*-shm",
            "**/jar-cache/*",
            "**/request*",
            "**/response*",
            "*.tmp",
            "*.bak",
            "**/var",
            "*.log",
            "**/logs",
            "**/certificates/*",
            "**/keystore/",
            "**/config/local",
            "**/config/resources/local",
            "**/.container-init.conf",
            "**/conversion-report.txt",
            "**.digest.json",
            "**/migration-log-*.md",
            "**/.resources/",
            "**/.alarms_*",
            // This module's own records: git credentials, remotes and deliveries belong to the
            // gateway they were set on. Versioned, a gateway following another's branch would take
            // that gateway's remote and deliveries, and Sync - replace would delete its own. The
            // Git policy is the exception (4.1.0): it is what a Prod override is written to travel in.
            RECORDS_RULES.get(0),
            RECORDS_RULES.get(1),
            "",
            ROOT_RULES_COMMENT,
            "/*",
            "!/config/",
            "!/.gitignore"
    );

    /**
     * Template lines written for config that would otherwise also hit {@code projects/} in the
     * Combined layout (a script package called {@code db} or {@code logs}, a view called
     * {@code request}), with the {@code /config/}-scoped line each becomes there. The key and
     * certificate globs and the SQLite sidecars stay global on purpose.
     */
    public static final java.util.Map<String, String> CONFIG_SCOPED = scoped(
            "**/db/*", "**/metricsdb/*", "**/autobackup/*", "**/db_backup_sqlite.idb", "**/valueStore.idb",
            "**/jar-cache/*", "**/request*", "**/response*", "*.tmp", "*.bak", "**/var", "*.log", "**/logs",
            "**/.container-init.conf", "**/conversion-report.txt", "**.digest.json", "**/migration-log-*.md",
            "**/.resources/", "**/.alarms_*");

    private static java.util.Map<String, String> scoped(String... globs) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String g : globs) {
            String rest = g.startsWith("**/") ? g.substring(3) : g.startsWith("**") ? "*" + g.substring(2) : g;
            m.put(g, "/config/**/" + rest);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** {@code lines} with each managed template glob replaced by its {@code /config/} form; others as they are. */
    public static List<String> scopeToConfig(List<String> lines) {
        return lines.stream().map(l -> CONFIG_SCOPED.getOrDefault(l.trim(), l)).toList();
    }

    /** The reverse of {@link #scopeToConfig}, for the switch back to Separate. */
    public static List<String> unscope(List<String> lines) {
        java.util.Map<String, String> back = new java.util.HashMap<>();
        CONFIG_SCOPED.forEach((k, v) -> back.put(v, k));
        return lines.stream().map(l -> back.getOrDefault(l.trim(), l)).toList();
    }

    /**
     * Opens the deny block {@link #withDenyBlock} keeps at the end of {@code .gitignore}. Git applies
     * the last matching pattern, so a person's negation below it could re-include a key; every
     * module write moves it back to the end.
     */
    public static final String DENY_MARKER =
            "# >>> Git Integration: never versioned — keys, certificates, per-gateway secrets"
                    + " (moved back to the end on every write, so nothing above can re-include them)";

    public static final String DENY_END = "# <<< Git Integration: never versioned";

    /** Headed the deny block before 4.5.0, with no end marker. */
    static final String OLD_DENY_MARKER =
            "# Never versioned: keys, certificates and per-gateway secrets (kept last by the Git module)";

    /**
     * Paths no edit can bring into the repository: the gateway's keystores and certificates, its
     * local (never shared) config, {@code ignition.conf} (JVM arguments can hold passwords) and key
     * files anywhere. Last in the file, so git's last-match-wins puts them above any negation.
     */
    public static final List<String> DENY = List.of(
            "**/config/local/",
            "**/config/resources/local/",
            "**/keystore/",
            "**/certificates/",
            "/ignition.conf",
            // Internal user sources keep each user's password hash (and hash history) here, in every
            // collection; config.json and resource.json beside it hold settings only (8.3.10).
            "**/ignition/user-source/*/users.json",
            "*.jks",
            "*.p12",
            "*.pfx",
            "*.pem",
            "*.key",
            "*.keystore");

    /**
     * {@code text} with the {@link #DENY} block moved to (or added at) its end, unchanged. Any line
     * of the block found under its marker (old or new) is dropped first, so the result is the same
     * however often it is applied. A line someone typed inside the block stays, just above it.
     */
    public static String withDenyBlock(String text) {
        List<String> lines = withoutDeny(lines(text));
        trimEnd(lines);
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add(DENY_MARKER);
        lines.addAll(DENY);
        lines.add(DENY_END);
        return String.join("\n", lines) + "\n";
    }

    private static List<String> lines(String text) {
        return new java.util.ArrayList<>(List.of((text == null ? "" : text).split("\r?\n", -1)));
    }

    private static List<String> withoutDeny(List<String> in) {
        List<String> lines = new java.util.ArrayList<>(in);
        for (int at = indexOfDeny(lines); at >= 0; at = indexOfDeny(lines)) {
            lines.remove(at);
            while (at < lines.size() && DENY.contains(lines.get(at).trim())) {
                lines.remove(at);
            }
            if (at < lines.size() && lines.get(at).trim().equals(DENY_END)) {
                lines.remove(at);
            }
        }
        return lines;
    }

    private static int indexOfDeny(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.equals(DENY_MARKER) || t.equals(OLD_DENY_MARKER)) {
                return i;
            }
        }
        return -1;
    }

    private static void trimEnd(List<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
    }

    /**
     * Opens the block holding every line the module writes (template, root and records rules, the
     * Combined rules, the Git ignore tab's ticks). Module writes change only what is inside it, so a
     * line a person or another tool added outside it is never moved, reordered or removed.
     */
    public static final String MANAGED_START =
            "# >>> Git Integration (managed — edit with the Git ignore tab, not by hand)";

    public static final String MANAGED_END = "# <<< Git Integration";

    /** Headed the Git ignore tab's lines before 4.5.0. */
    public static final String OLD_TAB_MARKER = "# --- managed by the Versioning page below this line ---";

    /** {@code .gitignore} split around the managed block; the deny block is left out. */
    public record Parts(List<String> before, List<String> managed, List<String> after) {}

    /**
     * Splits {@code text} around the managed block, first putting the module's own lines into one
     * ({@link #wrap}) when the file has none yet.
     */
    public static Parts split(String text) {
        List<String> lines = withoutDeny(lines(text));
        int start = indexOfTrimmed(lines, MANAGED_START, 0);
        int end = start < 0 ? -1 : indexOfTrimmed(lines, MANAGED_END, start + 1);
        if (start < 0 || end < 0) {
            return wrap(lines);
        }
        return new Parts(new java.util.ArrayList<>(lines.subList(0, start)),
                new java.util.ArrayList<>(lines.subList(start + 1, end)),
                new java.util.ArrayList<>(lines.subList(end + 1, lines.size())));
    }

    /** The file for {@code parts}: before, the managed block, after, then the deny block. */
    public static String join(Parts parts) {
        List<String> out = new java.util.ArrayList<>(parts.before());
        trimEnd(out);
        if (!out.isEmpty()) {
            out.add("");
        }
        out.add(MANAGED_START);
        out.addAll(parts.managed());
        out.add(MANAGED_END);
        List<String> after = new java.util.ArrayList<>(parts.after());
        while (!after.isEmpty() && after.get(0).isBlank()) {
            after.remove(0);
        }
        if (!after.isEmpty()) {
            out.add("");
            out.addAll(after);
        }
        return withDenyBlock(String.join("\n", out));
    }

    /**
     * {@code text} with {@code edit} applied to the managed block only (wrapping the module's lines
     * first if need be), or null when {@code edit} returns null — nothing to change.
     */
    public static String editManaged(String text, java.util.function.UnaryOperator<List<String>> edit) {
        Parts parts = split(text);
        List<String> managed = edit.apply(new java.util.ArrayList<>(parts.managed()));
        return managed == null ? null : join(new Parts(parts.before(), managed, parts.after()));
    }

    private static int indexOfTrimmed(List<String> lines, String line, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(line)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 4.5.0 upgrade: a file written before the markers. Lines exactly as the module wrote them go into
     * the managed block, at the first one's place; every other line stays outside in its order —
     * those between module lines end up after the block, where git's last match lets them win as
     * their author meant. Below the old tab marker, the tab's anchored path lines count as module
     * lines too. A file with none gets an empty block at its end.
     */
    static Parts wrap(List<String> lines) {
        java.util.Set<String> module = new java.util.HashSet<>(TEMPLATE);
        module.addAll(CONFIG_SCOPED.values());
        module.addAll(RECORDS_RULES);
        module.add(OLD_RECORDS_RULE);
        module.add(OLD_TAB_MARKER);
        module.addAll(LayoutSwitch.BLOCK);
        module.add(LayoutSwitch.DATA_BIN);
        module.add(LayoutSwitch.OLD_REINCLUDE);
        module.remove("");
        boolean[] ours = new boolean[lines.size()];
        boolean underTab = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            underTab |= t.equals(OLD_TAB_MARKER);
            ours[i] = module.contains(t) || underTab && TAB_LINE.matcher(t).matches();
        }
        // A blank line between two module lines is the module's spacing.
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                int p = i - 1;
                while (p >= 0 && lines.get(p).isBlank()) {
                    p--;
                }
                int n = i + 1;
                while (n < lines.size() && lines.get(n).isBlank()) {
                    n++;
                }
                ours[i] = p >= 0 && n < lines.size() && ours[p] && ours[n];
            }
        }
        int first = -1;
        for (int i = 0; i < lines.size() && first < 0; i++) {
            first = ours[i] && !lines.get(i).isBlank() ? i : -1;
        }
        if (first < 0) {
            return new Parts(new java.util.ArrayList<>(lines), new java.util.ArrayList<>(), new java.util.ArrayList<>());
        }
        List<String> managed = new java.util.ArrayList<>();
        List<String> after = new java.util.ArrayList<>();
        for (int i = first; i < lines.size(); i++) {
            if (ours[i] && !(lines.get(i).isBlank() && !managed.isEmpty()
                    && managed.get(managed.size() - 1).isBlank())) {
                managed.add(lines.get(i));
            } else if (!ours[i]) {
                after.add(lines.get(i));
            }
        }
        trimEnd(managed);
        return new Parts(new java.util.ArrayList<>(lines.subList(0, first)), managed, after);
    }

    /** What the Git ignore tab writes: an anchored path or its negation, no glob but a trailing /**. */
    private static final java.util.regex.Pattern TAB_LINE =
            java.util.regex.Pattern.compile("!?/[^*?\\[\\]!#]+|!/[^*?\\[\\]!#]+/\\*\\*");

    /** Whether the {@link #DENY} block covers {@code path} or one of its folders. */
    public static boolean denied(String path) {
        IgnoreNode node = new IgnoreNode();
        try {
            node.parse(new java.io.ByteArrayInputStream(
                    String.join("\n", DENY).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String[] parts = path.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            prefix.append(i > 0 ? "/" : "").append(parts[i]);
            for (FastIgnoreRule r : node.getRules()) {
                if (r.isMatch(prefix.toString(), i < parts.length - 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Parsed {@code .gitignore} rules, newest last (git's last-match-wins order). */
    public static List<FastIgnoreRule> parse(Path gitignore) throws IOException {
        if (!Files.exists(gitignore)) {
            return List.of();
        }
        IgnoreNode node = new IgnoreNode();
        try (InputStream in = Files.newInputStream(gitignore)) {
            node.parse(in);
        }
        return node.getRules();
    }

    /**
     * Decide one path against the rules, git-style: the LAST matching rule wins, and a negation
     * (`!foo`) re-includes. Returns {@code null} when nothing matched.
     *
     * <p>Matching is done on the full repo-relative path AND on each trailing path segment, because
     * a rule without a slash (e.g. {@code *.log}) matches at any depth — {@code FastIgnoreRule}
     * itself only compares what it is handed.
     */
    public static FastIgnoreRule matchRule(List<FastIgnoreRule> rules, String path, boolean isDir) {
        FastIgnoreRule match = null;
        for (FastIgnoreRule r : rules) {
            if (r.isEmpty()) {
                continue;
            }
            boolean hit = r.isMatch("/" + path, isDir, true);
            // JGit flags an anchored "/*" as name-only too; testing its basename would let it
            // exclude every path at every depth, so only a rule with no slash at all gets this.
            if (!hit && r.getNameOnly() && !isAnchored(r)) {
                // A name-only rule applies to every segment, so test the basename too.
                int slash = path.lastIndexOf('/');
                hit = r.isMatch("/" + (slash >= 0 ? path.substring(slash + 1) : path), isDir, true);
            }
            if (hit) {
                match = r;
            }
        }
        return match;
    }

    /** A rule that starts with a slash ("/*", "!/config/") applies at the repo root only. */
    private static boolean isAnchored(FastIgnoreRule r) {
        String pattern = r.toString();
        return (pattern.startsWith("!") ? pattern.substring(1) : pattern).startsWith("/");
    }

    /**
     * Whether a path is excluded, taking ancestors into account. Git cannot re-include a file whose
     * PARENT DIRECTORY is excluded, so an ancestor's exclusion is final for everything beneath it —
     * a negation deeper down is silently ineffective, and the UI must not offer it.
     *
     * @return the deciding rule and whether an ancestor (not the path itself) settled it
     */
    public static Decision decide(List<FastIgnoreRule> rules, String path, boolean isDir) {
        // Walk ancestors root-downwards; the first excluded ancestor settles it.
        StringBuilder walked = new StringBuilder();
        for (String segment : path.split("/")) {
            if (walked.length() > 0) {
                walked.append('/');
            }
            walked.append(segment);
            String sofar = walked.toString();
            boolean last = sofar.equals(path);
            FastIgnoreRule m = matchRule(rules, sofar, last ? isDir : true);
            if (m != null && m.getResult() && !last) {
                // An ancestor is excluded — nothing below it can be re-included.
                return new Decision(m, true);
            }
            if (last) {
                return new Decision(m, false);
            }
        }
        return new Decision(null, false);
    }

    /** The rule that settled a path, and whether it was inherited from an excluded ancestor. */
    public record Decision(FastIgnoreRule rule, boolean fromAncestor) {
        public boolean excluded() {
            return rule != null && rule.getResult();
        }
    }

    /**
     * Whether the deciding rule is this path's own explicit line rather than an inherited glob.
     * Only an own line can be removed by unticking; a glob has to be negated instead.
     */
    public static boolean isOwnLine(FastIgnoreRule rule, String path, boolean isDir) {
        String text = rule.toString().trim();
        if (text.startsWith("!")) {
            text = text.substring(1);
        }
        String bare = text.startsWith("/") ? text.substring(1) : text;
        if (bare.endsWith("/")) {
            bare = bare.substring(0, bare.length() - 1);
        }
        return bare.equals(path) || (isDir && bare.equals(path + "/"));
    }

}
