package com.operametrix.ignition.git.automation;

import com.inductiveautomation.ignition.common.model.ApplicationScope;
import com.inductiveautomation.ignition.gateway.audit.AuditManager;
import com.inductiveautomation.ignition.gateway.audit.AuditProfile;
import com.inductiveautomation.ignition.gateway.audit.AuditRecordBuilder;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.operametrix.ignition.git.records.GitAuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One audit-profile row per essential action (4.4.0): push, delivery installed, branch and layout
 * switch, restore, policy change, credential and mode override added or removed. Polls, fetches,
 * auto-commits, per-file detail and refusals stay on the Logs tab only.
 *
 * <p>Writes happen on one background thread, so a slow or broken profile never holds up the git
 * action; a failure is logged once until a write succeeds again.
 */
public final class GitAudit {

    private static final Logger logger = LoggerFactory.getLogger(GitAudit.class);

    public static final String PUSH = "git push";
    public static final String DELIVERY = "git delivery installed";
    public static final String BRANCH = "git branch switch";
    public static final String LAYOUT = "git layout switch";
    public static final String RESTORE = "git restore";
    public static final String POLICY = "git policy change";
    public static final String AUDIT = "git audit setting change";
    public static final String CREDENTIAL_ADDED = "git credential added";
    public static final String CREDENTIAL_REMOVED = "git credential removed";
    public static final String OVERRIDE_CREATED = "git mode override created";
    public static final String OVERRIDE_REMOVED = "git mode override removed";
    public static final String PROJECT_ADDED = "git project added from repository";
    public static final String PULL = "git pull";
    public static final String JOIN = "git join remote history";

    /** The gateway repository, where an action has no project. */
    public static final String GATEWAY = "gateway";

    private static volatile GatewayContext context;
    private static volatile boolean failing;

    // Bounded: if the profile stalls, rows are dropped (and said so once) rather than piling up.
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500), r -> {
                Thread t = new Thread(r, "git-module-audit");
                t.setDaemon(true);
                return t;
            }, (r, ex) -> failOnce("the audit queue is full; an audit row was dropped", null));

    static {
        WRITER.allowCoreThreadTimeOut(true);
    }

    private GitAudit() {
    }

    public static void setContext(GatewayContext ctx) {
        context = ctx;
    }

    /**
     * Queues one row. Never throws and never waits on the profile.
     *
     * @param project the project, or null/blank for the gateway repository
     * @param detail  what changed, in a few words (no secrets)
     */
    public static void record(String action, String user, String project, String branch, String commit,
                              String detail) {
        try {
            GatewayContext ctx = context;
            if (ctx == null) {
                return;
            }
            Date when = new Date();
            WRITER.execute(() -> write(ctx, action, user, project, branch, commit, detail, when));
        } catch (Throwable t) {
            failOnce("an audit row could not be queued", t);
        }
    }

    /** The value column: {@code branch=main; commit=<sha>; <detail>}, leaving out what is unknown. */
    static String value(String branch, String commit, String detail) {
        List<String> parts = new ArrayList<>();
        if (branch != null && !branch.isBlank()) {
            parts.add("branch=" + branch.trim());
        }
        if (commit != null && !commit.isBlank()) {
            parts.add("commit=" + commit.trim());
        }
        if (detail != null && !detail.isBlank()) {
            parts.add(detail.trim());
        }
        return String.join("; ", parts);
    }

    private static void write(GatewayContext ctx, String action, String user, String project, String branch,
                              String commit, String detail, Date when) {
        try {
            AuditProfile profile = profile(ctx);
            if (profile == null) {
                return;
            }
            boolean gateway = project == null || project.isBlank();
            profile.audit(new AuditRecordBuilder()
                    .setAction(action)
                    .setActionTarget(gateway ? GATEWAY : project.trim())
                    .setActionValue(value(branch, commit, detail))
                    .setActor(user == null || user.isBlank() ? "gateway" : user)
                    .setActorHost("gateway")
                    .setOriginatingContext(ApplicationScope.GATEWAY)
                    .setOriginatingSystem(system(ctx))
                    .setStatusCode(STATUS_GOOD)
                    .setTimestamp(when)
                    .build());
            if (failing) {
                failing = false;
                logger.info("Audit rows are being written again.");
            }
        } catch (Throwable t) {
            failOnce("an audit row could not be written", t);
        }
    }

    /** As Ignition's own rows name the gateway: {@code sys:<system name>}. */
    private static String system(GatewayContext ctx) {
        try {
            return "sys:" + ctx.getSystemPropertiesManager().getSystemName();
        } catch (Exception e) {
            return "gateway";
        }
    }

    /** The quality code Ignition's own audit rows carry for a completed action (Good). */
    static final int STATUS_GOOD = 0;

    /** The chosen profile, the gateway's own when none is chosen, or null when auditing is off. */
    static AuditProfile profile(GatewayContext ctx) throws Exception {
        GitAuditRecord.Config c = GitAuditRecord.get();
        if (!c.on()) {
            return null;
        }
        AuditManager m = ctx.getAuditManager();
        return c.profileOrBlank().isEmpty() ? m.getGatewayAuditProfile() : m.getProfile(c.profileOrBlank());
    }

    /** The profiles on this gateway and the one the gateway itself uses (blank for none). */
    public static List<String> available() {
        try {
            GatewayContext ctx = context;
            return ctx == null ? List.of() : ctx.getAuditManager().getAvailableAuditProfiles();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Whether Gateway Settings names an audit profile, which the default setting follows. */
    public static boolean gatewayHasProfile() {
        try {
            GatewayContext ctx = context;
            return ctx != null && ctx.getAuditManager().getGatewayAuditProfile() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void failOnce(String what, Throwable t) {
        if (!failing) {
            failing = true;
            logger.warn("Git audit: {} ({}). Logged once until a write succeeds; the git action itself went ahead.",
                    what, t == null ? "no detail" : GitEvents.reason(t));
        }
    }
}
