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
            throw new IllegalStateException(repo.pathspec() + " still has its own .git folder, so the"
                    + " gateway repository cannot version it. Move that .git folder out of the"
                    + " project (or switch the layout back to Separate) and try again.");
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
        CommitCommand commit = git.commit().setMessage(message);
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
        CommitCommand commit = git.commit().setMessage(message).setAuthor(authorName, "");
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
    public static void revertScoped(Git git, RepoLocator.Repo repo, String commitHash) throws Exception {
        Repository r = git.getRepository();
        ObjectId id = r.resolve(commitHash);
        if (id == null) {
            throw new IllegalArgumentException("Commit not found: " + commitHash);
        }
        List<String> outside = new ArrayList<>();
        for (String f : changedFiles(r, id)) {
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
        Set<String> untrackedBefore = new HashSet<>(status(git, repo).getUntracked());
        RevCommit result = git.revert().include(id).call();
        if (result == null) {
            git.reset().addPath(repo.pathspec()).call();
            git.checkout().setStartPoint(Constants.HEAD).addPath(repo.pathspec()).call();
            for (String f : status(git, repo).getUntracked()) {
                if (!untrackedBefore.contains(f)) {
                    Files.deleteIfExists(repo.root().resolve(f));
                }
            }
            r.writeRevertHead(null);
            r.writeMergeCommitMsg(null);
            throw new IllegalStateException("Revert failed — conflicts detected. The revert has been aborted.");
        }
    }
}
