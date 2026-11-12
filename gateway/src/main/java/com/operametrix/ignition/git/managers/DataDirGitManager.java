package com.operametrix.ignition.git.managers;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.operametrix.ignition.git.automation.GitEvent;
import com.operametrix.ignition.git.automation.GitEvents;
import com.operametrix.ignition.git.records.GitConfigRemoteRecord;
import com.operametrix.ignition.git.records.ModeStore;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.operametrix.ignition.git.GatewayHook.getContext;

/**
 * Gateway data-directory ("config-as-code") versioning. A single git repo rooted at the Ignition
 * data directory tracks gateway config (primarily {@code <dataDir>/config/}); the per-project repos
 * under {@code <dataDir>/projects/} are versioned separately and are excluded here via {@code .gitignore}.
 *
 * <p>This is a thin orchestration layer over the static {@link GitManager} primitives — all of which
 * take a working-dir {@link Path} and are reused directly against {@link #dataDir()}. The only
 * config-specific logic lives here: init/{@code .gitignore}, a plain porcelain status (the
 * project-resource actor/metadata helpers in {@link GitManager} must NOT be applied to config files),
 * restore-to-version, and applying restored files to the running gateway via {@code requestScan()}.
 */
public class DataDirGitManager {
    private static final LoggerEx logger = LoggerEx.newBuilder().build(DataDirGitManager.class);

    /**
     * Serializes our own git mutations and status reads so a {@code /dirty} poll never observes a
     * half-staged index while the gateway concurrently rewrites {@code config/} files.
     */
    private static final Object DATA_DIR_LOCK = new Object();

    /** Config-as-code lives under {@code config/}; the {@code .gitignore} sits at the repo root. */

    private static final String ROOT_RULES_COMMENT = IgnoreRules.ROOT_RULES_COMMENT;
    private static final List<String> GITIGNORE_LINES = IgnoreRules.TEMPLATE;

    /** The data-root paths 3.8.0 to 3.9.10 listed one by one in {@code .gitignore}. */
    private static final List<String> PRE_3911_ROOT_PATHS = List.of(
            "gateway.xml", "gateway.xml_clean", "ignition.conf", "logback.xml", "log4j.properties",
            "commissioning.json", "redundancy.xml", "modules.json", "email-profiles");

    /** Written once {@link #denyRootOnce} has run, so a path the user later ticks stays ticked. */
    private static final String ROOT_DENIED_MARKER = ".git-module-root-denied";

    /** A single uncommitted config change. {@code type} ∈ ADDED | MODIFIED | DELETED | UNTRACKED. */
    public record ConfigChange(String path, String type) {}

    public static Path dataDir() {
        return GitManager.getDataFolderPath();
    }

    /** The lock every write to the data-directory repository takes (projects too, in Combined). */
    public static Object lock() {
        return DATA_DIR_LOCK;
    }

    /** In Combined, {@code projects/} belongs to the Designer's commits, not to config's. */
    private static boolean isConfigPath(String path) {
        return !GitManager.combined() || !(path.equals("projects") || path.startsWith("projects/"));
    }

    /** v1 "is config versioning enabled" signal — the repo state lives entirely in {@code .git}. */
    public static boolean isInitialized() {
        return Files.exists(dataDir().resolve(".git"));
    }

    /** The gateway repository's HEAD commit and branch, for an audit row; blanks when unreadable. */
    public static String[] headAndBranch() {
        try (Git git = GitManager.getGit(dataDir())) {
            org.eclipse.jgit.lib.ObjectId h = git.getRepository().resolve("HEAD");
            return new String[] {h == null ? "" : h.getName(), git.getRepository().getBranch()};
        } catch (Exception e) {
            return new String[] {"", ""};
        }
    }

    /**
     * First-run initialization: {@code git init} at the data dir, write {@code .gitignore}, stage all
     * non-ignored files, and make the baseline commit. Explicit (never auto-run at startup).
     */
    public static void initRepo() {
        synchronized (DATA_DIR_LOCK) {
            if (isInitialized()) {
                throw new RuntimeException("Config versioning is already initialized.");
            }
            GitConfigRemoteRecord configured = GitConfigRemoteRecord.get();
            try (Git git = Git.init().setDirectory(dataDir().toFile())
                    .setInitialBranch(MasterRename.initialBranch(configured == null ? null : configured.getBranch()))
                    .call()) {
                RepoHardening.harden(git.getRepository());
                writeGitignore();
                stageScope(git, false);
                // Authored as the gateway (like the auto-commits), not the acting web user, so the
                // whole config history is uniformly attributed to the gateway.
                git.commit().setMessage(PolicyGate.trailer("Initial config-as-code commit")).setAuthor(gatewayAuthor(), "").call();
            } catch (Exception e) {
                logger.error("Error initializing config versioning repo", e);
                throw new RuntimeException(e);
            }
        }
    }

    /** The gateway's system name — the author for config-repo commits (init + auto-commits). */
    private static String gatewayAuthor() {
        return getContext().getSystemPropertiesManager().getSystemName();
    }

    private static void writeGitignore() throws IOException {
        Path gitignore = dataDir().resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            Files.write(gitignore, IgnoreRules.editManaged("", managed -> GITIGNORE_LINES)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Porcelain list of uncommitted config changes (JGit honors
     * {@code .gitignore}, so db/logs/keystore/projects never appear). JSON key-ordering-only
     * changes are suppressed via {@link GitManager#filterJsonOrderingChanges}.
     */
    public static List<ConfigChange> getStatus() {
        synchronized (DATA_DIR_LOCK) {
            List<ConfigChange> changes = new ArrayList<>();
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                Status s = scopedStatus(git);

                // Build path -> type with deletions taking precedence over modifications.
                TreeMap<String, String> byPath = new TreeMap<>();
                for (String p : s.getUntracked()) byPath.put(p, "UNTRACKED");
                for (String p : s.getAdded()) byPath.put(p, "ADDED");
                for (String p : s.getModified()) byPath.put(p, "MODIFIED");
                for (String p : s.getChanged()) byPath.put(p, "MODIFIED");
                for (String p : s.getMissing()) byPath.put(p, "DELETED");
                for (String p : s.getRemoved()) byPath.put(p, "DELETED");

                Set<String> survivors = GitManager.filterJsonOrderingChanges(repo, dataDir(), byPath.keySet());
                for (Map.Entry<String, String> e : byPath.entrySet()) {
                    // A staged removal under projects/ comes from a Git ignore exclusion (git rm
                    // --cached); nothing else stages one without committing it, so it is config's.
                    if (survivors.contains(e.getKey()) && !isNestedRepo(e.getKey())
                            && (isConfigPath(e.getKey()) || s.getRemoved().contains(e.getKey()))) {
                        changes.add(new ConfigChange(e.getKey(), e.getValue()));
                    }
                }
            } catch (Exception e) {
                logger.error("Error computing data-dir git status", e);
                throw new RuntimeException(e);
            }
            return changes;
        }
    }

    /**
     * Whether a reported path is itself a git repository. {@code config/resources} is one, so JGit
     * reports it modified whenever the inner repo moves while {@code add} can never stage it — the
     * repo is permanently dirty and the auto-committer writes an empty commit on every config
     * change. An inner repo keeps its own history; it is not this repo's change to record.
     */
    private static boolean isNestedRepo(String path) {
        return Files.isDirectory(dataDir().resolve(path).resolve(".git"));
    }

    /**
     * Stage everything the repository covers. JGit honours {@code .gitignore}, so that file is the
     * only thing deciding what is versioned — the module used to stage a hard-coded scope as well,
     * which meant a data-root file nothing ignored was still never committed, and the tree had to
     * hide it. {@code update} stages deletions of tracked files; the first pass adds the rest.
     */
    private static void stageScope(Git git, boolean update) throws Exception {
        git.add().setUpdate(update).addFilepattern(".").call();
    }

    private static Status scopedStatus(Git git) throws Exception {
        return git.status().call();
    }

    /**
     * Once, on a repository initialised before 3.9.12: add the root rules and untrack whatever they
     * now exclude, in one commit. 3.8.0 staged everything its fixed list of root files missed, so
     * an upgraded gateway committed other modules' runtimes and caches.
     *
     * <p>The rules go ABOVE the managed section: git's last match wins, so a root path the user
     * ticked on there keeps its negation. The marker stops it running twice — otherwise a path the
     * user ticks on later would be re-ignored at the next restart.
     *
     * @return the paths untracked, empty when there was nothing to do
     */
    public static List<String> denyRootOnce() {
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized() || Files.exists(dataDir().resolve(ROOT_DENIED_MARKER))) {
                return List.of();
            }
            List<String> untracked = new ArrayList<>();
            try (Git git = GitManager.getGit(dataDir())) {
                Path gitignore = dataDir().resolve(".gitignore");
                List<String> lines = Files.exists(gitignore)
                        ? new ArrayList<>(Files.readAllLines(gitignore)) : new ArrayList<>();
                boolean rulesAdded = lines.stream().noneMatch(l -> l.trim().equals("/*"));
                if (rulesAdded) {
                    // 3.8.0 excluded each of these, so one still versioned was ticked on — and
                    // ticking deletes its line rather than adding a negation, so nothing below the
                    // new rules would keep it. Give it one.
                    List<FastIgnoreRule> before = ignoreRules();
                    List<String> kept = new ArrayList<>();
                    for (String name : PRE_3911_ROOT_PATHS) {
                        boolean isDir = Files.isDirectory(dataDir().resolve(name));
                        if (isTracked(git.getRepository(), name, isDir)
                                && !decide(before, name, isDir).excluded()) {
                            kept.add(name);
                        }
                    }
                    writeIgnoreFile(IgnoreRules.editManaged(String.join("\n", lines), managed -> {
                        int at = managed.indexOf(IgnoreRules.OLD_TAB_MARKER);
                        managed.addAll(at < 0 ? managed.size() : at,
                                List.of(ROOT_RULES_COMMENT, "/*", "!/config/", "!/.gitignore", ""));
                        for (String name : kept) {
                            boolean isDir = Files.isDirectory(dataDir().resolve(name));
                            managed.add("!/" + name + (isDir ? "/" : ""));
                            if (isDir) {
                                managed.add("!/" + name + "/**");
                            }
                        }
                        return managed;
                    }));
                }

                // A .gitignore line has no effect on a tracked file, so the rules alone would leave
                // everything 3.8.0 swept in still versioned. Only what the ROOT rules exclude is
                // untracked; a tracked file deeper down that a template glob covers is left as it was.
                List<FastIgnoreRule> rules = ignoreRules();
                DirCache cache = git.getRepository().readDirCache();
                for (int i = 0; i < cache.getEntryCount(); i++) {
                    String path = cache.getEntry(i).getPathString();
                    int slash = path.indexOf('/');
                    String top = slash < 0 ? path : path.substring(0, slash);
                    if (decide(rules, top, slash >= 0).excluded()) {
                        untracked.add(path);
                    }
                }
                if (rulesAdded || !untracked.isEmpty()) {
                    if (!untracked.isEmpty()) {
                        var rm = git.rm().setCached(true);
                        untracked.forEach(rm::addFilepattern);
                        rm.call();
                    }
                    git.add().addFilepattern(".gitignore").call();
                    git.commit().setMessage(PolicyGate.trailer("Only config/ is versioned by default ("
                                    + untracked.size() + " data-root files untracked)"))
                            .setAuthor(gatewayAuthor(), "").call();
                }
                Files.writeString(dataDir().resolve(ROOT_DENIED_MARKER),
                        "Root ignore rules added by Git Integration 3.9.12.\n");
            } catch (Exception e) {
                logger.error("Could not restrict the config repository to config/", e);
                throw new RuntimeException(e);
            }
            return untracked;
        }
    }

