package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.DetachedHeadException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repository made by {@code git init} before 4.2.0 sits on {@code master} while its remote's
 * branch is {@code main}. Pushing still worked (the push target maps one to the other), but every
 * view showed two names for one line. This offers the one-click rename: same commits, the local
 * branch's name only, and its upstream left pointing at the remote branch. Pure, so unit tested.
 */
public final class MasterRename {

    public static final String MASTER = "master";

    private MasterRename() {
    }

    /**
     * The branch a new repository starts on: the remote branch it is set up for, else {@code main}
     * — never JGit's {@code master}, which then has no twin on a remote whose branch is main.
     */
    public static String initialBranch(String configured) {
        return configured == null || configured.isBlank() ? "main" : configured.trim();
    }

    /**
     * The name to rename {@code master} to: {@code wanted}, when master is the only local branch
     * and checked out, and wanted is another name nothing local uses. Else null.
     */
    public static String target(Repository repo, String wanted) throws IOException {
        if (wanted == null || wanted.isBlank() || MASTER.equals(wanted.trim())) {
            return null;
        }
        if (!MASTER.equals(repo.getBranch())) {
            return null;
        }
        List<Ref> heads = repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        if (heads.size() != 1) {
            return null;
        }
        return wanted.trim();
    }

    /**
     * Renames master to {@code to}. Its upstream follows the rename; with none, it is set to
     * {@code <remote>/<to>} when the remote has that branch, so pull and push need no mapping.
     */
    public static void rename(Git git, String to, String remote) throws Exception {
        Repository repo = git.getRepository();
        if (target(repo, to) == null) {
            throw new IllegalStateException("Only a repository whose one branch is master, checked out,"
                    + " can be renamed to " + to + ".");
        }
        try {
            git.branchRename().setOldName(MASTER).setNewName(to).call();
        } catch (GitAPIException | JGitInternalException e) {
            throw new IllegalStateException(plain(e, to) + PullGuard.DETAILS + e, e);
        }
        StoredConfig c = repo.getConfig();
        if (c.getString("branch", to, "merge") == null && remote != null
                && repo.exactRef(Constants.R_REMOTES + remote + "/" + to) != null) {
            c.setString("branch", to, "remote", remote);
            c.setString("branch", to, "merge", Constants.R_HEADS + to);
            c.save();
        }
    }

    private static final Pattern RESULT = Pattern.compile("rename result (\\w+)");

    /**
     * What a failed rename should say instead of JGit's text ("Unexpected rename result
     * LOCK_FAILURE"): what happened and what to do about it.
     */
    public static String plain(Throwable e, String to) {
        if (e instanceof RefAlreadyExistsException) {
            return "A branch named " + to + " already exists in this repository, so master cannot take"
                    + " that name. Switch to " + to + " from Branches, or delete it there, then try again.";
        }
        if (e instanceof InvalidRefNameException) {
            return to + " is not a valid branch name. Correct the branch in the remote settings, then"
                    + " try again.";
        }
        if (e instanceof RefNotFoundException) {
            return "There is no local master branch to rename: it was renamed or deleted already."
                    + " Reload the page.";
        }
        if (e instanceof DetachedHeadException) {
            return "The repository has a commit checked out, not a branch. Switch to master from"
                    + " Branches, then try again.";
        }
        if (e instanceof NoHeadException) {
            return "The repository has no commits yet, so there is no branch to rename.";
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        Matcher r = RESULT.matcher(m);
        String result = r.find() ? r.group(1) : "";
        if ("LOCK_FAILURE".equals(result) || hasCause(e, LockFailedException.class)
                || m.toLowerCase(Locale.ROOT).contains("cannot lock")) {
            return "The branch is locked: another git operation is using this repository, or one that"
                    + " stopped left a .lock file in .git (refs/heads/" + MASTER + ".lock, refs/heads/" + to
                    + ".lock or HEAD.lock). Wait a moment and try again; if it keeps failing, delete the"
                    + " stale .lock file. Nothing was renamed.";
        }
        if ("IO_FAILURE".equals(result) || hasCause(e, IOException.class)) {
            return "The gateway could not write the branch files in the repository's .git folder. Check"
                    + " the disk has space and the gateway may write there, then try again.";
        }
        if (!result.isEmpty()) {
            return "Git refused to rename master to " + to + " (" + result + "). Try again; the Logs"
                    + " tab and the gateway log have the details.";
        }
        return "Could not rename master to " + to + ". Try again; the Logs tab and the gateway log have"
                + " the details.";
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
