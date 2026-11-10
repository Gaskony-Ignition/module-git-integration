package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.IOException;
import java.util.Locale;

/**
 * What a pull, push or fetch should say instead of a raw JGit exception, and the one case a pull
 * must catch before JGit does: the checked-out branch's upstream was deleted on the remote
 * (usually merged on GitHub and its branch deleted), which JGit reports as
 * {@code RefNotAdvertisedException}. Pure, so it is unit tested.
 */
public final class PullGuard {

    /** Prefix of the message the Designer turns into "Switch to main and pull". */
    public static final String GONE_PREFIX = com.operametrix.ignition.git.PullText.GONE_PREFIX;

    /** Separates the plain message from the original exception, which the Designer puts in Details. */
    public static final String DETAILS = com.operametrix.ignition.git.PullText.DETAILS;

    private PullGuard() {
    }

    /** The checked-out branch, its remote and the remote branch it tracked, which is no longer there. */
    public record Gone(String branch, String remote, String upstream) {

        public String message() {
            return branch + "'s branch on " + remote + " was deleted — usually because it was merged.";
        }
    }

    /**
     * After a fetch with prune: the current branch's upstream when its remote-tracking ref is gone,
     * else null (no upstream, a local upstream, detached HEAD, or the branch is still there).
     */
    public static Gone goneUpstream(Repository repo) throws IOException {
        String branch = repo.getBranch();
        if (branch == null || ObjectId.isId(branch)) {
            return null;
        }
        StoredConfig c = repo.getConfig();
        String remote = c.getString("branch", branch, "remote");
        String merge = c.getString("branch", branch, "merge");
        if (remote == null || merge == null || ".".equals(remote)) {
            return null;
        }
        String upstream = Repository.shortenRefName(merge);
        return repo.exactRef(Constants.R_REMOTES + remote + "/" + upstream) == null
                ? new Gone(branch, remote, upstream) : null;
    }

    /**
     * The branch to go back to: the remote's default ({@code <remote>/HEAD}), else the configured
     * one, else main, else master — the first the remote still has and that is not {@code except}.
     * Null when it has none of them.
     */
    public static String defaultBranch(Repository repo, String remote, String configured, String except)
            throws IOException {
        String prefix = Constants.R_REMOTES + remote + "/";
        Ref head = repo.exactRef(prefix + Constants.HEAD);
        if (head != null && head.isSymbolic()) {
            String target = head.getTarget().getName();
            if (target.startsWith(prefix)) {
                String name = target.substring(prefix.length());
                if (!name.equals(except) && repo.exactRef(target) != null) {
                    return name;
                }
            }
        }
        for (String candidate : new String[]{configured, "main", "master"}) {
            if (candidate != null && !candidate.isBlank() && !candidate.equals(except)
                    && repo.exactRef(prefix + candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    /** An unattended pull's followed branch that the remote no longer has. */
    public static String missingBranch(String branch, String remote) {
        return "'" + branch + "' is no longer on " + remote + ": it was deleted there, usually because it"
                + " was merged. Not pulled; follow the branch it was merged into instead.";
    }

    /** The text the Designer parses: prefix, then branch, remote, default branch (may be empty), message. */
    public static String encode(Gone g, String defaultBranch) {
        return GONE_PREFIX + g.branch() + "\t" + g.remote() + "\t" + (defaultBranch == null ? "" : defaultBranch)
                + "\t" + g.message();
    }

    /** One sentence for what went wrong, for a person rather than for JGit's authors. */
    public static String plain(Throwable e) {
        String p = plainOrNull(e);
        return p != null ? p : e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /** {@link #plain}, or null when nothing in the chain is a failure it knows how to say. */
    public static String plainOrNull(Throwable e) {
        Throwable t = e;
        // The useful exception is often a cause or two down (TransportException in a GitAPIException).
        for (int i = 0; i < 4 && t != null; i++) {
            String p = plainOne(t);
            if (p != null) {
                return p;
            }
            t = t.getCause();
        }
        return null;
    }

    private static String plainOne(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e instanceof RefNotAdvertisedException) {
            return "That branch is not on the remote: it was deleted or renamed there, or the credential"
                    + " cannot see it.";
        }
        if (e instanceof InvalidRemoteException) {
            return "There is no remote by that name in this repository.";
        }
        if (e instanceof WrongRepositoryStateException) {
            return "The repository is in the middle of a merge or similar; finish or abort it first.";
        }
        if (e instanceof CheckoutConflictException) {
            return "Uncommitted changes would be overwritten; commit or discard them first.";
        }
        if (e instanceof NoHeadException) {
            return "The repository has no commits yet.";
        }
        if (e instanceof RefNotFoundException) {
            return "That branch or commit does not exist in this repository.";
        }
        if (e instanceof TransportException || e instanceof org.eclipse.jgit.api.errors.TransportException) {
            if (m.contains("not authorized") || m.contains("authentication") || m.contains("401")
                    || m.contains("403") || m.contains("auth fail") || m.contains("permission denied")) {
                return "The remote refused the credential: check the token or key, and that it may reach"
                        + " this repository.";
            }
            if (m.contains("not found") || m.contains("404")) {
                return "The remote repository was not found at that URL, or the credential cannot see it.";
            }
            if (m.contains("unknownhost") || m.contains("unknown host") || m.contains("connection refused")
                    || m.contains("timed out") || m.contains("cannot open git-upload-pack")
                    || m.contains("cannot open git-receive-pack")) {
                return "Could not reach the remote host. Check the URL and that this gateway can reach it.";
            }
        }
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) {
            return "Could not reach the remote host. Check the URL and that this gateway can reach it.";
        }
        return null;
    }

    /** A plain message with the original exception after {@link #DETAILS}, for a pull, push or fetch. */
    public static RuntimeException error(Throwable e) {
        return new RuntimeException(plain(e) + DETAILS + e, e);
    }
}
