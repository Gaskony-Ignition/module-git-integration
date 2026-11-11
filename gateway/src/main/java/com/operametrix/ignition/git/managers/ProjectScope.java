package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The git operations whose meaning depends on the project being a prefix of a shared repository
 * (Combined layout): status, commit, revert. In Separate the {@link RepoLocator.Repo} has no prefix
 * and each call is the plain operation on the project's own repository.
 *
 * <p>The rule in Combined: what a Designer commits or reverts never reaches past
 * {@code projects/<name>/} — not into config, not into another project.
 */
public final class ProjectScope {

    private ProjectScope() {
    }

    /** Status limited to the project's paths; still repo-relative (map with {@code inProject}). */
    public static Status status(Git git, RepoLocator.Repo repo) throws Exception {
        StatusCommand cmd = git.status();
        if (repo.scoped()) {
            cmd.addPath(repo.pathspec());
        }
        return cmd.call();
    }

    /**
     * A project folder that still has its own {@code .git} cannot be part of the shared
     * repository: git treats it as a nested repository and reports every file in it as deleted.
     * Refused with a message naming it, never filtered out quietly.
     */
    public static void requireNoNestedRepo(RepoLocator.Repo repo) {
        if (repo.scoped() && Files.exists(repo.root().resolve(repo.prefix()).resolve(".git"))) {
            String name = repo.pathspec().substring("projects/".length());
            throw new IllegalStateException("Project '" + name + "' still has its own .git folder ("
                    + repo.root().resolve(repo.prefix()).resolve(".git") + "), so the gateway repository"
                    + " cannot version it. Move that .git folder out of the project, or switch the layout"
                    + " back to Separate, and try again.");
        }
    }

    /**
     * Stage and commit the given project-relative paths. In Combined the commit holds only those
     * paths ({@code git commit --only}), whatever else is staged; an amend that would rewrite a
     * commit touching anything outside the project is refused.
     */
    public static RevCommit commit(Git git, RepoLocator.Repo repo, List<String> changes, String message,
                                   boolean amend, String authorName, String authorEmail) throws Exception {
        requireNoNestedRepo(repo);
        List<String> paths = new ArrayList<>();
        for (String change : changes == null ? List.<String>of() : changes) {
            String p = repo.toRepoPath(change);
            if (!repo.contains(p)) {
                throw new IllegalArgumentException("'" + change + "' is outside this project.");
            }
            git.add().addFilepattern(p).call();
            git.add().setUpdate(true).addFilepattern(p).call();
            paths.add(p);
        }
        CommitCommand commit = git.commit().setMessage(PolicyGate.trailer(message));
        if (amend) {
            commit.setAmend(true);
        }
        if (authorName != null) {
            commit.setAuthor(authorName, authorEmail == null ? "" : authorEmail);
        }
        if (repo.scoped()) {
            Repository r = git.getRepository();
            if (amend) {
                ObjectId head = r.resolve(Constants.HEAD);
                if (head != null) {
                    for (String f : changedFiles(r, head)) {
                        if (!repo.contains(f)) {
                            throw new IllegalStateException("The last commit also changed " + f
                                    + ", which is not in this project, so it cannot be amended from here.");
                        }
                    }
                }
            }
            List<String> known = new ArrayList<>();
            for (String p : paths) {
                if (isKnown(r, repo.root(), p)) {
                    known.add(p);
                }
            }
            if (known.isEmpty()) {
                if (!amend) {
                    throw new IllegalStateException("Nothing to commit in this project.");
                }
                // Message-only amend: the rest of the index must be HEAD's, or it would ride along.
                Status s = git.status().call();
                for (Set<String> staged : List.of(s.getAdded(), s.getChanged(), s.getRemoved())) {
                    for (String f : staged) {
                        if (!repo.contains(f)) {
                            throw new IllegalStateException(f + " is staged outside this project;"
                                    + " commit or unstage it first.");
                        }
                    }
                }
            } else {
                known.forEach(commit::setOnly);
            }
        }
        return commit.call();
    }

    /**
     * Commit everything under the project's folder as it is on disk — additions, edits and
     * deletions — and nothing else. A release replacing the folder, or the project being deleted.
     *
     * @return the commit, or null when the folder already matches HEAD
     */
    public static RevCommit commitAll(Git git, RepoLocator.Repo repo, String message, String authorName)
            throws Exception {
        requireNoNestedRepo(repo);
        String spec = repo.scoped() ? repo.pathspec() : ".";
        if (Files.isDirectory(repo.root().resolve(spec))) {
            git.add().addFilepattern(spec).call();
        }
        git.add().setUpdate(true).addFilepattern(spec).call();
        Status s = status(git, repo);
        if (s.getAdded().isEmpty() && s.getChanged().isEmpty() && s.getRemoved().isEmpty()) {
            return null;
        }
        CommitCommand commit = git.commit().setMessage(PolicyGate.trailer(message)).setAuthor(authorName, "");
        if (repo.scoped()) {
            commit.setOnly(spec);
        }
        return commit.call();
    }

