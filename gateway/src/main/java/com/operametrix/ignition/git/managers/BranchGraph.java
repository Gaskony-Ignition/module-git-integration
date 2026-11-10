package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The commit graph of a repository for the web UI, with the lanes worked out here so the page only
 * draws. A pure function over a JGit repository, like {@link BranchHygiene}, so it is unit tested
 * without a gateway.
 */
public final class BranchGraph {

    public static final int DEFAULT_LIMIT = 200;

    private BranchGraph() {
    }

    /**
     * @param lane    column the commit's dot sits in
     * @param touches false only when a path filter was given and the commit leaves it untouched
     */
    public record Commit(String sha, String shortSha, List<String> parents, String author, String time,
                         String subject, int lane, boolean touches) {
    }

    /**
     * A line from a commit (row {@code from}) to one of its parents (row {@code to}). Rows index
     * {@link Result#commits()}. It runs down column {@code lane} and joins the endpoints' own
     * columns at the ends.
     */
    public record Edge(int from, int fromLane, int to, int toLane, int lane) {
    }

    /** @param type "local", "remote" or "tag" */
    public record RefInfo(String name, String type, String sha, boolean head) {
    }

    /** A local branch: hygiene info, behind its upstream, and its tip commit. */
    public record Branch(BranchHygiene.Info info, int behind, String sha, String subject, String time) {
    }

    /** @param branch current branch, or null when HEAD is detached */
    public record Result(List<Commit> commits, List<Edge> edges, List<RefInfo> refs, List<Branch> branches,
                         String branch, String headSha, int lanes, boolean truncated) {
    }

    /**
     * @param pathPrefix        repo-relative folder to flag commits against (Combined project view),
     *                          or null/empty for no filter; commits that miss it are kept and flagged
     * @param protectedBranches as for {@link BranchHygiene#classify}
     */
    public static Result build(Git git, int limit, String pathPrefix, Map<String, String> protectedBranches)
            throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        int max = limit > 0 ? limit : DEFAULT_LIMIT;
        String current = repo.getBranch();
        ObjectId head = repo.resolve(Constants.HEAD);
        boolean detached = repo.exactRef(Constants.R_HEADS + current) == null;

