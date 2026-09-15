package com.operametrix.ignition.git.automation;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.operametrix.ignition.git.managers.GitProjectManager;
import com.operametrix.ignition.git.records.GitProjectsConfigRecord;
import com.operametrix.ignition.git.records.GitRemoteCredentialsRecord;
import com.operametrix.ignition.git.records.GitRunnerRecord;
import com.operametrix.ignition.git.records.GitSyncRecord;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * The route a runner calls in repo-updates mode: pull the named project's branch now.
 *
 * <p>Access control is {@link RunnerAuth}; everything the session would normally do is done
 * there instead, before the body is read.
 *
 * <p>Unlike the webhook this replaced, the caller is on the local network — the runner dials out
 * to GitHub and is handed the job over its own connection, so nothing about this route needs to
 * be reachable from the internet.
 */
public final class RunnerTrigger {

    private static final Logger logger = LoggerFactory.getLogger(RunnerTrigger.class);

    /** Bodies above this are refused unread. The real body is a few dozen bytes. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private RunnerTrigger() {
    }

    public static Object handle(RequestContext req, HttpServletResponse resp) {
        String rejected = RunnerAuth.reject(req, resp);
        if (rejected != null) {
            return rejected;
        }

        String project;
        try {
            byte[] body = RunnerAuth.readBody(req, MAX_BODY_BYTES);
            JsonObject o = new Gson().fromJson(new String(body, StandardCharsets.UTF_8),
                    JsonObject.class);
            project = o != null && o.has("project") && !o.get("project").isJsonNull()
                    ? o.get("project").getAsString() : null;
        } catch (IllegalStateException tooBig) {
            resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return "{\"error\":\"body too large\"}";
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "{\"error\":\"body is not JSON\"}";
        }

        if (project == null || project.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return "{\"error\":\"no project named\"}";
        }

        // A Scheduled sync record, when one exists, says which branch and credential to use.
        // Without one the project's own checked-out branch and remote are used.
        GitSyncRecord sync = GitSyncRecord.findByProject(project);
        if (sync == null) {
            sync = fromProject(project);
        }
        if (sync == null) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "{\"error\":\"'" + project + "' is not a git repository with a remote on this gateway\"}";
        }

        try {
            // Runs even when the scheduled timer is off: that says "pull on demand only".
            String result = SyncScheduler.syncNow(sync);
            logger.info("Runner requested a sync of '{}': {}", project, result);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("project", project);
            o.addProperty("result", result);
            return o.toString();
        } catch (Exception e) {
            logger.warn("Runner-requested sync of '{}' failed.", project, e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("error", String.valueOf(e.getMessage()));
            return o.toString();
        }
    }

    /**
     * An unsaved sync setting built from the project itself: its checked-out branch, its remote,
     * and the user whose stored credential that remote already uses. Null when the project is not
     * a repository with a remote.
     */
    static GitSyncRecord fromProject(String project) {
        GitProjectManager.ProjectStatus status = GitProjectManager.listProjectStatus().stream()
                .filter(p -> p.name().equals(project) && p.versioned()
                        && p.remoteUrl() != null && !p.remoteUrl().isBlank())
                .findFirst().orElse(null);
        if (status == null) {
            return null;
        }
        String remote = status.remoteName() == null ? "origin" : status.remoteName();

        String user = GitRunnerRecord.get().getIgnitionUser();
        GitProjectsConfigRecord reg = GitProjectsConfigRecord.findByProjectName(project);
        if (reg != null) {
            user = GitRemoteCredentialsRecord.listByProject(reg.getId()).stream()
                    .filter(c -> remote.equals(c.getRemoteName())
                            && (c.getHttpsCredentialId() > 0 || c.getSshKeyId() > 0))
                    .map(GitRemoteCredentialsRecord::getIgnitionUser)
                    .findFirst().orElse(user);
        }

        GitSyncRecord s = new GitSyncRecord();
        s.setProject(project);
        s.setEnabled(false);
        s.setRemoteName(remote);
        String branch = status.branch();
        s.setBranch(branch == null || branch.contains("(detached)") ? "" : branch);
        s.setIgnitionUser(user);
        return s;
    }
}