    /** In the working tree, the index or HEAD — {@code --only} refuses a path that is in none. */
    private static boolean isKnown(Repository r, Path root, String path) throws IOException {
        if (Files.exists(root.resolve(path))) {
            return true;
        }
        DirCache dc = r.readDirCache();
        if (dc.findEntry(path) >= 0) {
            return true;
        }
        String dir = path + "/";
        for (int i = 0; i < dc.getEntryCount(); i++) {
            if (dc.getEntry(i).getPathString().startsWith(dir)) {
                return true;
            }
        }
        ObjectId head = r.resolve(Constants.HEAD);
        if (head == null) {
            return false;
        }
        try (RevWalk walk = new RevWalk(r);
             TreeWalk tw = TreeWalk.forPath(r, path, walk.parseCommit(head).getTree())) {
            return tw != null;
        }
    }

    /** Files a commit changed against its first parent (everything, for a root commit). */
    public static List<String> changedFiles(Repository r, ObjectId commitId) throws IOException {
        List<String> out = new ArrayList<>();
        try (RevWalk walk = new RevWalk(r);
             org.eclipse.jgit.lib.ObjectReader reader = r.newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            RevCommit c = walk.parseCommit(commitId);
            df.setRepository(r);
            CanonicalTreeParser now = new CanonicalTreeParser();
            now.reset(reader, c.getTree());
            if (c.getParentCount() == 0) {
                for (DiffEntry e : df.scan(new EmptyTreeIterator(), now)) {
                    out.add(e.getNewPath());
                }
            } else {
                CanonicalTreeParser before = new CanonicalTreeParser();
                before.reset(reader, walk.parseCommit(c.getParent(0)).getTree());
                for (DiffEntry e : df.scan(before, now)) {
                    out.add(e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath());
                }
            }
        }
        return out;
    }

    /** Paths that differ between two commits. */
    public static List<String> changedBetween(Repository r, ObjectId from, ObjectId to) throws IOException {
        List<String> out = new ArrayList<>();
        try (RevWalk walk = new RevWalk(r);
             org.eclipse.jgit.lib.ObjectReader reader = r.newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(r);
            CanonicalTreeParser a = new CanonicalTreeParser();
            a.reset(reader, walk.parseCommit(from).getTree());
            CanonicalTreeParser b = new CanonicalTreeParser();
            b.reset(reader, walk.parseCommit(to).getTree());
            for (DiffEntry e : df.scan(a, b)) {
                out.add(e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath());
            }
        }
        return out;
    }

    /**
     * The remote branch a push of the checked-out branch goes to: its upstream when it has one;
     * otherwise the configured branch for the repository's home branch (the configured branch
     * itself, or the only local branch — a data-dir repository made by {@code git init} is on
     * {@code master} and has always pushed to the configured branch); otherwise the same name.
     * Never another branch onto the configured one.
     */
    public static String pushTarget(Repository r, String remote, String configured) throws IOException {
        String full = r.getFullBranch();
        if (full == null || !full.startsWith(Constants.R_HEADS)) {
            throw new IllegalStateException("HEAD is not on a branch; check out a branch before pushing.");
        }
        String branch = Repository.shortenRefName(full);
        org.eclipse.jgit.lib.BranchConfig bc = new org.eclipse.jgit.lib.BranchConfig(r.getConfig(), branch);
        if (bc.getMerge() != null && remote.equals(bc.getRemote())) {
            return Repository.shortenRefName(bc.getMerge());
        }
        if (configured != null && !configured.isBlank()) {
            boolean only = r.getRefDatabase().getRefsByPrefix(Constants.R_HEADS).size() <= 1;
            if (branch.equals(configured) || only) {
                return configured;
            }
        }
        return branch;
    }

    /** Record {@code remoteBranch} on {@code remote} as the checked-out branch's upstream. */
    public static void setUpstream(Repository r, String remote, String remoteBranch) throws IOException {
        String branch = r.getBranch();
        org.eclipse.jgit.lib.StoredConfig c = r.getConfig();
        c.setString("branch", branch, "remote", remote);
        c.setString("branch", branch, "merge", Constants.R_HEADS + remoteBranch);
        c.save();
    }