        List<RefInfo> refs = new ArrayList<>();
        List<Branch> branches = new ArrayList<>();
        List<ObjectId> tips = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                String name = ref.getName();
                String type;
                if (name.startsWith(Constants.R_HEADS)) {
                    type = "local";
                } else if (name.startsWith(Constants.R_REMOTES)) {
                    if (name.endsWith("/HEAD")) {
                        continue;
                    }
                    type = "remote";
                } else if (name.startsWith(Constants.R_TAGS)) {
                    type = "tag";
                } else {
                    continue;
                }
                ObjectId id = repo.getRefDatabase().peel(ref).getPeeledObjectId();
                if (id == null) {
                    id = ref.getObjectId();
                }
                if (id == null) {
                    continue;
                }
                RevCommit tip;
                try {
                    tip = walk.parseCommit(id);
                } catch (org.eclipse.jgit.errors.IncorrectObjectTypeException e) {
                    continue; // a tag on a tree or blob
                }
                tips.add(tip);
                String shortName = Repository.shortenRefName(name);
                refs.add(new RefInfo(shortName, type, tip.name(),
                        "local".equals(type) && !detached && shortName.equals(current)));
                if ("local".equals(type)) {
                    BranchHygiene.Info info = BranchHygiene.info(repo, shortName, protectedBranches);
                    branches.add(new Branch(info, behind(repo, info), tip.name(),
                            tip.getShortMessage(), iso(tip.getCommitterIdent())));
                }
            }
        }
        refs.sort((a, b) -> a.type().equals(b.type()) ? a.name().compareTo(b.name()) : a.type().compareTo(b.type()));
        branches.sort((a, b) -> a.info().name().compareTo(b.info().name()));

        List<RevCommit> walked = new ArrayList<>();
        boolean truncated = false;
        try (RevWalk walk = new RevWalk(repo)) {
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            for (ObjectId tip : tips) {
                walk.markStart(walk.parseCommit(tip));
            }
            for (RevCommit c : walk) {
                if (walked.size() == max) {
                    truncated = true;
                    break;
                }
                walked.add(c);
            }
            return layout(repo, walked, refs, branches, detached ? null : current,
                    head == null ? null : head.name(), pathPrefix, truncated);
        }
    }

    private static Result layout(Repository repo, List<RevCommit> walked, List<RefInfo> refs,
                                 List<Branch> branches, String branch, String headSha, String pathPrefix,
                                 boolean truncated) throws IOException {
        Map<String, Integer> row = new HashMap<>();
        for (int i = 0; i < walked.size(); i++) {
            row.put(walked.get(i).name(), i);
        }

        // lanes.get(n) is the commit the line in column n is waiting for, or null when free.
        List<String> lanes = new ArrayList<>();
        int[] laneOf = new int[walked.size()];
        List<int[]> pending = new ArrayList<>(); // from, fromLane, parentRow, reservedLane
        int widest = 0;
        for (int i = 0; i < walked.size(); i++) {
            RevCommit c = walked.get(i);
            int lane = lanes.indexOf(c.name());
            if (lane < 0) {
                lane = free(lanes);
                lanes.set(lane, c.name());
            }
            laneOf[i] = lane;
            // Every line that was waiting for this commit ends here.
            for (int n = 0; n < lanes.size(); n++) {
                if (c.name().equals(lanes.get(n))) {
                    lanes.set(n, null);
                }
            }
            for (int p = 0; p < c.getParentCount(); p++) {
                String parent = c.getParent(p).name();
                Integer parentRow = row.get(parent);
                if (parentRow == null) {
                    continue; // beyond the limit
                }
                int reserved = lanes.indexOf(parent);
                if (reserved < 0) {
                    reserved = p == 0 && lanes.get(lane) == null ? lane : free(lanes);
                    lanes.set(reserved, parent);
                }
                pending.add(new int[]{i, lane, parentRow, reserved});
            }
            widest = Math.max(widest, lanes.size());
        }

        List<Edge> edges = new ArrayList<>();
        for (int[] e : pending) {
            edges.add(new Edge(e[0], e[1], e[2], laneOf[e[2]], e[3]));
        }

        boolean filter = pathPrefix != null && !pathPrefix.isEmpty();
        List<Commit> commits = new ArrayList<>();
        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repo);
            if (filter) {
                df.setPathFilter(PathFilter.create(pathPrefix.endsWith("/")
                        ? pathPrefix.substring(0, pathPrefix.length() - 1) : pathPrefix));
            }
            for (int i = 0; i < walked.size(); i++) {
                RevCommit c = walked.get(i);
                boolean touches = true;
                if (filter) {
                    // Against the first parent, as `git log --first-parent -- path` reads it.
                    touches = !df.scan(c.getParentCount() == 0 ? null : c.getParent(0).getTree(), c.getTree())
                            .isEmpty();
                }
                List<String> parents = new ArrayList<>();
                for (RevCommit p : c.getParents()) {
                    parents.add(p.name());
                }
                PersonIdent who = c.getAuthorIdent();
                commits.add(new Commit(c.name(), c.name().substring(0, 7), parents,
                        who == null ? "" : who.getName(), iso(c.getCommitterIdent()), c.getShortMessage(),
                        laneOf[i], touches));
            }
        }
        return new Result(commits, edges, refs, branches, branch, headSha, widest, truncated);
    }

    private static int free(List<String> lanes) {
        int n = lanes.indexOf(null);
        if (n >= 0) {
            return n;
        }
        lanes.add(null);
        return lanes.size() - 1;
    }

    private static int behind(Repository repo, BranchHygiene.Info info) throws IOException {
        if (info.upstream() == null || info.gone()) {
            return 0;
        }
        BranchTrackingStatus status = BranchTrackingStatus.of(repo, info.name());
        return status == null ? 0 : status.getBehindCount();
    }

    private static String iso(PersonIdent ident) {
        return ident == null ? "" : Instant.ofEpochSecond(ident.getWhen().getTime() / 1000).toString();
    }
}