    private static final String POLICY_VERSIONED_MARKER = ".git-module-policy-versioned";

    private static final String COMBINED_RULES_MARKER = ".git-module-combined-rules-v2";

    /**
     * 4.4.0, once, Combined only: the config globs scoped to /config/, the catch-all
     * {@code !/projects/**} dropped and data.bin made an ordinary line
     * ({@link LayoutSwitch#migrateCombinedRules}). Nothing else in the file changes.
     */
    public static boolean combinedRulesOnce() {
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized() || !GitManager.combined()
                    || Files.exists(dataDir().resolve(COMBINED_RULES_MARKER))) {
                return false;
            }
            boolean changed = false;
            try {
                Path gitignore = dataDir().resolve(".gitignore");
                String next = IgnoreRules.editManaged(
                        Files.exists(gitignore) ? Files.readString(gitignore) : "", LayoutSwitch::migrateCombinedRules);
                if (next != null) {
                    writeIgnoreFile(next);
                    try (Git git = GitManager.getGit(dataDir())) {
                        git.add().addFilepattern(".gitignore").call();
                        git.commit().setOnly(".gitignore")
                                .setMessage(PolicyGate.trailer("Scope the config ignore rules to config/"))
                                .setAuthor(gatewayAuthor(), "").call();
                    }
                    changed = true;
                }
                Files.writeString(dataDir().resolve(COMBINED_RULES_MARKER),
                        "Combined ignore rules scoped to config/ by Git Integration 4.4.0.\n");
            } catch (Exception e) {
                logger.error("Could not update the Combined ignore rules; check the Git ignore tab.", e);
            }
            return changed;
        }
    }

    private static final String IGNORE_BLOCKS_MARKER = ".git-module-ignore-blocks";

    /**
     * 4.5.0, once: the module's own {@code .gitignore} lines go between markers
     * ({@link IgnoreRules#split}), so later module writes leave every other line where it is.
     * Commits {@code .gitignore} only.
     *
     * @return whether the file was rewritten
     */
    public static boolean ignoreBlocksOnce() {
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized() || Files.exists(dataDir().resolve(IGNORE_BLOCKS_MARKER))) {
                return false;
            }
            boolean changed = false;
            try {
                Path gitignore = dataDir().resolve(".gitignore");
                if (Files.exists(gitignore)) {
                    String before = Files.readString(gitignore, StandardCharsets.UTF_8);
                    String next = IgnoreRules.editManaged(before, managed -> managed);
                    if (!next.equals(before)) {
                        writeIgnoreFile(next);
                        try (Git git = GitManager.getGit(dataDir())) {
                            git.add().addFilepattern(".gitignore").call();
                            git.commit().setOnly(".gitignore")
                                    .setMessage(PolicyGate.trailer("Mark the Git module's .gitignore lines"))
                                    .setAuthor(gatewayAuthor(), "").call();
                        }
                        changed = true;
                    }
                }
                Files.writeString(dataDir().resolve(IGNORE_BLOCKS_MARKER),
                        "Module .gitignore lines put between markers by Git Integration 4.5.0.\n");
            } catch (Exception e) {
                logger.error("Could not mark the module's .gitignore lines; check the Git ignore tab.", e);
            }
            return changed;
        }
    }

    /**
     * 4.1.0, once: the records rule in an existing {@code .gitignore} is rewritten so the Git policy
     * is versioned and every other record of this module stays out. The marker stops it running
     * again, so a user who later edits those lines keeps their edit.
     *
     * @return whether the file was rewritten
     */
    public static boolean versionPolicyOnce() {
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized() || Files.exists(dataDir().resolve(POLICY_VERSIONED_MARKER))) {
                return false;
            }
            boolean changed = false;
            try {
                Path gitignore = dataDir().resolve(".gitignore");
                String next = IgnoreRules.editManaged(
                        Files.exists(gitignore) ? Files.readString(gitignore) : "", IgnoreRules::versionPolicy);
                if (next != null) {
                    writeIgnoreFile(next);
                    try (Git git = GitManager.getGit(dataDir())) {
                        git.add().addFilepattern(".gitignore").call();
                        git.commit().setOnly(".gitignore")
                                .setMessage(PolicyGate.trailer("Version the Git policy with the config"))
                                .setAuthor(gatewayAuthor(), "").call();
                    }
                    changed = true;
                }
                Files.writeString(dataDir().resolve(POLICY_VERSIONED_MARKER),
                        "Git policy carve-out added by Git Integration 4.1.0.\n");
            } catch (Exception e) {
                logger.error("Could not version the Git policy; check the Git ignore tab.", e);
                throw new RuntimeException(e);
            }
            return changed;
        }
    }

    /**
     * Auto-commit path ({@link ConfigAutoCommitter}): commit all dirty config authored as the
     * gateway (its system name), or no-op on a clean tree (avoids empty commits when a change
     * batch was already covered, e.g. the scan after a restore). Committing does not change
     * on-disk config, so the running gateway is already consistent — no {@code requestScan()}
     * here. Returns whether a commit was made.
     */
    public static boolean commitAllIfDirty(String message) {
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized() || getStatus().isEmpty()) {
                return false;
            }
            List<String> changed = getStatus().stream().map(ConfigChange::path).toList();
            try (Git git = GitManager.getGit(dataDir())) {
                var commit = commitConfig(git, changed, message, gatewayAuthor(), GitManager.combined());
                if (commit == null) {
                    // Left for the auto-committer's retry, or the next change, once the operation ends.
                    logger.info("Config changes not committed: " + midOperation(git.getRepository()));
                    return false;
                }
                GitEvents.fire(GitEvent.of(GitEvent.AUTOCOMMIT).config()
                        .user(gatewayAuthor()).commit(commit.getName())
                        .message(message).files(changed).success());
            } catch (Exception e) {
                logger.error("Error committing data-dir config", e);
                GitEvents.fire(GitEvent.of(GitEvent.AUTOCOMMIT).config()
                        .user(gatewayAuthor()).files(changed).failure(GitEvents.reason(e)));
                throw new RuntimeException(e);
            }
            return true;
        }
    }

    /**
     * Why the gateway repository cannot take a commit of its own now, or null when it can. A
     * Designer's pull that stopped on conflicts (Combined) leaves MERGE_HEAD here: a config commit
     * would complete that merge as the gateway's, recording the remote as merged while dropping
     * its changes.
     */
    public static String midOperation(Repository r) {
        org.eclipse.jgit.lib.RepositoryState state = r.getRepositoryState();
        return switch (state) {
            case SAFE, BARE -> null;
            case MERGING, MERGING_RESOLVED -> "a merge is in progress in the gateway repository (a pull stopped"
                    + " on conflicts). Resolve it in the Designer, or Abort the merge on the Versioning page,"
                    + " first.";
            default -> "the gateway repository is in the middle of an operation (" + state.getDescription()
                    + "). Finish or abort it first.";
        };
    }

    /** {@link #midOperation} for the page's status, or null; never throws. */
    public static String midOperationNow() {
        if (!isInitialized()) {
            return null;
        }
        try (Git git = GitManager.getGit(dataDir())) {
            return midOperation(git.getRepository());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The page's Abort for a merge left open in the gateway repository — a Designer closed while
     * its pull was in conflict. Undoes only the files the merge wrote ({@link ProjectScope#abortMerge}),
     * then reloads config and projects from disk.
     */
    public static void abortOpenMerge() {
        synchronized (DATA_DIR_LOCK) {
            ConfigAutoCommitter.hold(30_000);
            try (Git git = GitManager.getGit(dataDir())) {
                org.eclipse.jgit.lib.RepositoryState state = git.getRepository().getRepositoryState();
                if (state != org.eclipse.jgit.lib.RepositoryState.MERGING
                        && state != org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED) {
                    throw new RuntimeException("No merge is in progress in the gateway repository.");
                }
                ProjectScope.abortMerge(git, null, Set.of());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            } finally {
                ConfigAutoCommitter.hold(6_000);
            }
            applyConfigToRunningGateway();
            if (GitManager.combined()) {
                applyProjects(null);
            }
        }
    }

    /** Throws when {@link #midOperation} has a reason; for actions a person asked for. */
    public static void refuseMidOperation() {
        try (Git git = GitManager.getGit(dataDir())) {
            String why = midOperation(git.getRepository());
            if (why != null) {
                throw new IllegalStateException("Refused: " + why);
            }
        }
    }

    /**
     * Stages and commits config's changes; null, with nothing staged, while the repository is
     * mid-merge, revert or cherry-pick (see {@link #midOperation}).
     */
    static org.eclipse.jgit.revwalk.RevCommit commitConfig(Git git, List<String> changed, String message,
                                                         String author, boolean combined) throws Exception {
        if (midOperation(git.getRepository()) != null) {
            return null;
        }
        CommitCommand cc = git.commit().setMessage(PolicyGate.trailer(message)).setAuthor(author, "");
        Set<String> conflicting = git.status().call().getConflicting();
        if (combined) {
            // The same repository holds every project; stage exactly the config changes,
            // so a Designer's uncommitted work is never swept into an auto-commit. A staged
            // project file that is not a removal (a Git ignore exclusion's rm --cached) is
            // unstaged rather than committed. Not `commit --only`: that takes each path's
            // working-tree file, which would put an excluded file straight back.
            for (String path : changed) {
                if (conflicting.contains(path)) {
                    continue;
                }
                git.add().addFilepattern(path).call();
                git.add().setUpdate(true).addFilepattern(path).call();
            }
            Status staged = git.status().call();
            for (Set<String> set : List.of(staged.getAdded(), staged.getChanged())) {
                for (String path : set) {
                    if (path.equals("projects") || path.startsWith("projects/")) {
                        git.reset().addPath(path).call();
                    }
                }
            }
        } else {
            if (!conflicting.isEmpty()) {
                return null;
            }
            stageScope(git, false);
            stageScope(git, true);
        }
        return cc.call();
    }

    /** Paginated commit history for the config repo. */
    public static List<String[]> history(int skip, int limit) {
        return GitManager.getCommitLog(dataDir(), skip, limit);
    }

    /** Files changed in a commit, as {@code "CHANGE_TYPE:path"}. */
    public static List<String> commitFiles(String commitHash) {
        return GitManager.getCommitFileList(dataDir(), commitHash);
    }

    /**
     * {@code [oldContent, newContent]} for a file (JSON-normalized): at a commit when
     * {@code commitHash} is given, otherwise HEAD vs working tree (uncommitted changes).
     */
    public static List<String> fileDiff(String commitHash, String filePath) {
        if (commitHash == null || commitHash.isBlank()) {
            // The working tree holds far more than the repository: only a file it versions, or
            // one .gitignore lets it version, is read.
            String path = SafePaths.repoPath(filePath);
            try (Git git = GitManager.getGit(dataDir())) {
                boolean tracked = isTracked(git.getRepository(), path, false);
                if (IgnoreRules.denied(path) || !tracked && decide(ignoreRules(), path, false).excluded()) {
                    throw new IllegalArgumentException(path + " is not versioned (.gitignore excludes it).");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return GitManager.getWorkingTreeDiffContent(dataDir(), path);
        }
        // History holds what was once versioned, which a later .gitignore cannot take back; the
        // never-versioned paths stay unreadable here too.
        String path = SafePaths.repoPath(filePath);
        if (IgnoreRules.denied(path)) {
            throw new IllegalArgumentException(path + " is never shown (keys, certificates, local config).");
        }
        return GitManager.getCommitFileDiffContent(dataDir(), commitHash, path);
    }

    // ----- Remote (manual push only — never pushed automatically) -----

    private static final String ORIGIN = "origin";

    /** Last manual push outcome for the page's status line (in-memory; resets on restart). */
    private static volatile long lastPushTime;
    private static volatile String lastPushError;

    public static long getLastPushTime() {
        return lastPushTime;
    }

    public static String getLastPushError() {
        return lastPushError;
    }

    /** Save (create or update) the config remote: persist the record and sync origin in .git/config. */
    public static void saveRemote(String uri, String branch, long sshKeyId, long httpsCredentialId) {
        if (uri == null || uri.isBlank()) {
            throw new RuntimeException("Remote URI cannot be empty.");
        }
        if (branch == null || branch.isBlank()) {
            throw new RuntimeException("Branch cannot be empty.");
        }
        synchronized (DATA_DIR_LOCK) {
            GitConfigRemoteRecord record = GitConfigRemoteRecord.get();
            if (record == null) {
                record = new GitConfigRemoteRecord();
            } else if (isInitialized()) {
                try (Git git = GitManager.getGit(dataDir())) {
                    OriginSync.rememberIfUnset(git, record.getBranch());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            record.setUri(uri.trim());
            record.setBranch(branch.trim());
            record.setSshKeyId(sshKeyId);
            record.setHttpsCredentialId(httpsCredentialId);
            record.save();
            // Origin follows the EFFECTIVE record, which is not the one just saved when it went
            // to core under an active mode's override.
            if (!isInitialized()) {
                return;
            }
            try (Git git = GitManager.getGit(dataDir())) {
                GitConfigRemoteRecord effective = ModeStore.effective(GitConfigRemoteRecord::get);
                if (effective != null) {
                    syncOrigin(git, effective);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * A fetch of one followed branch, with a branch deleted on the remote (usually merged) said
     * plainly rather than as JGit's "Remote does not have refs/heads/x available for fetch".
     */
    private static void fetchBranch(FetchCommand fetch, String branch) throws Exception {
        try {
            fetch.call();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            String m = String.valueOf(e.getMessage()) + " " + String.valueOf(e.getCause());
            if (m.contains("does not have") || m.contains("not advertise")) {
                throw new RuntimeException(PullGuard.missingBranch(branch, ORIGIN) + PullGuard.DETAILS + e, e);
            }
            throw PullGuard.error(e);
        }
    }

    /** The remote branch's tip after fetching it, or null when the remote does not have it. */
    private static ObjectId fetchTipOrNull(Git git, GitConfigRemoteRecord remote, String branch) throws Exception {
        FetchCommand fetch = git.fetch().setRemote(ORIGIN)
                .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":" + trackingRef(branch)));
        authOrigin(git, fetch, remote);
        try {
            fetch.call();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            String m = String.valueOf(e.getMessage()) + " " + String.valueOf(e.getCause());
            if (m.contains("does not have") || m.contains("not advertise")) {
                return null;
            }
            throw PullGuard.error(e);
        }
        return git.getRepository().resolve(trackingRef(branch));
    }

    /** Origin from the effective record; see {@link OriginSync}. */
    static void syncOrigin(Git git, GitConfigRemoteRecord remote) throws IOException {
        OriginSync.apply(git, remote.getUri(), remote.getBranch());
    }

    /** {@link #syncOrigin} from outside, after the effective record changed (a reset to core). */
    public static void resyncOrigin() {
        GitConfigRemoteRecord remote = ModeStore.effective(GitConfigRemoteRecord::get);
        if (remote == null || !isInitialized()) {
            return;
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                syncOrigin(git, remote);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Origin set from the record, then the record's credential — never one without the other. */
    private static void authOrigin(Git git, org.eclipse.jgit.api.TransportCommand<?, ?> cmd,
                                   GitConfigRemoteRecord remote) throws Exception {
        syncOrigin(git, remote);
        GitManager.setAuthenticationFromIds(cmd, remote.getUri(), remote.getSshKeyId(), remote.getHttpsCredentialId());
    }

    public static void removeRemote() {
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                git.remoteRemove().setRemoteName(ORIGIN).call();
            } catch (Exception e) {
                logger.warn("Could not remove origin from the config repo", e);
            }
            GitConfigRemoteRecord record = GitConfigRemoteRecord.get();
            if (record != null) {
                record.delete();
            }
            lastPushTime = 0;
            lastPushError = null;
        }
    }

    /**
     * Completely remove config versioning: delete {@code <dataDir>/.git} (all history), the
     * {@code .gitignore}, and the remote record. **Credential records are kept** (they are shared
     * with the Designer per-project repos). Afterward {@link #isInitialized()} is false, so the
     * page reverts to the Initialize screen. Opens no JGit handle (so the working tree isn't
     * locked during the recursive delete).
     */
    /**
     * Deletes the gateway repository and its {@code .gitignore}. The saved remote and its credential
     * link stay unless {@code removeRemote}, so initialising again (or Update from remote) reconnects.
     */
    public static void deleteRepo(boolean removeRemote) {
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized()) {
                throw new RuntimeException("Config versioning is not initialized.");
            }
            if (GitManager.combined()) {
                throw new RuntimeException("This repository holds every project too (Combined layout)."
                        + " Switch the layout back to Separate first, then delete versioning.");
            }
            try {
                GitConfigRemoteRecord record = removeRemote ? GitConfigRemoteRecord.get() : null;
                if (record != null) {
                    record.delete();
                }
                deleteRecursively(dataDir().resolve(".git"));
                Files.deleteIfExists(dataDir().resolve(".gitignore"));
                lastPushTime = 0;
                lastPushError = null;
            } catch (Exception e) {
                logger.error("Failed to remove config versioning", e);
                throw new RuntimeException(e);
            }
        }
    }

    /** Recursively delete a path (depth-first), tolerating a missing root. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException("Could not delete " + p, e);
                        }
                    });
        }
    }

    /**
     * The remote branch the checked-out branch pushes to ({@link ProjectScope#pushTarget}): its
     * upstream, else the configured branch for the repository's home branch, else the same name.
     * Falls back to the configured branch when HEAD is detached, for the status line only.
     */
    public static String pushBranch(Repository repo, GitConfigRemoteRecord remote) {
        try {
            return ProjectScope.pushTarget(repo, ORIGIN, remote.getBranch());
        } catch (Exception e) {
            return remote.getBranch();
        }
    }

    /** {@code [checked-out branch, remote branch]} a push would use now; for the page's labels. */
    public static String[] pushTargetNow(GitConfigRemoteRecord remote) {
        if (!isInitialized()) {
            return new String[] {"", remote.getBranch()};
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                return new String[] {git.getRepository().getBranch(), pushBranch(git.getRepository(), remote)};
            } catch (Exception e) {
                return new String[] {"", remote.getBranch()};
            }
        }
    }

    /**
     * Manual push of the checked-out branch: to its upstream, or — the first time — to the
     * configured branch (home branch) or the same name, recorded as its upstream. Never another
     * branch onto the configured one: in the Combined layout a Designer can check one out.
     *
     * @return {@code [local branch, remote branch]}
     */
    public static String[] push() {
        PolicyGate.check(PolicyGate.Action.PUSH);
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote configured.");
        }
        String[] target = {"", remote.getBranch()};
        try {
            target = doPush(remote);
            GitEvents.fire(GitEvent.of(GitEvent.PUSH).config()
                    .user(gatewayAuthor()).branch(target[1]).remote(remote.getUri())
                    .message("Pushed " + target[0] + " to " + target[1]).success());
            return target;
        } catch (RuntimeException e) {
            GitEvents.fire(GitEvent.of(GitEvent.PUSH).config()
                    .user(gatewayAuthor()).branch(target[1]).remote(remote.getUri())
                    .failure(GitEvents.reason(e)));
            throw e;
        }
    }

    private static String[] doPush(GitConfigRemoteRecord remote) {
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                String local = git.getRepository().getBranch();
                String target = ProjectScope.pushTarget(git.getRepository(), ORIGIN, remote.getBranch());
                // Never push over a branch laid out another way or with unrelated history.
                String layout = LayoutCheck.problem(git.getRepository(), fetchTipOrNull(git, remote, target),
                        git.getRepository().resolve(Constants.HEAD), ORIGIN + "/" + target, GitManager.combined());
                if (layout != null) {
                    throw new RuntimeException(layout);
                }
                PushCommand push = git.push()
                        .setRemote(ORIGIN)
                        .setRefSpecs(new RefSpec("refs/heads/" + local + ":refs/heads/" + target));
                authOrigin(git, push, remote);
                for (PushResult result : push.call()) {
                    for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                        RemoteRefUpdate.Status st = update.getStatus();
                        if (st != RemoteRefUpdate.Status.OK
                                && st != RemoteRefUpdate.Status.UP_TO_DATE) {
                            if (st == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                                throw new RuntimeException("Push rejected: the remote branch '"
                                        + target + "' has commits this gateway doesn't "
                                        + "have (non-fast-forward). Reconcile them before pushing — "
                                        + "bring the remote's commits into this gateway, or reset the "
                                        + "remote branch if its history isn't needed (e.g. it was "
                                        + "seeded with a README).");
                            }
                            throw new RuntimeException("Push rejected: " + st
                                    + (update.getMessage() != null ? " — " + update.getMessage() : ""));
                        }
                    }
                }
                // Advance the local remote-tracking ref so the history can mark which commits are
                // on the remote (our refspec doesn't update it automatically).
                Repository repo = git.getRepository();
                ObjectId head = repo.resolve("HEAD");
                if (head != null) {
                    RefUpdate ru = repo.updateRef(trackingRef(target));
                    ru.setNewObjectId(head);
                    ru.setForceUpdate(true);
                    ru.update();
                }
                ProjectScope.setUpstream(repo, ORIGIN, target);
                lastPushTime = System.currentTimeMillis();
                lastPushError = null;
                return new String[] {local, target};
            } catch (PolicyGate.Refused e) {
                lastPushTime = System.currentTimeMillis();
                lastPushError = e.getMessage();
                throw e;
            } catch (Exception e) {
                String friendly = friendlyPushError(e);
                lastPushTime = System.currentTimeMillis();
                lastPushError = friendly;
                logger.error("Push of the config repo failed", e);
                throw new RuntimeException(friendly, e);
            }
        }
    }

    /** Map a raw push failure (transport/auth/JGit) to a concise, user-facing message. */
    private static String friendlyPushError(Exception e) {
        String raw = e.getMessage() == null ? e.toString() : e.getMessage();
        // Already-friendly messages we threw ourselves (e.g. the non-fast-forward explanation).
        if (raw.startsWith("Push rejected:")) {
            return raw;
        }
        String low = raw.toLowerCase();
        // Authorization: authenticated OK, but this credential can't push (read-only / no write
        // scope). JGit surfaces the refused receive-pack service, usually an HTTP 403.
        if (low.contains("receive-pack") || low.contains("not permitted")
                || low.contains(" 403") || low.contains("forbidden")
                || low.contains("permission denied") || low.contains("write access")) {
            return "Push denied: the credential can read this repository but isn't allowed to push "
                    + "to it. Grant it write access, or use a token/key with write (push) permission.";
        }
        // Authentication: the gateway couldn't sign in at all — wrong or expired credential.
        if (low.contains("not authorized") || low.contains("authentication")
                || low.contains("auth fail") || low.contains(" 401")
                || low.contains("invalid username") || low.contains("invalid credential")) {
            return "Authentication failed: the gateway couldn't sign in to the remote. Check the "
                    + "remote's credential — the token, password or SSH key may be wrong or expired.";
        }
        if (low.contains("unable to access") || low.contains("cannot open")
                || low.contains("unknownhost") || low.contains("unknown host")
                || low.contains("connection") || low.contains("timed out")
                || low.contains("not found") || low.contains("could not read")) {
            return "Could not reach the remote repository. Check the URL and that the gateway has "
                    + "network access to it.";
        }
        return "Push failed: " + raw;
    }

    private static String trackingRef(String branch) {
        return "refs/remotes/" + ORIGIN + "/" + branch;
    }

    /**
     * Ref pointers for the history: {@code [localHeadHash, remoteHeadHash]} (full hashes). The
     * remote head is the local remote-tracking ref that {@link #push()} advances; it is {@code ""}
     * when no remote is configured or nothing has been pushed. These mark just the two tip commits
     * (like git's branch pointers), not every commit.
     */
    public static String[] pointerHashes() {
        String local = "";
        String remote = "";
        if (!isInitialized()) {
            return new String[]{local, remote};
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                ObjectId head = repo.resolve("HEAD");
                if (head != null) {
                    local = head.getName();
                }
                GitConfigRemoteRecord r = GitConfigRemoteRecord.get();
                if (r != null) {
                    ObjectId trackingId = repo.resolve(trackingRef(pushBranch(repo, r)));
                    if (trackingId != null) {
                        remote = trackingId.getName();
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not resolve config repo ref pointers", e);
            }
        }
        return new String[]{local, remote};
    }

    /**
     * Fetches the branch Remote sync pushes to, so ahead/behind and {@link #relation} are current.
     * Changes nothing in the working tree.
     */
    public static void fetchConfig() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote configured.");
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                String branch = pushBranch(git.getRepository(), remote);
                FetchCommand fetch = git.fetch().setRemote(ORIGIN).setRemoveDeletedRefs(true)
                        .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":" + trackingRef(branch)));
                authOrigin(git, fetch, remote);
                fetchBranch(fetch, branch);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
    }

    /** Commits on the fetched remote branch that are not here, and whether it shares no history. */
    public record Relation(int behind, boolean unrelated) {}

    /**
     * What Join remote history would delete: files on the fetched remote branch, outside
     * {@code config/} (and {@code projects/} in Combined), that this gateway does not have. The
     * join commits this gateway's files as they are, so each of these goes from the branch.
     */
    public static List<String> joinDeletions() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null || !isInitialized()) {
            return List.of();
        }
        boolean combined = GitManager.combined();
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                ObjectId tip = repo.resolve(trackingRef(pushBranch(repo, remote)) + "^{tree}");
                if (tip == null) {
                    return List.of();
                }
                List<String> out = new ArrayList<>();
                try (org.eclipse.jgit.treewalk.TreeWalk tw = new org.eclipse.jgit.treewalk.TreeWalk(repo)) {
                    tw.addTree(tip);
                    tw.setRecursive(true);
                    while (tw.next()) {
                        String p = tw.getPathString();
                        if (p.startsWith("config/") || (combined && p.startsWith("projects/"))
                                || p.equals(".gitignore")) {
                            continue;
                        }
                        if (!Files.exists(dataDir().resolve(p))) {
                            out.add(p);
                        }
                    }
                }
                return out;
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    public static Relation relation() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null || !isInitialized()) {
            return new Relation(0, false);
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                ObjectId head = repo.resolve("HEAD");
                ObjectId tip = repo.resolve(trackingRef(pushBranch(repo, remote)));
                if (head == null || tip == null) {
                    return new Relation(0, false);
                }
                try (RevWalk walk = new RevWalk(repo)) {
                    walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
                    walk.markStart(walk.parseCommit(head));
                    walk.markStart(walk.parseCommit(tip));
                    if (walk.next() == null) {
                        return new Relation(0, true);
                    }
                    walk.reset();
                    walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.ALL);
                    walk.markStart(walk.parseCommit(tip));
                    walk.markUninteresting(walk.parseCommit(head));
                    int n = 0;
                    for (RevCommit ignored : walk) {
                        n++;
                    }
                    return new Relation(n, false);
                }
            } catch (Exception e) {
                return new Relation(0, false);
            }
        }
    }

    /**
     * Pull on the Versioning page: the push branch merged in (fast-forward or merge), as
     * {@link #pullFromBranch} does for a delivery but without the GitHub-merged check, which is for
     * unattended deliveries. Refuses with the files when anything is uncommitted.
     */
    public static PullResult pullConfig() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote configured.");
        }
        PolicyGate.checkDelivery("pull");
        String branch;
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                branch = pushBranch(git.getRepository(), remote);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return pullFromBranch(branch, false);
    }

    /**
     * Join remote history: for a remote branch with no commit in common with this gateway's
     * repository (each gateway's repository starts with its own first commit), move this branch to
     * the remote tip keeping the working tree as it is (soft reset), and commit what differs as one
     * commit. Nothing on the gateway changes; push and pull work from then on.
     *
     * @return the new commit
     */
    public static String joinRemoteHistory(String user) {
        PolicyGate.check(PolicyGate.Action.BRANCH_SWITCH);
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote configured.");
        }
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized()) {
                throw new RuntimeException("Config versioning is not initialised.");
            }
            refuseMidOperation();
            ConfigAutoCommitter.hold(30_000);
            try (Git git = GitManager.getGit(dataDir())) {
                commitAllIfDirty("Config changes before joining the remote history");
                Repository repo = git.getRepository();
                String branch = pushBranch(repo, remote);
                FetchCommand fetch = git.fetch().setRemote(ORIGIN).setRemoveDeletedRefs(true)
                        .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":" + trackingRef(branch)));
                authOrigin(git, fetch, remote);
                fetchBranch(fetch, branch);
                ObjectId tip = repo.resolve(trackingRef(branch));
                if (tip == null) {
                    throw new RuntimeException(PullGuard.missingBranch(branch, ORIGIN));
                }
                String layout = LayoutCheck.problem(repo, tip, null, ORIGIN + "/" + branch, GitManager.combined());
                if (layout != null) {
                    throw new RuntimeException(layout);
                }
                if (!relation().unrelated()) {
                    throw new RuntimeException(ORIGIN + "/" + branch + " already shares history with this gateway's"
                            + " repository; use Pull instead. Nothing was changed.");
                }
                git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(tip.name()).call();
                git.add().addFilepattern(".").call();
                git.add().setUpdate(true).addFilepattern(".").call();
                String who = user != null ? user : gatewayAuthor();
                String commit = git.commit()
                        .setMessage(PolicyGate.trailer("This gateway's config on top of " + ORIGIN + "/" + branch))
                        .setAuthor(who, "").setCommitter(gatewayAuthor(), "").call().getName();
                org.eclipse.jgit.lib.StoredConfig cfg = repo.getConfig();
                cfg.setString("branch", repo.getBranch(), "remote", ORIGIN);
                cfg.setString("branch", repo.getBranch(), "merge", "refs/heads/" + branch);
                cfg.save();
                GitEvents.fire(GitEvent.of(GitEvent.PULL).config().user(who).branch(branch).commit(commit)
                        .message("Joined " + ORIGIN + "/" + branch + ": this gateway's config committed on top").success());
                return commit;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            } finally {
                ConfigAutoCommitter.hold(6_000);
            }
        }
    }

    /**
     * Number of commits on the local branch that are not yet on the remote tracking ref — the
     * "unsynced" count for the page's sync indicator. When nothing has been pushed yet, every
     * commit counts. 0 when no remote is configured or the branches are level.
     */
    public static int aheadCount() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        // Guard on the repo existing too: after a gwbk restore the remote record survives but
        // .git does not, and calling getGit() on the missing repo would log a spurious ERROR
        // on every /remote poll. Not-initialized ⇒ nothing pushed ⇒ 0 ahead.
        if (remote == null || !isInitialized()) {
            return 0;
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                ObjectId head = repo.resolve("HEAD");
                if (head == null) {
                    return 0;
                }
                try (RevWalk walk = new RevWalk(repo)) {
                    walk.markStart(walk.parseCommit(head));
                    ObjectId trackingId = repo.resolve(trackingRef(pushBranch(repo, remote)));
                    if (trackingId != null) {
                        walk.markUninteresting(walk.parseCommit(trackingId));
                    }
                    int count = 0;
                    for (RevCommit ignored : walk) {
                        count++;
                    }
                    return count;
                }
            } catch (Exception e) {
                logger.warn("Could not compute the unsynced commit count", e);
                return 0;
            }
        }
    }

    /**
     * A throwaway repository for an ls-remote with no local one. Without a repository JGit reads
     * http.followRedirects from nowhere and follows the first redirect, credential and all.
     */
    private static Repository noRedirects() {
        Repository mem = new org.eclipse.jgit.internal.storage.dfs.InMemoryRepository(
                new org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription("remote-test"));
        mem.getConfig().setString("http", null, "followRedirects", "false");
        return mem;
    }

    /** Validate URI + credential with an ls-remote — touches no local or remote state. */
    public static void testRemote(String uri, long sshKeyId, long httpsCredentialId) {
        GitConfigRemoteRecord saved = GitConfigRemoteRecord.get();
        String mismatch = saved == null ? null
                : PolicyGate.credentialMismatch(PolicyGate.hostOf(saved.getUri()), uri);
        if (mismatch != null) {
            throw new RuntimeException("The saved credential belongs to " + PolicyGate.hostOf(saved.getUri())
                    + "; enter the secret again to test " + PolicyGate.hostOf(uri) + ".");
        }
        try {
            try (Repository mem = noRedirects()) {
                LsRemoteCommand lsRemote = Git.wrap(mem).lsRemote().setRemote(uri).setHeads(true);
                GitManager.setAuthenticationFromIds(lsRemote, uri, sshKeyId, httpsCredentialId);
                lsRemote.call();
            }
        } catch (PolicyGate.Refused e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /** Test with raw plaintext secrets (not yet persisted) — used by the inline Configure drawer. */
    public static void testRemoteRaw(String uri, String sshKeyPlaintext, String httpsUser,
                                     String httpsPassword) {
        try {
            try (Repository mem = noRedirects()) {
                LsRemoteCommand lsRemote = Git.wrap(mem).lsRemote().setRemote(uri).setHeads(true);
                GitManager.setAuthenticationRaw(lsRemote, uri, sshKeyPlaintext, httpsUser, httpsPassword);
                lsRemote.call();
            }
        } catch (PolicyGate.Refused e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /**
     * Bring config up to the configured remote's HEAD and apply it to the running gateway. Handles
     * two entry states:
     * <ul>
     *   <li><b>Not initialized</b> — e.g. after a gateway-backup restore, which brings back
     *       {@code config/} and the surviving {@link GitConfigRemoteRecord} but <em>not</em>
     *       {@code <dataDir>/.git}: the repo is re-created, {@code origin} is wired from the record,
     *       and the remote branch is fetched and laid down.</li>
     *   <li><b>Initialized but behind</b> — fetch and fast-forward the working tree to the remote tip.</li>
     * </ul>
     * Refuses when the local branch has diverged (commits not on the remote) so nothing is discarded —
     * push or restore those first. The working tree is forced to exactly the remote tree the same way
     * {@link GitManager#restoreTree} does (overwrite tracked, {@code git clean} untracked), so gitignored
     * key material (keystore / projects / db / {@code config/local}) is preserved. Returns the new HEAD
     * short hash.
     */
    public static String updateFromRemote() {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote is configured to update from.");
        }
        String branch = remote.getBranch();
        synchronized (DATA_DIR_LOCK) {
            boolean fresh = !isInitialized();
            try {
                if (fresh) {
                    // Re-establish the repo from the saved remote (post-restore recovery).
                    try (Git git = Git.init().setDirectory(dataDir().toFile())
                            .setInitialBranch(MasterRename.initialBranch(branch)).call()) {
                        RepoHardening.harden(git.getRepository());
                        git.remoteAdd().setName(ORIGIN).setUri(new URIish(remote.getUri())).call();
                    }
                    if (!Files.exists(dataDir().resolve(".gitignore"))) {
                        writeGitignore();
                    }
                }
                try (Git git = GitManager.getGit(dataDir())) {
                    Repository repo = git.getRepository();
                    if (!fresh) {
                        // The hard reset below would take them, in Combined every project's too.
                        String busy = midOperation(repo);
                        if (busy != null) {
                            throw new RuntimeException("Refused: " + busy);
                        }
                        List<String> edits = IgnitionReformat.realChanges(repo, git.status().call()).stream()
                                .filter(p -> !isNestedRepo(p)).sorted().toList();
                        if (!edits.isEmpty()) {
                            throw new RuntimeException("Update from remote would discard " + edits.size()
                                    + " uncommitted change(s); commit or discard them first: "
                                    + String.join(", ", edits.subList(0, Math.min(10, edits.size())))
                                    + (edits.size() > 10 ? ", …" : ""));
                        }
                    }

                    FetchCommand fetch = git.fetch().setRemote(ORIGIN).setRemoveDeletedRefs(true)
                            .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":" + trackingRef(branch)));
                    authOrigin(git, fetch, remote);
                    fetchBranch(fetch, branch);

                    ObjectId remoteId = repo.resolve(trackingRef(branch));
                    if (remoteId == null) {
                        throw new RuntimeException(PullGuard.missingBranch(branch, ORIGIN));
                    }
                    String layout = LayoutCheck.problem(repo, remoteId, fresh ? null : repo.resolve(Constants.HEAD), ORIGIN + "/" + branch,
                            GitManager.combined());
                    if (layout != null) {
                        throw new RuntimeException(layout);
                    }

                    ObjectId head = fresh ? null : repo.resolve(Constants.HEAD);
                    if (head != null) {
                        if (head.equals(remoteId)) {
                            return remoteId.abbreviate(7).name();  // already up to date
                        }
                        try (RevWalk walk = new RevWalk(repo)) {
                            boolean remoteContainsLocal = walk.isMergedInto(
                                    walk.parseCommit(head), walk.parseCommit(remoteId));
                            if (!remoteContainsLocal) {
                                throw new RuntimeException("Local config has commit(s) not on the remote. "
                                        + "Push or restore first — update-from-remote won't discard them.");
                            }
                        }
                    }

                    // Point the branch (and HEAD) at the remote tip, then bring the working tree to it.
                    git.branchCreate().setName(branch).setStartPoint(remoteId.name()).setForce(true).call();
                    repo.updateRef(Constants.HEAD).link("refs/heads/" + branch);
                    if (fresh) {
                        // Fresh repo laid over a restored working tree: config/ exists but is untracked,
                        // so a hard reset would hit checkout conflicts. Stage the remote tree (MIXED)
                        // then overwrite the tracked files via a path-checkout. clean() runs WITHOUT
                        // setCleanDirectories(true): a directory clean deletes untracked dirs wholesale,
                        // taking gitignored runtime data nested inside them with it (e.g. the tag value
                        // store config/ignition/tags/valueStore.idb) — JGit does not spare it.
                        git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(remoteId.name()).call();
                        git.checkout().setStartPoint(remoteId.name()).setAllPaths(true).call();
                        git.clean().call();
                    } else {
                        // Existing repo (fast-forward): a hard reset moves tracked files (add/modify/
                        // delete) to the remote tip and leaves untracked/ignored runtime data — the tag
                        // value store, db, keystore — untouched. No clean needed.
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteId.name()).call();
                    }

                    applyConfigToRunningGateway();
                    if (GitManager.combined()) {
                        applyProjects(changedBetween(repo, head, remoteId));
                    }
                    return remoteId.abbreviate(7).name();
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Update-from-remote failed", e);
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
    }

    /** Outcome of one {@link #replaceFromBranch} run. {@code commit} is empty when nothing was done. */
    public record ReplaceResult(boolean changed, String commit, int discardedCommits,
                                int discardedChanges, List<String> files, boolean rolledBack,
                                String refused, boolean firstRefusal) {
        public ReplaceResult(boolean changed, String commit, int discardedCommits, int discardedChanges,
                             List<String> files, boolean rolledBack) {
            this(changed, commit, discardedCommits, discardedChanges, files, rolledBack, null, false);
        }

        /** Not installed: the tip is not merged through GitHub ({@code firstRefusal}: say so in the log). */
        static ReplaceResult refused(String why, boolean first) {
            return new ReplaceResult(false, "", 0, 0, List.of(), false, why, first);
        }
    }

    /** Ref holding the last remote tip a branch-follow replaced the config with. */
    static final String SYNCED_REF = "refs/git-module/config-synced";

    /**
     * Makes config exactly the remote branch: fetch, then, unless the tip is the one last applied and
     * HEAD already has its tree, point the local branch and HEAD at it, hard-reset, remove untracked
     * files the repository does not ignore, and apply with a settings rescan. Local commits and
     * uncommitted changes are discarded and counted; a branch moved backwards is followed.
     *
     * <p>Untracked files are removed one by one, not by {@code git clean -d}: a directory clean takes
     * gitignored runtime data nested inside an untracked directory with it (see
     * {@link #updateFromRemote()}).
     */
    public static ReplaceResult replaceFromBranch(String branch) {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote is configured to follow.");
        }
        if (branch == null || branch.isBlank()) {
            throw new RuntimeException("No branch is set to follow.");
        }
        synchronized (DATA_DIR_LOCK) {
            if (!isInitialized()) {
                throw new RuntimeException("Config versioning is not initialised.");
            }
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();

                FetchCommand fetch = git.fetch().setRemote(ORIGIN).setRemoveDeletedRefs(true)
                        .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":" + trackingRef(branch)));
                authOrigin(git, fetch, remote);
                fetchBranch(fetch, branch);

                ObjectId tip = repo.resolve(trackingRef(branch));
                if (tip == null) {
                    throw new RuntimeException(PullGuard.missingBranch(branch, ORIGIN));
                }
                String layout = LayoutCheck.problem(repo, tip, null, ORIGIN + "/" + branch,
                        GitManager.combined());
                if (layout != null) {
                    throw new RuntimeException(layout);
                }
                ObjectId head = repo.resolve(Constants.HEAD);
                ObjectId applied = repo.resolve(SYNCED_REF);
                if (!tip.equals(applied)) {
                    String notMerged = GitHubMerged.refusal(repo, tip, remote.getUri());
                    if (notMerged != null) {
                        return ReplaceResult.refused(notMerged, GitHubMerged.firstRefusal("config", tip));
                    }
                }

                try (RevWalk walk = new RevWalk(repo)) {
                    RevCommit tipCommit = walk.parseCommit(tip);
                    if (tip.equals(applied) && head != null
                            && walk.parseCommit(head).getTree().equals(tipCommit.getTree())) {
                        return new ReplaceResult(false, "", 0, 0, List.of(), false);
                    }

                    // Commits of the branch itself that the gateway had applied are not "local": a
                    // branch moved backwards drops them, but nobody on this gateway made them.
                    int discardedCommits = 0;
                    boolean rolledBack = head != null && !head.equals(tip) && walk.isMergedInto(
                            walk.parseCommit(tip), walk.parseCommit(head));
                    if (head != null) {
                        walk.reset();
                        walk.markStart(walk.parseCommit(head));
                        walk.markUninteresting(tipCommit);
                        if (applied != null) {
                            walk.markUninteresting(walk.parseCommit(applied));
                        }
                        for (RevCommit ignored : walk) {
                            discardedCommits++;
                        }
                    }
                    // The hard reset below takes every uncommitted change, projects' too in Combined.
                    int discardedChanges = (int) IgnitionReformat.realChanges(repo, git.status().call()).stream()
                            .filter(p -> !isNestedRepo(p)).count();
                    List<String> files = changedBetween(repo, head, tip);

                    // Hold the auto-committer first: it must not commit the rescan's own events, or
                    // a local commit lands on top of the tip just applied.
                    ConfigAutoCommitter.hold(30_000);
                    git.branchCreate().setName(branch).setStartPoint(tip.name()).setForce(true).call();
                    repo.updateRef(Constants.HEAD).link("refs/heads/" + branch);
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(tip.name()).call();
                    // Combined: a project that exists only on this gateway is not the branch's to
                    // delete; it stays, and the Logs tab says so.
                    List<String> kept = ProjectScope.removeUntracked(repo, GitManager.combined());
                    for (String k : kept) {
                        logger.warnf("Sync - replace kept %s: it is on this gateway only, not on %s.", k, branch);
                        GitEvents.fire(GitEvent.of(GitEvent.SYNC).config().branch(branch).files(List.of(k))
                                .warning("Kept " + k + ": a project on this gateway only, not on " + branch
                                        + ". Commit it or delete it."));
                    }

                    RefUpdate mark = repo.updateRef(SYNCED_REF);
                    mark.setNewObjectId(tip);
                    mark.setForceUpdate(true);
                    mark.update();

                    applyConfigToRunningGateway();
                    if (GitManager.combined()) {
                        applyProjects(files);
                    }
                    ConfigAutoCommitter.hold(6_000);
                    return new ReplaceResult(true, tip.name(), discardedCommits, discardedChanges, files,
                            rolledBack);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Config branch follow failed", e);
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
    }

    /**
     * Combined layout: commit one project's folder exactly as it is on disk (a runner release
     * replaced it, or the project was deleted). Nothing outside {@code projects/<name>/}.
     *
     * @return the short hash, or "" when there was nothing to commit
     */
    public static String commitProjectFolder(String project, String message) {
        synchronized (DATA_DIR_LOCK) {
            RepoLocator.Repo repo = GitManager.repoFor(project);
            try (Git git = GitManager.getGit(repo.root())) {
                RevCommit c = ProjectScope.commitAll(git, repo, message, gatewayAuthor());
                return c == null ? "" : c.abbreviate(7).name();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Combined layout: projects tracked in the gateway repository whose folder has gone, so their
     * removal is recorded. The Designer's commits are limited to its own project and config's
     * auto-commit leaves {@code projects/} alone, so nothing else would ever commit it.
     */
    public static List<String> trackedProjects() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (!isInitialized() || !GitManager.combined()) {
            return List.of();
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                DirCache cache = git.getRepository().readDirCache();
                for (int i = 0; i < cache.getEntryCount(); i++) {
                    String p = cache.getEntry(i).getPathString();
                    int slash = p.indexOf('/', 9);
                    if (p.startsWith("projects/") && slash > 9) {
                        out.add(p.substring(9, slash));
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not list the projects in the gateway repository", e);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Switch the repository layout (Versioning page, admin only). Config's own pending changes are
     * committed first, as the auto-committer would within seconds anyway; anything else uncommitted
     * — a project's Designer work — refuses the switch and changes nothing.
     */
    public static LayoutSwitch.Result switchLayout(RepoLocator.Layout target, boolean restoreHistory, String user) {
        return switchLayout(target, restoreHistory, false, user);
    }

    /** {@code archiveLeftovers}: to Separate, move a leftover {@code projects/<name>/.git} to the archive first. */
    public static LayoutSwitch.Result switchLayout(RepoLocator.Layout target, boolean restoreHistory,
                                                   boolean archiveLeftovers, String user) {
        PolicyGate.check(PolicyGate.Action.LAYOUT_SWITCH);
        if (!isInitialized()) {
            throw new RuntimeException("Config versioning is not initialised; initialise it first.");
        }
        synchronized (DATA_DIR_LOCK) {
            refuseMidOperation();
            ConfigAutoCommitter.hold(30_000);
            try {
                commitAllIfDirty("Config changes before switching the repository layout");
                org.eclipse.jgit.lib.PersonIdent author = new org.eclipse.jgit.lib.PersonIdent(gatewayAuthor(), "");
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                LayoutSwitch.Result r = target == RepoLocator.Layout.COMBINED
                        ? LayoutSwitch.toCombined(dataDir(), author, stamp, IgnitionReformat::realChanges)
                        : LayoutSwitch.toSeparate(dataDir(), author, stamp, restoreHistory, archiveLeftovers,
                                IgnitionReformat::realChanges);
                if (target == RepoLocator.Layout.SEPARATE) {
                    // The Designer only offers git to a registered project; a repository made here
                    // needs the same marker an init from the Designer writes.
                    for (String p : r.projects()) {
                        if (com.operametrix.ignition.git.records.GitProjectsConfigRecord.findByProjectName(p) == null) {
                            var rec = new com.operametrix.ignition.git.records.GitProjectsConfigRecord();
                            rec.setProjectName(p);
                            rec.save();
                        }
                    }
                }
                GitEvents.fire(GitEvent.of("layout").config().user(user != null ? user : gatewayAuthor()).commit(r.commit())
                        .message(target == RepoLocator.Layout.COMBINED
                                ? "Combined " + r.projects().size() + " project(s) into the gateway repository"
                                : "Separated " + r.projects().size() + " project(s) into their own repositories"
                                        + (r.restored().isEmpty() ? "" : " (" + r.restored().size() + " with their earlier history)"))
                        .files(r.projects()).success());
                return r;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            } finally {
                ConfigAutoCommitter.hold(6_000);
            }
        }
    }

    /** Outcome of one {@link #pullFromBranch} run. */
    /** {@code warn}: a refusal to show on the Logs tab as a warning, once per tip. */
    public record PullResult(boolean changed, String commit, List<String> files, String note, boolean warn) {
        public PullResult(boolean changed, String commit, List<String> files, String note) {
            this(changed, commit, files, note, false);
        }
    }

    /**
     * Combined layout's "Sync — pull" and runner repo updates: fetch the branch and merge it into
     * the gateway repository, then load what changed. Refuses while anything (config or any
     * project) has a real uncommitted change, and does not follow a branch moved backwards —
     * as a project's Pull does in the Separate layout.
     */
    public static PullResult pullFromBranch(String branch) {
        return pullFromBranch(branch, true);
    }

    /** {@code delivery}: an unattended delivery, which "Only deliver commits merged on GitHub" applies to. */
    public static PullResult pullFromBranch(String branch, boolean delivery) {
        GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
        if (remote == null) {
            throw new RuntimeException("No remote is configured to pull from.");
        }
        if (branch == null || branch.isBlank()) {
            throw new RuntimeException("No branch is set to follow.");
        }
        // The fetch only moves the remote-tracking ref, so it runs outside the lock: a slow remote
        // must not hold up config auto-commits and every Designer in Combined.
        try (Git git = GitManager.getGit(dataDir())) {
            FetchCommand fetch = git.fetch().setRemote(ORIGIN).setRemoveDeletedRefs(true)
                    .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":" + trackingRef(branch)));
            authOrigin(git, fetch, remote);
            fetchBranch(fetch, branch);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
        synchronized (DATA_DIR_LOCK) {
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                String busy = midOperation(repo);
                if (busy != null) {
                    return new PullResult(false, "", List.of(), busy.substring(0, 1).toUpperCase()
                            + busy.substring(1) + " Not pulling.");
                }
                Status status = git.status().call();
                Set<String> edits = IgnitionReformat.realChanges(repo, status);
                if (!edits.isEmpty()) {
                    return new PullResult(false, "", new ArrayList<>(edits), "local changes present ("
                            + edits.size() + " uncommitted: " + String.join(", ", edits.stream().sorted().limit(10).toList())
                            + (edits.size() > 10 ? ", …" : "") + "); not pulling");
                }
                Set<String> reformatted = IgnitionReformat.reformatted(repo, status);
                if (!reformatted.isEmpty()) {
                    git.checkout().addPaths(new ArrayList<>(reformatted)).call();
                }

                ObjectId tip = repo.resolve(trackingRef(branch));
                if (tip == null) {
                    throw new RuntimeException(PullGuard.missingBranch(branch, ORIGIN));
                }
                String layout = LayoutCheck.problem(repo, tip, repo.resolve(Constants.HEAD), ORIGIN + "/" + branch,
                        GitManager.combined());
                if (layout != null) {
                    throw new RuntimeException(layout);
                }
                ObjectId before = repo.resolve(Constants.HEAD);
                if (delivery && !tip.equals(before)) {
                    String notMerged = GitHubMerged.refusal(repo, tip, remote.getUri());
                    if (notMerged != null) {
                        return new PullResult(false, "", List.of(), notMerged,
                                GitHubMerged.firstRefusal("config", tip));
                    }
                }
                ConfigAutoCommitter.hold(30_000);
                String switchedFrom = null;
                try {
                    if (!branch.equals(repo.getBranch())) {
                        switchedFrom = repo.getBranch();
                        boolean exists = repo.findRef("refs/heads/" + branch) != null;
                        git.checkout().setName(branch).setCreateBranch(!exists)
                                .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                                .setStartPoint(exists ? null : ORIGIN + "/" + branch).call();
                    }
                    ObjectId local = repo.resolve(Constants.HEAD);
                    if (!tip.equals(local)) {
                        try (RevWalk walk = new RevWalk(repo)) {
                            if (local != null && walk.isMergedInto(walk.parseCommit(tip), walk.parseCommit(local))) {
                                return new PullResult(false, "", List.of(), "remote branch is behind this"
                                        + " gateway; not applied (Sync — replace follows it)");
                            }
                        }
                        Set<String> untracked = new HashSet<>(git.status().call().getUntracked());
                        var merge = git.merge().include(tip).setMessage(PolicyGate.trailer("Merge " + ORIGIN + "/" + branch)).call();
                        if (!merge.getMergeStatus().isSuccessful()) {
                            // A merge that failed outright wrote nothing; only a conflict left files to undo.
                            if (merge.getMergeStatus() == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING) {
                                ProjectScope.abortMerge(git, tip, untracked);
                            }
                            // The checkout above already happened; say so rather than "nothing changed".
                            throw new RuntimeException("Pull failed: merge " + merge.getMergeStatus() + (switchedFrom == null
                                    ? "; nothing was changed."
                                    : "; the merge was undone, but the gateway is now on '" + branch
                                            + "' (it was on '" + switchedFrom + "')."));
                        }
                    }
                    ObjectId after = repo.resolve(Constants.HEAD);
                    if (after == null || after.equals(before)) {
                        return new PullResult(false, "", List.of(), "up to date");
                    }
                    List<String> files = changedBetween(repo, before, after);
                    applyConfigToRunningGateway();
                    applyProjects(files);
                    return new PullResult(true, after.getName(), files, "");
                } finally {
                    ConfigAutoCommitter.hold(6_000);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Gateway pull failed", e);
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
    }

    private static List<String> changedBetween(Repository repo, ObjectId from, ObjectId to) {
        List<String> out = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo);
             org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader();
             Git git = new Git(repo)) {
            org.eclipse.jgit.treewalk.CanonicalTreeParser oldTree = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
            if (from != null) {
                oldTree.reset(reader, walk.parseCommit(from).getTree());
            }
            org.eclipse.jgit.treewalk.CanonicalTreeParser newTree = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
            newTree.reset(reader, walk.parseCommit(to).getTree());
            for (var e : git.diff().setOldTree(from == null ? new org.eclipse.jgit.treewalk.EmptyTreeIterator() : oldTree)
                    .setNewTree(newTree).call()) {
                out.add(e.getChangeType() == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE
                        ? e.getOldPath() : e.getNewPath());
            }
        } catch (Exception e) {
            logger.debug("Could not list files changed by the branch follow", e);
        }
        return out;
    }

    /**
     * Restore config to exactly the state of {@code commitHash} and apply it to the running gateway.
     * Uses {@link GitManager#restoreTree} (keeps HEAD on the branch) then makes a forward
     * "Restore config to &lt;shortHash&gt;" commit, then triggers a config scan so the gateway picks
     * up the on-disk changes without a restart. No-op commit is skipped when nothing changed.
     */
    public static void restoreToCommit(String commitHash, String actingUser) {
        synchronized (DATA_DIR_LOCK) {
            refuseMidOperation();
            String shortHash;
            try (Git git = GitManager.getGit(dataDir())) {
                ObjectId id = git.getRepository().resolve(commitHash);
                if (id == null) {
                    throw new RuntimeException("Commit not found: " + commitHash);
                }
                shortHash = id.abbreviate(7).name();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (GitManager.combined()) {
                restoreOutsideProjects(commitHash);
            } else {
                GitManager.restoreTree(dataDir(), commitHash);
            }

            try (Git git = GitManager.getGit(dataDir())) {
                Status s = git.status().call();
                boolean staged = !s.getAdded().isEmpty() || !s.getChanged().isEmpty() || !s.getRemoved().isEmpty();
                if (!staged) {
                    logger.infof("Restore to %s produced no changes; skipping commit.", shortHash);
                    return;
                }
                CommitCommand commit = git.commit().setMessage(PolicyGate.trailer("Restore config to " + shortHash));
                commit.setAuthor(actingUser, GitManager.resolveUserEmail(actingUser));
                commit.call();
            } catch (Exception e) {
                logger.error("Error committing restore to " + commitHash, e);
                throw new RuntimeException(e);
            }
        }
        applyConfigToRunningGateway();
    }

    /**
     * {@link GitManager#restoreTree} for the Combined layout: everything but {@code projects/}, which
     * the Designer owns. Restoring the whole tree would throw away every project's uncommitted
     * work and delete projects created since that commit.
     */
    private static void restoreOutsideProjects(String commitHash) {
        try (Git git = GitManager.getGit(dataDir())) {
            Repository repo = git.getRepository();
            ObjectId targetId = repo.resolve(commitHash);
            ObjectId headId = repo.resolve(Constants.HEAD);
            if (targetId == null || headId == null) {
                throw new RuntimeException("Commit not found: " + commitHash);
            }
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit target = walk.parseCommit(targetId);
                for (String path : changedBetween(repo, targetId, headId)) {
                    if (isConfigPath(path) && TreeWalk.forPath(repo, path, target.getTree()) == null) {
                        git.rm().addFilepattern(path).call();
                    }
                }
                var checkout = git.checkout().setStartPoint(target);
                boolean any = false;
                try (TreeWalk tw = new TreeWalk(repo)) {
                    tw.addTree(target.getTree());
                    tw.setRecursive(false);
                    while (tw.next()) {
                        if (isConfigPath(tw.getPathString())) {
                            checkout.addPath(tw.getPathString());
                            any = true;
                        }
                    }
                }
                if (any) {
                    checkout.call();
                }
                // File by file, never a directory clean: see restoreTree.
                java.util.Set<String> strays = new java.util.HashSet<>();
                for (String p : git.status().call().getUntracked()) {
                    if (isConfigPath(p)) {
                        strays.add(p);
                    }
                }
                if (!strays.isEmpty()) {
                    git.clean().setPaths(strays).call();
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error restoring config to " + commitHash, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Load changed project files into the running gateway (Combined: a pull, branch follow or
     * checkout of the gateway repository can change any project). Ignition does not notice files
     * changing under {@code projects/} on its own.
     *
     * @param changedPaths repo-relative paths, or null for every project
     */
    public static void applyProjects(List<String> changedPaths) {
        try {
            java.util.Set<String> names = new java.util.TreeSet<>();
            if (changedPaths == null) {
                names.addAll(getContext().getProjectManager().getNames());
            } else {
                for (String p : changedPaths) {
                    if (p.startsWith("projects/") && p.indexOf('/', 9) > 9) {
                        names.add(p.substring(9, p.indexOf('/', 9)));
                    }
                }
            }
            for (String name : names) {
                if (getContext().getProjectManager().getNames().contains(name)
                        && Files.isRegularFile(GitManager.getProjectFolderPath(name).resolve("project.json"))) {
                    GitProjectManager.importProject(name);
                }
            }
            getContext().getProjectManager().requestScan();
        } catch (Exception e) {
            logger.warn("Could not load the changed projects into the gateway", e);
        }
    }

    /** Apply on-disk config to the running gateway in-process (no restart). */
    public static void applyConfigToRunningGateway() {
        try {
            getContext().getConfigurationManager().requestScan().join();
        } catch (Exception e) {
            logger.error("Error requesting config scan after restore", e);
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // .gitignore management (the Versioning page's Excluded-files tree)
    // ------------------------------------------------------------------

    /**
     * Everything the page appends lives below this marker, so the template above it — and any
     * hand-written rule a user added — is never rewritten, reordered or lost.
     */

    /** How many entries a folder's include/exclude roll-up will visit before giving up. */
    private static final int ROLLUP_BUDGET = 2000;

    /**
     * One row of the exclusion tree.
     *
     * @param name      the entry's own name
     * @param path      repo-relative path, {@code /}-separated, no trailing slash
     * @param directory whether it is a folder
     * @param excluded  whether git currently ignores it
     * @param tracked   whether it is in the index — a tracked file stays tracked whatever
     *                  {@code .gitignore} says, which is why the two are reported separately
     * @param rule      the {@code .gitignore} line that decided it, or null if nothing matched
     * @param ownRule   true when {@code rule} is this exact path's own line (so unticking can
     *                  delete the line); false when an inherited glob decided it, and the row
     *                  must be read-only because unticking one path cannot undo a glob
     * @param childState for folders: INCLUDED, EXCLUDED, MIXED or UNKNOWN (roll-up budget spent)
     * @param reincludable whether ticking this row can actually re-include it. Git will not
     *                     re-include anything whose PARENT DIRECTORY is excluded, so a negation
     *                     under an excluded folder is silently ineffective — the row must be
     *                     read-only rather than offering an edit that does nothing
     */
    public record TreeEntry(String name, String path, boolean directory, boolean excluded,
                            boolean tracked, String rule, boolean ownRule, String childState,
                            boolean reincludable) {}

    private static List<FastIgnoreRule> ignoreRules() throws IOException {
        return IgnoreRules.parse(dataDir().resolve(".gitignore"));
    }

    private static FastIgnoreRule matchRule(List<FastIgnoreRule> rules, String path, boolean isDir) {
        return IgnoreRules.matchRule(rules, path, isDir);
    }

    private static IgnoreRules.Decision decide(List<FastIgnoreRule> rules, String path, boolean isDir) {
        return IgnoreRules.decide(rules, path, isDir);
    }

    private static boolean isOwnLine(FastIgnoreRule rule, String path, boolean isDir) {
        return IgnoreRules.isOwnLine(rule, path, isDir);
    }

    /** Is this path in the index? Tracked files ignore {@code .gitignore} entirely. */
    private static boolean isTracked(Repository repo, String path, boolean isDir) throws IOException {
        DirCache cache = repo.readDirCache();
        if (!isDir) {
            return cache.findEntry(path) >= 0;
        }
        String prefix = path + "/";
        for (int i = 0; i < cache.getEntryCount(); i++) {
            if (cache.getEntry(i).getPathString().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * List one directory level of the data dir, with each entry's exclusion state. Lazy by design:
     * a data directory carries history, logs and caches, and eagerly walking it would be both slow
     * and pointless — the large directories are exactly the excluded ones.
     *
     * @param relPath repo-relative directory, or "" / null for the data-dir root
     */
    public static List<TreeEntry> listTree(String relPath) {
        synchronized (DATA_DIR_LOCK) {
            String base = normalize(relPath);
            Path dir = base.isEmpty() ? dataDir() : dataDir().resolve(base);
            if (!dir.normalize().startsWith(dataDir().normalize())) {
                throw new RuntimeException("Path escapes the data directory: " + relPath);
            }
            if (!Files.isDirectory(dir)) {
                throw new RuntimeException("Not a directory: " + relPath);
            }
            List<TreeEntry> out = new ArrayList<>();
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                List<FastIgnoreRule> rules = ignoreRules();
                try (var stream = Files.list(dir)) {
                    List<Path> entries = stream.sorted(DIR_FIRST).toList();
                    for (Path p : entries) {
                        String name = p.getFileName().toString();
                        if (name.equals(".git")) {
                            continue; // the repo's own plumbing is not a config choice
                        }
                        boolean isDir = Files.isDirectory(p);
                        String path = base.isEmpty() ? name : base + "/" + name;
                        IgnoreRules.Decision d = decide(rules, path, isDir);
                        boolean excluded = d.excluded();
                        FastIgnoreRule own = matchRule(rules, path, isDir);
                        boolean ownRule = own != null && own == d.rule() && isOwnLine(own, path, isDir);
                        out.add(new TreeEntry(name, path, isDir, excluded,
                                isTracked(repo, path, isDir),
                                d.rule() == null ? null : d.rule().toString(), ownRule,
                                isDir ? rollUp(p, path, rules, excluded) : null,
                                !d.fromAncestor()));
                    }
                }
            } catch (Exception e) {
                logger.error("Error listing config tree at '" + relPath + "'", e);
                throw new RuntimeException(e);
            }
            return out;
        }
    }

    /**
     * How many entries a search may look at. A data directory holds logs, caches and tag history,
     * so an unbounded walk would hang the page on exactly the gateways that need this most.
     */
    private static final int SEARCH_BUDGET = 40000;

    /** How many matches a search returns before it stops looking. */
    private static final int SEARCH_LIMIT = 200;

    /** Search results, and whether the walk gave up before it finished. */
    public record SearchResult(List<TreeEntry> entries, boolean truncated) {}

    /**
     * Find every entry whose name contains {@code query}, anywhere under the data directory.
     * Breadth-first, so a match near the root is found before the walk spends its budget deep in
     * {@code logs/}. Both budgets report as {@code truncated} rather than silently returning a
     * partial list that looks complete.
     */
    public static SearchResult searchTree(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            return new SearchResult(List.of(), false);
        }
        synchronized (DATA_DIR_LOCK) {
            List<TreeEntry> out = new ArrayList<>();
            boolean truncated = false;
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                List<FastIgnoreRule> rules = ignoreRules();
                java.util.Deque<String> queue = new java.util.ArrayDeque<>();
                queue.add("");
                int seen = 0;
                while (!queue.isEmpty()) {
                    String base = queue.poll();
                    Path dir = base.isEmpty() ? dataDir() : dataDir().resolve(base);
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (var stream = Files.list(dir)) {
                        for (Path p : stream.sorted(DIR_FIRST).toList()) {
                            String name = p.getFileName().toString();
                            if (name.equals(".git")) {
                                continue;
                            }
                            if (++seen > SEARCH_BUDGET) {
                                truncated = true;
                                queue.clear();
                                break;
                            }
                            boolean isDir = Files.isDirectory(p);
                            String path = base.isEmpty() ? name : base + "/" + name;
                            if (isDir) {
                                queue.add(path);
                            }
                            if (!name.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                                continue;
                            }
                            if (out.size() >= SEARCH_LIMIT) {
                                truncated = true;
                                queue.clear();
                                break;
                            }
                            IgnoreRules.Decision d = decide(rules, path, isDir);
                            FastIgnoreRule own = matchRule(rules, path, isDir);
                            out.add(new TreeEntry(name, path, isDir, d.excluded(),
                                    isTracked(repo, path, isDir),
                                    d.rule() == null ? null : d.rule().toString(),
                                    own != null && own == d.rule() && isOwnLine(own, path, isDir),
                                    // The roll-up is a per-directory walk of its own; a flat result
                                    // list has no folder tick to roll up into.
                                    isDir ? "UNKNOWN" : null,
                                    !d.fromAncestor()));
                        }
                    } catch (IOException e) {
                        logger.debug("Skipped an unreadable folder while searching: " + base, e);
                    }
                }
            } catch (Exception e) {
                logger.error("Error searching the config tree for '" + query + "'", e);
                throw new RuntimeException(e);
            }
            return new SearchResult(out, truncated);
        }
    }

    /** Folders before files, then case-insensitive by name — the order a file browser uses. */
    private static final java.util.Comparator<Path> DIR_FIRST =
            java.util.Comparator.<Path, Boolean>comparing(p -> !Files.isDirectory(p))
                    .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);

    /**
     * Roll a folder's children up to INCLUDED / EXCLUDED / MIXED so a collapsed folder can show a
     * partial tick. Recursion stops at excluded directories — everything under one is excluded, and
     * those are the directories with a hundred thousand files in them.
     */
    private static String rollUp(Path dir, String path, List<FastIgnoreRule> rules, boolean selfExcluded) {
        if (selfExcluded) {
            return "EXCLUDED";
        }
        int[] budget = { ROLLUP_BUDGET };
        boolean[] seen = { false, false }; // included, excluded
        boolean complete = walkRollUp(dir, path, rules, budget, seen);
        if (!complete) {
            return "UNKNOWN";
        }
        if (seen[0] && seen[1]) {
            return "MIXED";
        }
        return seen[1] ? "EXCLUDED" : "INCLUDED";
    }

    private static boolean walkRollUp(Path dir, String path, List<FastIgnoreRule> rules,
                                      int[] budget, boolean[] seen) {
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (budget[0]-- <= 0) {
                    return false;
                }
                String name = p.getFileName().toString();
                if (name.equals(".git")) {
                    continue;
                }
                boolean isDir = Files.isDirectory(p);
                String child = path + "/" + name;
                boolean excluded = decide(rules, child, isDir).excluded();
                seen[excluded ? 1 : 0] = true;
                if (isDir && !excluded && !walkRollUp(p, child, rules, budget, seen)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The raw {@code .gitignore}, for the page's source view. Empty string when there is none. */
    public static String readIgnoreFile() {
        synchronized (DATA_DIR_LOCK) {
            try {
                Path gitignore = dataDir().resolve(".gitignore");
                return Files.exists(gitignore)
                        ? Files.readString(gitignore, StandardCharsets.UTF_8) : "";
            } catch (IOException e) {
                logger.error("Error reading .gitignore", e);
                throw new RuntimeException(e);
            }
        }
    }

    /** Replace {@code .gitignore} wholesale — the source view's Save. */
    public static void writeIgnoreFile(String text) {
        synchronized (DATA_DIR_LOCK) {
            try {
                // Whatever was written, the deny block ends the file: no edit can re-include a key.
                String body = IgnoreRules.withDenyBlock(text);
                Files.write(dataDir().resolve(".gitignore"), body.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logger.error("Error writing .gitignore", e);
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Apply tick/untick edits from the tree.
     *
     * <p>Excluding appends an anchored literal line. Including removes that line if the path owns
     * one, and otherwise appends a negation — never deleting the glob, because deleting
     * {@code **}{@code /logs} to recover one file is how a gateway starts versioning a gigabyte of
     * logs. Lines are added at the end of the managed block; a line outside it is removed only when
     * it names exactly the path being ticked (otherwise the tick could not take effect), never a glob.
     *
     * <p>Excluding a TRACKED path also removes it from the index. A {@code .gitignore} line has no
     * effect on a file git is already tracking, so without this the page would report an exclusion
     * that had not happened.
     *
     * @return the number of paths whose index entry was dropped
     */
    public static int applyIgnoreEdits(List<String> exclude, List<String> include) {
        synchronized (DATA_DIR_LOCK) {
            int untracked = 0;
            try (Git git = GitManager.getGit(dataDir())) {
                Repository repo = git.getRepository();
                IgnoreRules.Parts parts = IgnoreRules.split(readIgnoreFile());
                List<String> managed = parts.managed();
                List<List<String>> all = List.of(parts.before(), managed, parts.after());

                for (String raw : include == null ? List.<String>of() : include) {
                    String path = normalize(raw);
                    if (path.isEmpty()) {
                        continue;
                    }
                    boolean isDir = Files.isDirectory(dataDir().resolve(path));
                    boolean removed = false;
                    for (List<String> lines : all) {
                        removed |= lines.removeIf(l -> isLiteralFor(l, path, isDir));
                    }
                    if (!removed) {
                        // An inherited glob excluded it — negate rather than weaken the glob.
                        managed.add("!/" + path + (isDir ? "/" : ""));
                        if (isDir) {
                            // A rule like `**/certificates/*` excludes the CONTENTS, not the
                            // folder, so negating the folder alone re-includes nothing visible.
                            // Negate the contents too. Safe because the caller only offers the
                            // tick when no ancestor directory is excluded (see `reincludable`),
                            // which is the one case git refuses to re-include.
                            managed.add("!/" + path + "/**");
                        }
                    }
                }

                for (String raw : exclude == null ? List.<String>of() : exclude) {
                    String path = normalize(raw);
                    if (path.isEmpty()) {
                        continue;
                    }
                    boolean isDir = Files.isDirectory(dataDir().resolve(path));
                    // A stale negation would beat the new exclusion, so clear it first.
                    for (List<String> lines : all) {
                        lines.removeIf(l -> isNegationFor(l, path, isDir)
                                || (isDir && l.trim().equals("!/" + path + "/**")));
                    }
                    String literal = "/" + path + (isDir ? "/" : "");
                    if (all.stream().flatMap(List::stream).noneMatch(l -> l.trim().equals(literal))) {
                        managed.add(literal);
                    }
                    if (isTracked(repo, path, isDir)) {
                        git.rm().setCached(true).addFilepattern(path).call();
                        untracked++;
                    }
                }

                writeIgnoreFile(IgnoreRules.join(parts));
            } catch (Exception e) {
                logger.error("Error applying .gitignore edits", e);
                throw new RuntimeException(e);
            }
            return untracked;
        }
    }

    private static boolean isLiteralFor(String line, String path, boolean isDir) {
        String t = line.trim();
        return t.equals("/" + path) || t.equals(path)
                || (isDir && (t.equals("/" + path + "/") || t.equals(path + "/")));
    }

    private static boolean isNegationFor(String line, String path, boolean isDir) {
        String t = line.trim();
        return t.equals("!/" + path) || t.equals("!" + path)
                || (isDir && (t.equals("!/" + path + "/") || t.equals("!" + path + "/")));
    }

    /** Trim slashes and reject traversal, so a path parameter can never leave the data dir. */
    private static String normalize(String relPath) {
        if (relPath == null) {
            return "";
        }
        String p = relPath.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.equals("..") || p.startsWith("../") || p.contains("/../") || p.endsWith("/..")) {
            throw new RuntimeException("Invalid path: " + relPath);
        }
        return p;
    }
}
