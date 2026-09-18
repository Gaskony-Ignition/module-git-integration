package com.operametrix.ignition.git.automation;

import com.operametrix.ignition.git.GatewayHook;
import com.operametrix.ignition.git.managers.GitManager;
import com.operametrix.ignition.git.managers.GitProjectManager;
import com.operametrix.ignition.git.records.GitSyncRecord;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Inbound sync: fetch each configured project repository on a timer and, when the tracked branch
 * has moved, either fast-forward the project (Pull) or make it match the branch exactly (Replace).
 *
 * <p>This is the default inbound mechanism rather than a GitHub webhook because a webhook needs
 * GitHub to open a connection <em>to</em> the gateway, which is not possible on most OT networks
 * — see {@code docs/AUTOMATION.md}. Polling reaches the same state with no inbound exposure and
 * works against any remote, not only GitHub.
 */
public final class SyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SyncScheduler.class);

    /** How often the scheduler wakes to see what is due; per-repository intervals are longer. */
    private static final int TICK_SECONDS = 15;

    private static ScheduledExecutorService executor;

    /** Repositories with a sync in flight — one pull per repository at a time. */
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Project name to epoch-second of its last attempt. */
    private static final Map<String, Long> lastRun = new ConcurrentHashMap<>();

    private SyncScheduler() {
    }

    public static synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-sync");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(SyncScheduler::tick, TICK_SECONDS, TICK_SECONDS,
                TimeUnit.SECONDS);
        logger.debug("Git sync scheduler started.");
    }

    public static synchronized void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
        inFlight.clear();
        lastRun.clear();
    }

    private static void tick() {
        try {
            long now = System.currentTimeMillis() / 1000L;
            for (GitSyncRecord cfg : GitSyncRecord.listEnabled()) {
                Long last = lastRun.get(cfg.getProject());
                if (last != null && now - last < cfg.getIntervalSeconds()) {
                    continue;
                }
                lastRun.put(cfg.getProject(), now);
                syncNow(cfg);
            }
        } catch (Throwable t) {
            // The scheduler must survive a bad configuration; scheduleWithFixedDelay cancels the
            // task permanently if this ever propagates.
            logger.error("Git sync tick failed.", t);
        }
    }

    /**
     * Claims a project for a whole-project operation. A release replacing the folder while a sync
     * is pulling into it would leave neither intact, so both go through the same set.
     */
    public static boolean acquire(String project) {
        return inFlight.add(project);
    }

    public static void release(String project) {
        inFlight.remove(project);
    }

    /** Runs one repository's sync. Safe to call outside the timer (the Sync now button). */
    public static String syncNow(GitSyncRecord cfg) {
        String project = cfg.getProject();
        if (!inFlight.add(project)) {
            return "already running";
        }
        try {
            return run(cfg);
        } catch (Exception e) {
            logger.warn("Git sync failed for project '{}'.", project, e);
            GitEvents.fire(GitEvent.of(GitEvent.SYNC)
                    .project(project)
                    .user(cfg.getIgnitionUser())
                    .remote(cfg.getRemoteName())
                    .failure(GitEvents.reason(e)));
            return "failed: " + GitEvents.reason(e);
        } finally {
            inFlight.remove(project);
        }
    }

    private static String run(GitSyncRecord cfg) throws Exception {
        String project = cfg.getProject();
        String user = cfg.getIgnitionUser();
        String remoteName = cfg.getRemoteName();

        Path dir = GitManager.getProjectFolderPath(project);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            throw new IllegalStateException("project is not under version control");
        }

        try (Git git = Git.open(dir.toFile())) {
            Repository repo = git.getRepository();

            // An unborn repository needs a clone, not a merge. Sync is unattended, so it reports
            // the condition rather than deciding to materialise a whole project by itself.
            if (repo.resolve("HEAD") == null) {
                throw new IllegalStateException(
                        "repository has no commits yet — run Pull once from the Designer to finish it");
            }

            String current = repo.getBranch();
            String branch = cfg.getBranch().isBlank() ? current : cfg.getBranch();
            boolean replace = cfg.isReplace();

            // Someone has a Designer open with unsaved work. Pull mode refuses: silently
            // discarding or stashing an engineer's changes is worse than not syncing. Replace mode
            // was chosen to be authoritative, like a release, so it goes ahead and says so.
            if (!replace) {
                Status status = git.status().call();
                if (!status.isClean()) {
                    String note = "local changes present (" + status.getUncommittedChanges().size()
                            + " uncommitted); not pulling";
                    GitEvents.fire(GitEvent.of(GitEvent.SYNC).project(project).user(user)
                            .branch(branch).remote(remoteName).failure(note));
                    return note;
                }
            }

            FetchCommand fetch = git.fetch().setRemote(remoteName);
            GitManager.setAuthentication(fetch, project, user, remoteName);
            fetch.call();

            ObjectId tracked = repo.resolve("refs/remotes/" + remoteName + "/" + branch);
            if (tracked == null) {
                throw new IllegalStateException(
                        "remote '" + remoteName + "' has no branch '" + branch + "'");
            }

            ObjectId before = repo.resolve("HEAD");
            boolean switched = !branch.equals(current);
            String message;

            if (replace) {
                // Compared with HEAD, not the working tree: a replace runs when the branch moves,
                // as a release runs when one is published, not whenever someone edits.
                if (!switched && tracked.equals(before)) {
                    return "up to date";
                }
                message = replace(git, repo, branch, current, remoteName, tracked, before, switched);
            } else {
                // Sync set to follow a branch that is not the one checked out: switch to it.
                // Pulling it into whatever IS checked out merges the wrong branch, and because the
                // local ref never exists the comparison below never matches, so it re-pulled and
                // rescanned the project on every interval. The tree is clean (refused above).
                if (switched) {
                    checkout(git, repo, branch, remoteName);
                }

                ObjectId local = repo.resolve(branch);
                if (!switched && tracked.equals(local)) {
                    return "up to date";
                }

                // The remote branch moved BACKWARDS (a force-push, e.g. a promotion rolled back).
                // A pull changes nothing, and reporting it as a sync would re-import and rescan
                // every interval. Following it is what Replace mode is for.
                if (!switched && local != null && isAncestor(repo, tracked, local)) {
                    return "remote branch is behind this gateway; not applied (Replace mode follows it)";
                }

                if (!tracked.equals(local)) {
                    PullCommand pull = git.pull().setRemote(remoteName).setRemoteBranchName(branch);
                    GitManager.setAuthentication(pull, project, user, remoteName);
                    PullResult result = pull.call();
                    if (!result.isSuccessful()) {
                        MergeResult merge = result.getMergeResult();
                        String reason = merge == null ? "pull rejected" : ("merge " + merge.getMergeStatus());
                        throw new IllegalStateException(reason);
                    }
                }
                message = switched ? "Switched from " + current + " to " + branch + "; pulled" : "Pulled";
            }

            ObjectId after = repo.resolve(branch);
            List<String> changed = changedPaths(repo, before, after);

            // The files under data/projects changed; Ignition does not notice on its own.
            GitProjectManager.importProject(project);
            requestScan();

            GitEvents.fire(GitEvent.of(GitEvent.SYNC)
                    .project(project)
                    .user(user)
                    .branch(branch)
                    .remote(remoteName)
                    .commit(after == null ? "" : after.getName())
                    .message(message + " " + changed.size() + " changed file(s) from "
                            + remoteName + "/" + branch)
                    .files(changed)
                    .success());
            return (replace ? "replaced, " : "pulled ") + changed.size() + " file(s)";
        }
    }

    private static void checkout(Git git, Repository repo, String branch, String remoteName)
            throws Exception {
        boolean exists = repo.findRef("refs/heads/" + branch) != null;
        git.checkout()
                .setName(branch)
                .setCreateBranch(!exists)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .setStartPoint(exists ? null : remoteName + "/" + branch)
                .call();
    }

    /**
     * Makes the project exactly the remote branch, as {@link ReleaseReceiver} does with a zip: files
     * the branch dropped disappear, uncommitted edits are overwritten, and a branch moved backwards
     * is followed. The gateway's own project properties are kept, because they hold per-gateway
     * settings a branch ships neutral. Returns the start of the event message.
     */
    private static String replace(Git git, Repository repo, String branch, String current,
                                  String remoteName, ObjectId tracked, ObjectId before,
                                  boolean switched) throws Exception {
        Path props = repo.getWorkTree().toPath().resolve(ReleaseReceiver.GLOBAL_PROPS);
        byte[] keptProps = Files.isRegularFile(props) ? Files.readAllBytes(props) : null;

        Status status = git.status().call();
        Set<String> overwritten = new TreeSet<>(status.getUncommittedChanges());
        overwritten.addAll(status.getUntracked());
        overwritten.remove(ReleaseReceiver.GLOBAL_PROPS);

        // Clean first so the checkout cannot trip over an edit, then move the branch itself —
        // reset, not merge, so a rollback lands and local commits never block it.
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
        git.clean().setCleanDirectories(true).call();
        if (switched) {
            checkout(git, repo, branch, remoteName);
        }
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(tracked.getName()).call();

        if (keptProps != null) {
            Files.createDirectories(props.getParent());
            Files.write(props, keptProps);
        }

        // The rollback leads: it is the one thing in this message someone reading the log is
        // looking for.
        StringBuilder m = new StringBuilder();
        if (before != null && !switched && isAncestor(repo, tracked, before)) {
            m.append("Rolled back. ");
        }
        if (!overwritten.isEmpty()) {
            m.append("Overwrote ").append(overwritten.size()).append(" uncommitted change(s). ");
        }
        m.append(switched
                ? "Switched from " + current + " to " + branch + "; replaced with" : "Replaced with");
        return m.toString();
    }

    private static void requestScan() {
        try {
            if (GatewayHook.getContext() != null) {
                GatewayHook.getContext().getProjectManager().requestScan();
            }
        } catch (Exception e) {
            logger.warn("Project scan request after a git sync failed.", e);
        }
    }

    private static boolean isAncestor(Repository repo, ObjectId maybeAncestor, ObjectId of) {
        try (RevWalk walk = new RevWalk(repo)) {
            return walk.isMergedInto(walk.parseCommit(maybeAncestor), walk.parseCommit(of));
        } catch (Exception e) {
            return false;
        }
    }

    /** Paths that differ between two commits; empty when either end is unknown. */
    private static List<String> changedPaths(Repository repo, ObjectId from, ObjectId to) {
        List<String> out = new ArrayList<>();
        if (from == null || to == null || from.equals(to)) {
            return out;
        }
        try (RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             Git git = new Git(repo)) {
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            oldTree.reset(reader, walk.parseCommit(from).getTree());
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            newTree.reset(reader, walk.parseCommit(to).getTree());
            for (DiffEntry entry : git.diff().setOldTree(oldTree).setNewTree(newTree).call()) {
                out.add(DiffEntry.ChangeType.DELETE.equals(entry.getChangeType())
                        ? entry.getOldPath() : entry.getNewPath());
            }
        } catch (Exception e) {
            logger.debug("Could not diff {}..{} for the sync event.", from.getName(), to.getName(), e);
        }
        return out;
    }

}
