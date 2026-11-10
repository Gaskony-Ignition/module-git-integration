package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Local branch classification and the delete rules, kept free of any gateway context so they run
 * against a plain JGit repository.
 */
public final class BranchHygiene {

    private BranchHygiene() {
    }

    /**
     * @param upstream        short name of the tracking ref ("origin/main"), or null if none configured
     * @param gone            an upstream is configured but its ref no longer exists
     * @param ahead           commits on the branch not on its upstream; with no upstream (or a gone
     *                        one), commits not reachable from any remote ref
     * @param merged          reachable from the current branch's upstream (else from HEAD)
     * @param protectedReason null, "current", or the name of whatever else needs the branch
     */
    public record Info(String name, String upstream, boolean gone, int ahead, boolean merged,
                       String protectedReason) {
    }

    /** Outcome of one delete: {@code kind} is null when deleted. */
    public record Result(String name, boolean ok, String kind, int count, String message) {
    }

    public static final String UNMERGED = "unmerged";
    public static final String UNPUSHED = "unpushed";
    public static final String PROTECTED = "protected";

    /**
     * @param protectedBranches branch name to the reason it must stay (e.g. "sync"); the checked
     *                          out branch is always protected as "current"
     */
    public static List<Info> classify(Git git, Map<String, String> protectedBranches)
            throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        List<Info> out = new ArrayList<>();
        for (Ref ref : git.branchList().call()) {
            out.add(info(repo, Repository.shortenRefName(ref.getName()), protectedBranches));
        }
        out.sort(Comparator.comparing(Info::name));
        return out;
    }

    public static Info info(Repository repo, String name, Map<String, String> protectedBranches)
            throws IOException {
        String current = repo.getBranch();
        Ref ref = repo.exactRef(Constants.R_HEADS + name);
        if (ref == null) {
            throw new IllegalArgumentException("No local branch '" + name + "'");
        }
        ObjectId tip = ref.getObjectId();

        String trackingRef = new BranchConfig(repo.getConfig(), name).getTrackingBranch();
        boolean gone = trackingRef != null && repo.exactRef(trackingRef) == null;
        String upstream = trackingRef == null ? null : Repository.shortenRefName(trackingRef);

        int ahead;
        if (trackingRef != null && !gone) {
            BranchTrackingStatus status = BranchTrackingStatus.of(repo, name);
            ahead = status == null ? 0 : status.getAheadCount();
        } else {
            ahead = countNotOnRemotes(repo, tip);
        }

        boolean merged = isMerged(repo, tip, mergeBase(repo, current));

        String reason = null;
        if (name.equals(current)) {
            reason = "current";
        } else if (protectedBranches != null && protectedBranches.get(name) != null) {
            reason = protectedBranches.get(name);
        }
        return new Info(name, upstream, gone, ahead, merged, reason);
    }

    /** Safe delete. Without force, unmerged or unpushed work refuses; protected always refuses. */
    public static Result delete(Git git, String name, boolean force, Map<String, String> protectedBranches)
            throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        Info info = info(repo, name, protectedBranches);
        if (info.protectedReason() != null) {
            return new Result(name, false, PROTECTED, 0,
                    "'" + name + "' is protected (" + info.protectedReason() + ") and cannot be deleted.");
        }
        if (!force) {
            if (info.ahead() > 0) {
                return new Result(name, false, UNPUSHED, info.ahead(), "'" + name + "' has " + info.ahead()
                        + " commit" + (info.ahead() == 1 ? "" : "s") + " that " + (info.ahead() == 1 ? "is" : "are")
                        + " not on any remote.");
            }
            if (!info.merged()) {
                ObjectId tip = repo.exactRef(Constants.R_HEADS + name).getObjectId();
                int n = countNotIn(repo, tip, mergeBase(repo, repo.getBranch()));
                return new Result(name, false, UNMERGED, n, "'" + name + "' has " + n + " commit"
                        + (n == 1 ? "" : "s") + " not merged into the current branch.");
            }
        }
        git.branchDelete().setBranchNames(name).setForce(true).call();
        return new Result(name, true, null, 0, "Deleted '" + name + "'.");
    }

    /** What "merged" is measured against: the current branch's upstream if it has one, else HEAD. */
    private static ObjectId mergeBase(Repository repo, String current) throws IOException {
        if (current != null) {
            String tracking = new BranchConfig(repo.getConfig(), current).getTrackingBranch();
            if (tracking != null) {
                Ref up = repo.exactRef(tracking);
                if (up != null) {
                    return up.getObjectId();
                }
            }
        }
        return repo.resolve(Constants.HEAD);
    }

    private static boolean isMerged(Repository repo, ObjectId tip, ObjectId base) throws IOException {
        if (base == null) {
            return false;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            return walk.isMergedInto(walk.parseCommit(tip), walk.parseCommit(base));
        }
    }

    private static int countNotIn(Repository repo, ObjectId tip, ObjectId base) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(tip));
            if (base != null) {
                walk.markUninteresting(walk.parseCommit(base));
            }
            int n = 0;
            for (RevCommit ignored : walk) {
                n++;
            }
            return n;
        }
    }

    private static int countNotOnRemotes(Repository repo, ObjectId tip) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(tip));
            for (Ref remote : repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES)) {
                if (remote.getObjectId() != null && !remote.getName().endsWith("/HEAD")) {
                    walk.markUninteresting(walk.parseCommit(remote.getObjectId()));
                }
            }
            int n = 0;
            for (RevCommit ignored : walk) {
                n++;
            }
            return n;
        }
    }
}
