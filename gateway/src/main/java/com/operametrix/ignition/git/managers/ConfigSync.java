package com.operametrix.ignition.git.managers;

import com.operametrix.ignition.git.automation.GitAudit;
import com.operametrix.ignition.git.automation.GitEvent;
import com.operametrix.ignition.git.automation.GitEvents;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the gateway config repo follow a remote branch ("Sync — replace"), the way a project's
 * Delivery does. The settings live in the repository's own {@code .git/config}, not in a gateway
 * resource: a resource sits under {@code config/} and is versioned, so saving it would be an
 * auto-commit on top of the branch being followed, and the branch's copy of the resource would
 * overwrite the setting on the next replace.
 */
public final class ConfigSync {

    private static final Logger logger = LoggerFactory.getLogger(ConfigSync.class);

    public static final int MIN_INTERVAL_SECONDS = 30;
    public static final int DEFAULT_INTERVAL_SECONDS = 300;
    public static final String MODE_OFF = "off";
    public static final String MODE_REPLACE = "replace";
    /** Combined layout only: merge the branch in, refusing over local changes. */
    public static final String MODE_PULL = "pull";
    /** Combined layout only: pull when a runner calls {@code /runner-sync}, no timer. */
    public static final String MODE_RUNNER = "runner";

    private static final String SECTION = "gitmodule";
    private static final String SUBSECTION = "configsync";

    /**
     * The gateway repository's delivery. Pull and runner are kept but inactive in the Separate
     * layout, where each project has its own delivery.
     */
    public record Settings(String mode, String branch, int intervalSeconds) {
        /** Whether the timer runs it. */
        public boolean enabled() {
            return !branch.isBlank() && (MODE_REPLACE.equals(mode)
                    || (MODE_PULL.equals(mode) && GitManager.combined()));
        }

        /** Whether a runner's repo-update call may run it. */
        public boolean runner() {
            return MODE_RUNNER.equals(mode) && !branch.isBlank() && GitManager.combined();
        }
    }

    /** Last run's outcome; in memory, so it resets on restart. */
    public record LastRun(long time, boolean ok, String message, String commit) {}

    private static volatile LastRun lastRun;
    private static volatile long lastAttempt;
    private static final AtomicBoolean running = new AtomicBoolean();

    private ConfigSync() {
    }

    public static LastRun lastRun() {
        return lastRun;
    }

    private static FileBasedConfig config() {
        return new FileBasedConfig(DataDirGitManager.dataDir().resolve(".git/config").toFile(), FS.DETECTED);
    }

    public static Settings get() {
        if (!DataDirGitManager.isInitialized()) {
            return new Settings(MODE_OFF, "", DEFAULT_INTERVAL_SECONDS);
        }
        try {
            FileBasedConfig c = config();
            c.load();
            String mode = c.getString(SECTION, SUBSECTION, "mode");
            String branch = c.getString(SECTION, SUBSECTION, "branch");
            int interval = c.getInt(SECTION, SUBSECTION, "interval", DEFAULT_INTERVAL_SECONDS);
            boolean known = MODE_REPLACE.equals(mode) || MODE_PULL.equals(mode) || MODE_RUNNER.equals(mode);
            return new Settings(known ? mode : MODE_OFF,
                    branch == null ? "" : branch, Math.max(MIN_INTERVAL_SECONDS, interval));
        } catch (Exception e) {
            logger.warn("Could not read the config branch-follow settings.", e);
            return new Settings(MODE_OFF, "", DEFAULT_INTERVAL_SECONDS);
        }
    }

    public static void save(String mode, String branch, int intervalSeconds) {
        if (!DataDirGitManager.isInitialized()) {
            throw new RuntimeException("Config versioning is not initialised.");
        }
        String b = branch == null ? "" : branch.trim();
        boolean follows = MODE_REPLACE.equals(mode) || MODE_PULL.equals(mode) || MODE_RUNNER.equals(mode);
        if (follows && b.isEmpty()) {
            throw new RuntimeException("A branch to follow is required.");
        }
        if ((MODE_PULL.equals(mode) || MODE_RUNNER.equals(mode)) && !GitManager.combined()) {
            throw new RuntimeException("Pull and runner delivery for the whole gateway need the Combined"
                    + " layout; in Separate each project has its own delivery on the Projects tab.");
        }
        try {
            StoredConfig c = config();
            c.load();
            c.setString(SECTION, SUBSECTION, "mode", follows ? mode : MODE_OFF);
            c.setString(SECTION, SUBSECTION, "branch", b);
            c.setInt(SECTION, SUBSECTION, "interval", Math.max(MIN_INTERVAL_SECONDS, intervalSeconds));
            c.save();
        } catch (IOException | org.eclipse.jgit.errors.ConfigInvalidException e) {
            throw new RuntimeException(e);
        }
        lastAttempt = 0;
    }

