package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Before the gateway repository pushes to or pulls from a branch: does that branch look like a
 * gateway repository? The module does not remap folders, so an existing repository laid out
 * another way ({@code gateway/config/}, {@code project/}) is restructured once on its side, and
 * until then nothing is pushed over it or merged from it. Pure, so unit tested.
 */
public final class LayoutCheck {

    public static final String DOC = "docs/LAYOUT.md, \"Using an existing repository\"";

    private LayoutCheck() {
    }

    /**
     * Why {@code remoteTip} cannot be pushed to or pulled from, or null when it can (or does not
     * exist yet: a push creates it).
     *
     * @param localHead     this gateway's HEAD, or null to skip the shared-history test (a replace,
     *                      which resets to the branch by design)
     * @param combined      whether projects/ is expected too (named in the message only)
     */
    public static String problem(Repository repo, ObjectId remoteTip, ObjectId localHead, String where,
                                 boolean combined) throws IOException {
        if (remoteTip == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit tip = walk.parseCommit(remoteTip);
            if (TreeWalk.forPath(repo, "config", tip.getTree()) == null) {
                return where + " does not look like a gateway repository: it has no config/ folder at the top"
                        + topLevel(repo, tip) + ". This gateway expects config/"
                        + (combined ? " and projects/<name>/" : "")
                        + " there and does not remap folders. Restructure that repository once — for example"
                        + " git mv gateway/config config" + (combined ? "; git mv project projects/<ProjectName>" : "")
                        + "; commit and push — then try again. See " + DOC + ". Nothing was pushed or pulled.";
            }
            if (localHead != null) {
                walk.reset();
                walk.setRevFilter(RevFilter.MERGE_BASE);
                walk.markStart(walk.parseCommit(remoteTip));
                walk.markStart(walk.parseCommit(localHead));
                if (walk.next() == null) {
                    return where + " has a history unrelated to this gateway's repository, so it was not"
                            + " pushed over or merged. Bring this gateway's commits into that repository"
                            + " (restructured to this layout), or push to a new branch. See " + DOC + ".";
                }
            }
        }
        return null;
    }

    private static String topLevel(Repository repo, RevCommit tip) throws IOException {
        List<String> names = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tip.getTree());
            tw.setRecursive(false);
            while (tw.next() && names.size() < 6) {
                names.add(tw.getNameString() + (tw.isSubtree() ? "/" : ""));
            }
        }
        return names.isEmpty() ? "" : " (it has " + String.join(", ", names) + ")";
    }
}
