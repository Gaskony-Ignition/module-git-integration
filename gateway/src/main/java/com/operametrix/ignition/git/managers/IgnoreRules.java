package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The data-directory {@code .gitignore}: its template and how the Git Ignore tab decides a path.
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
            "# Only config/ is versioned by default; tick a data-root path on the Git Ignore tab to add it.";

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

    /** Heads the block {@link #withDenyBlock} keeps at the end of {@code .gitignore}. */
    public static final String DENY_MARKER =
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
            "*.jks",
            "*.p12",
            "*.pfx",
            "*.pem",
            "*.key",
            "*.keystore");

    /**
     * {@code text} with the {@link #DENY} block moved to (or added at) its end, unchanged. Any line
     * of the block found under its marker is dropped first, so the result is the same however
     * often it is applied.
     */
    public static String withDenyBlock(String text) {
        List<String> lines = new java.util.ArrayList<>(List.of((text == null ? "" : text).split("\n", -1)));
        int marker = lines.indexOf(DENY_MARKER);
        while (marker >= 0) {
            lines.remove(marker);
            while (marker < lines.size() && DENY.contains(lines.get(marker).trim())) {
                lines.remove(marker);
            }
            marker = lines.indexOf(DENY_MARKER);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add(DENY_MARKER);
        lines.addAll(DENY);
        return String.join("\n", lines) + "\n";
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