    /** Called from the shared sync timer. */
    public static void tick(long nowSeconds) {
        if (!DataDirGitManager.isInitialized()) {
            return;
        }
        Settings s = get();
        if (!s.enabled() || nowSeconds - lastAttempt < s.intervalSeconds()) {
            return;
        }
        lastAttempt = nowSeconds;
        syncNow();
    }

    /** One run; safe to call from the Sync now button as well as the timer. */
    public static String syncNow() {
        Settings s = get();
        if (!s.enabled() && !s.runner()) {
            throw new RuntimeException("Branch follow is off.");
        }
        if (!running.compareAndSet(false, true)) {
            return "already running";
        }
        try {
            PolicyGate.checkDelivery(s.mode());
            if (!MODE_REPLACE.equals(s.mode())) {
                return pull(s);
            }
            DataDirGitManager.ReplaceResult r = DataDirGitManager.replaceFromBranch(s.branch());
            if (r.refused() != null) {
                if (r.firstRefusal()) {
                    logger.warn("Config branch follow: {}", r.refused());
                    GitEvents.fire(GitEvent.of(GitEvent.SYNC).config().branch(s.branch()).warning(r.refused()));
                }
                lastRun = new LastRun(System.currentTimeMillis(), false, r.refused(), "");
                return r.refused();
            }
            if (!r.changed()) {
                return "up to date";
            }
            String message = (r.rolledBack() ? "Rolled back. " : "") + "Replaced config with " + r.commit().substring(0, 7) + " from "
                    + s.branch() + ": " + r.files().size() + " changed file(s), discarded "
                    + r.discardedCommits() + " local commit(s) and " + r.discardedChanges()
                    + " uncommitted change(s)";
            GitEvents.fire(GitEvent.of(GitEvent.SYNC).config().branch(s.branch())
                    .commit(r.commit()).message(message).files(r.files()).success());
            GitAudit.record(GitAudit.DELIVERY, "gateway", null, s.branch(), r.commit(),
                    "Sync — replace, " + r.files().size() + " file(s)" + (r.rolledBack() ? ", rolled back" : ""));
            lastRun = new LastRun(System.currentTimeMillis(), true, message, r.commit());
            return message;
        } catch (Exception e) {
            String reason = GitEvents.reason(e);
            logger.warn("Config branch follow failed.", e);
            GitEvents.fire(GitEvent.of(GitEvent.SYNC).config().branch(s.branch()).failure(reason));
            lastRun = new LastRun(System.currentTimeMillis(), false, reason, "");
            return "failed: " + reason;
        } finally {
            running.set(false);
        }
    }

    private static String pull(Settings s) {
        try {
            DataDirGitManager.PullResult r = DataDirGitManager.pullFromBranch(s.branch());
            if (!r.changed()) {
                if (r.warn()) {
                    logger.warn("Gateway pull: {}", r.note());
                    GitEvents.fire(GitEvent.of(GitEvent.SYNC).config().branch(s.branch()).warning(r.note()));
                    lastRun = new LastRun(System.currentTimeMillis(), false, r.note(), "");
                } else if (r.note().startsWith("Not merged through GitHub")) {
                    lastRun = new LastRun(System.currentTimeMillis(), false, r.note(), "");
                } else if (!r.note().equals("up to date")) {
                    GitEvents.fire(GitEvent.of(GitEvent.SYNC).config().branch(s.branch()).failure(r.note()));
                    lastRun = new LastRun(System.currentTimeMillis(), false, r.note(), "");
                }
                return r.note();
            }
            String message = "Pulled " + r.commit().substring(0, 7) + " from " + s.branch() + ": "
                    + r.files().size() + " changed file(s)";
            GitEvents.fire(GitEvent.of(GitEvent.SYNC).config().branch(s.branch())
                    .commit(r.commit()).message(message).files(r.files()).success());
            GitAudit.record(GitAudit.DELIVERY, s.runner() ? "runner" : "gateway", null, s.branch(), r.commit(),
                    (s.runner() ? "Runner — repo update" : "Sync — pull") + ", " + r.files().size() + " file(s)");
            lastRun = new LastRun(System.currentTimeMillis(), true, message, r.commit());
            return message;
        } catch (Exception e) {
            String reason = GitEvents.reason(e);
            logger.warn("Gateway pull failed.", e);
            GitEvents.fire(GitEvent.of(GitEvent.SYNC).config().branch(s.branch()).failure(reason));
            lastRun = new LastRun(System.currentTimeMillis(), false, reason, "");
            return "failed: " + reason;
        }
    }
}
