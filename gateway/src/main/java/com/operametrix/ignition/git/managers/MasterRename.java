package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.IOException;
import java.util.List;

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
        git.branchRename().setOldName(MASTER).setNewName(to).call();
        StoredConfig c = repo.getConfig();
        if (c.getString("branch", to, "merge") == null && remote != null
                && repo.exactRef(Constants.R_REMOTES + remote + "/" + to) != null) {
            c.setString("branch", to, "remote", remote);
            c.setString("branch", to, "merge", Constants.R_HEADS + to);
            c.save();
        }
    }
}
