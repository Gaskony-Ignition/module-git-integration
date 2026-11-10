package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Versioning page's branch switch, free of any gateway context so it is unit tested. It never
 * stashes: a tree with uncommitted work is refused with the file list, so nothing is moved aside
 * for a Designer to find missing.
 */
public final class BranchSwitch {

    private BranchSwitch() {
    }

    /** What counts as an uncommitted change; the gateway passes {@link IgnitionReformat}. */
    public interface Dirty {
        Set<String> real(Repository repo, Status status);

        Set<String> reformatted(Repository repo, Status status);
    }

    public static final String DIRTY = "dirty";
    public static final String FOLLOWED = "followed";
    public static final String MISSING = "missing";

    /**
     * @param refused null when switched; otherwise DIRTY, FOLLOWED or MISSING
     * @param files   the uncommitted paths when DIRTY
     * @param branch  the local branch now checked out (when switched)
     */
    public record Result(String refused, String message, List<String> files, String branch, boolean created) {
        public boolean ok() {
            return refused == null;
        }
    }

    private static Result refuse(String kind, String message, List<String> files) {
        return new Result(kind, message, files, null, false);
    }

    /**
     * @param target        a local branch, or a remote-tracking one ("origin/x") that gets a local
     *                      tracking branch
     * @param followedBranch the branch an enabled sync or delivery pulls for this repository, or
     *                      null; switching to any other would be undone on its next run
     * @param whereToChange where that delivery is set, for the message
     * @param gateway       the repository holds config too (Combined, or the config repository):
     *                      config's changes do not block, and are committed first by
     *                      {@code commitConfig}
     */
    public static Result switchTo(Git git, String target, String followedBranch, String whereToChange,
                                  boolean gateway, Dirty dirty, Runnable commitConfig) throws Exception {
        Repository repo = git.getRepository();
        String local = localName(repo, target);
        boolean exists = repo.exactRef(Constants.R_HEADS + local) != null;
        if (!exists && repo.exactRef(Constants.R_REMOTES + target) == null) {
            return refuse(MISSING, "There is no branch '" + target + "'.", List.of());
        }
        if (local.equals(repo.getBranch())) {
            return new Result(null, "Already on '" + local + "'.", List.of(), local, false);
        }
        if (followedBranch != null && !followedBranch.isBlank() && !followedBranch.equals(local)) {
            return refuse(FOLLOWED, "An enabled delivery follows '" + followedBranch + "' and would move this"
                    + " back to it. Change or turn it off in " + whereToChange + " first.", List.of());
        }

        // Config's own pending changes are committed, not refused: it auto-commits anyway.
        if (gateway && commitConfig != null) {
            commitConfig.run();
        }
        Status status = git.status().call();
        Set<String> edits = new TreeSet<>(dirty.real(repo, status));
        if (gateway) {
            edits.removeIf(p -> p.startsWith("config/"));
        }
        if (!edits.isEmpty()) {
            return refuse(DIRTY, "Uncommitted changes would be lost or carried across. Commit or discard them in"
                    + " the Designer first.", new ArrayList<>(edits));
        }
        // Ignition's rewrite of files it imported is not an edit, but it would block the checkout.
        Set<String> rewritten = dirty.reformatted(repo, status);
        if (!rewritten.isEmpty()) {
            git.checkout().addPaths(new ArrayList<>(rewritten)).call();
        }

        if (exists) {
            git.checkout().setName(local).call();
            return new Result(null, "Switched to '" + local + "'.", List.of(), local, false);
        }
        git.checkout().setCreateBranch(true).setName(local)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).setStartPoint(target).call();
        return new Result(null, "Switched to '" + local + "' (new, tracking " + target + ").", List.of(),
                local, true);
    }

    /** "origin/x" is local "x" when origin is a remote; anything else is already a local name. */
    private static String localName(Repository repo, String target) throws java.io.IOException {
        if (repo.exactRef(Constants.R_HEADS + target) != null) {
            return target;
        }
        for (String remote : repo.getRemoteNames()) {
            if (target.startsWith(remote + "/")) {
                return target.substring(remote.length() + 1);
            }
        }
        return target;
    }
}