    /**
     * Uncommitted paths outside the project that a merge of {@code tip} would change: the
     * incoming side (merge base to tip) intersected with what is dirty. A pull from one Designer
     * must not merge into config or another project's unsaved work.
     */
    public static List<String> blockingChanges(Repository r, RepoLocator.Repo project, ObjectId tip,
                                               Set<String> dirty) throws IOException {
        ObjectId head = r.resolve(Constants.HEAD);
        if (head == null || tip == null) {
            return List.of();
        }
        ObjectId base;
        try (RevWalk walk = new RevWalk(r)) {
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(head));
            walk.markStart(walk.parseCommit(tip));
            RevCommit b = walk.next();
            base = b == null ? null : b.getId();
        }
        Set<String> incoming = new HashSet<>(base == null
                ? changedFiles(r, tip) : changedBetween(r, base, tip));
        List<String> out = new ArrayList<>();
        for (String p : dirty) {
            if (!project.contains(p) && incoming.contains(p)) {
                out.add(p);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Delete files neither tracked nor ignored (Sync — replace); never enters an ignored directory
     * or a nested repository, and never a whole untracked file by {@code clean -d}, which would take
     * ignored runtime data inside it too. With {@code keepNewProjects}, an untracked top-level
     * {@code projects/<name>/} — a project that exists only on this gateway — is left alone.
     *
     * @return the project folders kept
     */
    public static List<String> removeUntracked(Repository repo, boolean keepNewProjects) throws IOException {
        Path root = repo.getWorkTree().toPath();
        List<Path> doomed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(new org.eclipse.jgit.dircache.DirCacheIterator(repo.readDirCache()));
            tw.addTree(new org.eclipse.jgit.treewalk.FileTreeIterator(repo));
            tw.setRecursive(false);
            while (tw.next()) {
                org.eclipse.jgit.dircache.DirCacheIterator tracked =
                        tw.getTree(0, org.eclipse.jgit.dircache.DirCacheIterator.class);
                org.eclipse.jgit.treewalk.WorkingTreeIterator file =
                        tw.getTree(1, org.eclipse.jgit.treewalk.WorkingTreeIterator.class);
                if (file == null || file.isEntryIgnored()) {
                    continue;
                }
                if (tw.isSubtree()) {
                    String path = tw.getPathString();
                    if (keepNewProjects && tracked == null && path.startsWith("projects/")
                            && path.indexOf('/', 9) < 0) {
                        kept.add(path);
                        continue;
                    }
                    if (!org.eclipse.jgit.lib.FileMode.GITLINK.equals(file.getEntryFileMode())) {
                        tw.enterSubtree();
                    }
                } else if (tracked == null) {
                    doomed.add(root.resolve(tw.getPathString()));
                }
            }
        }
        for (Path p : doomed) {
            Files.deleteIfExists(p);
            // Drop directories the removal emptied; a non-empty one (ignored data inside) stays.
            for (Path d = p.getParent(); d != null && !d.equals(root); d = d.getParent()) {
                try {
                    Files.delete(d);
                } catch (IOException notEmpty) {
                    break;
                }
            }
        }
        return kept;
    }

    /** The project's history: the log limited to its folder in Combined. */
    public static void limitLog(LogCommand log, RepoLocator.Repo repo) {
        if (repo.scoped()) {
            log.addPath(repo.pathspec());
        }
    }

    /**
     * Revert a commit of a shared repository from one project. Refused when the commit changed
     * anything outside the project: reverting it whole would undo config or another project's
     * work from a Designer that cannot see it, and reverting part of it would leave a commit that
     * is neither. A conflicted revert is undone for the project's paths only — the hard reset the
     * Separate layout uses would also throw away uncommitted config and other projects' edits.
     */
    public static void revertScoped(Git git, RepoLocator.Repo repo, String commitHash, String userName,
                                    String email) throws Exception {
        revert(git, repo, commitHash, userName, email);
    }

    /**
     * Reverts one commit in either layout, authored by the Designer user. Only the files the commit
     * changed are ever touched: uncommitted edits to them refuse the revert, and a revert that
     * conflicts puts just those files back, so no one's uncommitted work elsewhere is lost.
     */
    public static void revert(Git git, RepoLocator.Repo repo, String commitHash, String userName,
                              String email) throws Exception {
        Repository r = git.getRepository();
        ObjectId id = r.resolve(commitHash);
        if (id == null) {
            throw new IllegalArgumentException("Commit not found: " + commitHash);
        }
        List<String> touched = changedFiles(r, id);
        if (repo.scoped()) {
            List<String> outside = new ArrayList<>();
            for (String f : touched) {
                if (!repo.contains(f)) {
                    outside.add(f);
                }
            }
            if (!outside.isEmpty()) {
                throw new IllegalStateException("Commit " + id.abbreviate(7).name() + " also changed "
                        + outside.size() + " file(s) outside this project (first: " + outside.get(0)
                        + "). In the Combined layout a revert from the Designer only touches this"
                        + " project, so this commit cannot be reverted here.");
            }
        }
        Status before = status(git, repo);
        Set<String> edited = new java.util.TreeSet<>(IgnitionReformat.realChanges(r, before));
        edited.retainAll(touched);
        if (!edited.isEmpty()) {
            throw new IllegalStateException(edited.size() + " file(s) this commit changed have uncommitted"
                    + " changes (first: " + repo.toProjectPath(edited.iterator().next())
                    + "). Commit or discard them, then revert.");
        }
        // Ignition rewrites the JSON it imports (after every pull), leaving files that differ from
        // HEAD only in formatting; HEAD's own bytes go back so they don't block the revert.
        Set<String> reformatted = new java.util.TreeSet<>(IgnitionReformat.reformatted(r, before));
        reformatted.retainAll(touched);
        if (!reformatted.isEmpty()) {
            org.eclipse.jgit.api.CheckoutCommand co = git.checkout().setStartPoint(Constants.HEAD);
            reformatted.forEach(co::addPath);
            co.call();
        }
        org.eclipse.jgit.api.RevertCommand cmd = git.revert().include(id);
        RevCommit result = cmd.call();
        if (result == null) {
            undo(git, touched, before.getUntracked());
            List<String> unmerged = cmd.getUnmergedPaths();
            // No unmerged path means JGit stopped on a file that changed under it, not a conflict.
            throw new IllegalStateException(unmerged == null || unmerged.isEmpty()
                    ? "Revert stopped: a file it needs was being written on the gateway. Nothing was"
                        + " changed; try again."
                    : "Revert failed — conflicts detected with later changes to "
                        + repo.toProjectPath(unmerged.get(0)) + ". The revert has been aborted; nothing was changed.");
        }
        reauthorHead(git, userName, email);
    }

    /**
     * Puts back the files a failed revert may have written. They matched HEAD before it started
     * (anything else refused it), so restoring them from HEAD loses nothing.
     */
    private static void undo(Git git, List<String> touched, Set<String> untrackedBefore) throws Exception {
        Repository r = git.getRepository();
        if (!touched.isEmpty()) {
            org.eclipse.jgit.api.ResetCommand reset = git.reset();
            touched.forEach(reset::addPath);
            reset.call();
            org.eclipse.jgit.revwalk.RevTree head = r.parseCommit(r.resolve(Constants.HEAD)).getTree();
            org.eclipse.jgit.api.CheckoutCommand co = git.checkout().setStartPoint(Constants.HEAD);
            boolean any = false;
            for (String f : touched) {
                if (TreeWalk.forPath(r, f, head) != null) {
                    co.addPath(f);
                    any = true;
                } else if (!untrackedBefore.contains(f)) {
                    Files.deleteIfExists(r.getWorkTree().toPath().resolve(f));
                }
            }
            if (any) {
                co.call();
            }
        }
        r.writeRevertHead(null);
        r.writeMergeCommitMsg(null);
    }

    /**
     * JGit's RevertCommand takes no author, so the revert commit is authored by the gateway's
     * git config. Rewrite just that commit (same tree, parent and message, plus the mode trailer) with
     * the Designer user.
     * Built directly rather than amended: an amend goes through the index, which in Combined can
     * hold other projects' staged work; this touches no index or working tree.
     */
    public static void reauthorHead(Git git, String userName, String email) throws java.io.IOException {
        if (userName == null || userName.isEmpty()) return;
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo); org.eclipse.jgit.lib.ObjectInserter ins = repo.newObjectInserter()) {
            RevCommit head = walk.parseCommit(repo.resolve(Constants.HEAD));
            org.eclipse.jgit.lib.PersonIdent who = new org.eclipse.jgit.lib.PersonIdent(
                    userName, email == null ? "" : email);
            org.eclipse.jgit.lib.CommitBuilder cb = new org.eclipse.jgit.lib.CommitBuilder();
            cb.setTreeId(head.getTree().getId());
            cb.setParentIds(head.getParents());
            cb.setMessage(PolicyGate.trailer(head.getFullMessage()));
            cb.setAuthor(who);
            cb.setCommitter(who);
            ObjectId id = ins.insert(cb);
            ins.flush();
            org.eclipse.jgit.lib.RefUpdate ru = repo.updateRef(Constants.HEAD);
            ru.setNewObjectId(id);
            ru.setExpectedOldObjectId(head.getId());
            ru.setRefLogMessage("revert: author " + userName, false);
            ru.forceUpdate();
        }
    }
}
