package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.IOException;

/**
 * Points the gateway repository's {@code [remote "origin"]} at the config remote record. No
 * gateway context, so it is unit tested.
 *
 * <p>The record resolves per deployment mode; {@code .git/config} does not. Before 4.1.0 origin was
 * written only when the remote was saved, while pushes and fetches authenticated with the record —
 * so after a mode change one mode's credential went to the URL the other mode had saved. Every
 * push and fetch of the gateway repository now runs this first.
 */
public final class OriginSync {

    static final String ORIGIN = "origin";

    private OriginSync() {
    }

    /**
     * Sets origin's URL to {@code uri} and removes any push URL, so the URL contacted is the one the
     * credential was saved with. When {@code branch} differs from the branch last applied, branches
     * that pushed to the old one are moved to it.
     *
     * @return whether {@code .git/config} changed
     */
    public static boolean apply(Git git, String uri, String branch) throws IOException {
        StoredConfig c = git.getRepository().getConfig();
        boolean changed = false;
        String u = uri == null ? "" : uri.trim();
        if (!u.equals(c.getString("remote", ORIGIN, "url"))) {
            c.setString("remote", ORIGIN, "url", u);
            if (c.getStringList("remote", ORIGIN, "fetch").length == 0) {
                c.setString("remote", ORIGIN, "fetch", "+refs/heads/*:refs/remotes/" + ORIGIN + "/*");
            }
            changed = true;
        }
        if (c.getStringList("remote", ORIGIN, "pushurl").length > 0) {
            c.unset("remote", ORIGIN, "pushurl");
            changed = true;
        }
        String applied = c.getString("gitmodule", ORIGIN, "branch");
        if (branch != null && !branch.isBlank() && !branch.equals(applied)) {
            if (applied != null && !applied.isBlank()) {
                for (String b : c.getSubsections("branch")) {
                    if (ORIGIN.equals(c.getString("branch", b, "remote"))
                            && (Constants.R_HEADS + applied).equals(c.getString("branch", b, "merge"))) {
                        c.setString("branch", b, "merge", Constants.R_HEADS + branch);
                    }
                }
            }
            c.setString("gitmodule", ORIGIN, "branch", branch);
            changed = true;
        }
        if (changed) {
            c.save();
        }
        return changed;
    }

    /**
     * Records {@code branch} as the one last applied when nothing is recorded yet: a repository from
     * before 4.1.0, whose upstreams still point at the record's previous branch.
     */
    public static void rememberIfUnset(Git git, String branch) throws IOException {
        StoredConfig c = git.getRepository().getConfig();
        if (branch != null && !branch.isBlank() && c.getString("gitmodule", ORIGIN, "branch") == null) {
            c.setString("gitmodule", ORIGIN, "branch", branch);
            c.save();
        }
    }
}
