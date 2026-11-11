package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

/**
 * The gateway's Git policy: what this module may do here. One record, overridable per deployment
 * mode, and the only one of this module's records that is versioned with the config — so a Prod
 * override written on Dev travels to Prod in the repository.
 * The fields are {@link GitPolicy}.
 */
public class GitPolicyRecord {

    public static final String NAME = "policy";

    public static final ResourceType TYPE = new ResourceType(GitProjectsConfigRecord.MODULE_ID, "git-policy");

    public static final ResourceTypeMeta<GitPolicy> META =
            ModeStore.overridable(GitPolicy.class, TYPE, "Git Policy");

    public static final class Handler extends NamedResourceHandler<GitPolicy> {
        public Handler(GatewayContext context) {
            super(context, META);
        }
    }

    private static volatile Handler handler;

    public static void setHandler(Handler h) {
        handler = h;
    }

    private GitPolicyRecord() {
    }

    private static volatile long lastLogged;

    /** Every read asks for the policy, so the error is logged once a minute, not per request. */
    private static boolean dueToLog() {
        long now = System.currentTimeMillis();
        if (now - lastLogged < 60_000L) {
            return false;
        }
        lastLogged = now;
        return true;
    }

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GitPolicyRecord.class);

    /**
     * The effective policy (the active mode's override, else core), or the default when none is
     * saved. A policy that exists but cannot be read is {@link GitPolicy#LOCKED}, never the
     * permissive default: a broken Prod override must not open Prod up.
     */
    public static GitPolicy get() {
        Handler h = handler;
        if (h == null) {
            return GitPolicy.DEFAULT;
        }
        try {
            java.util.Optional<GitPolicy> p = ModeStore.find(h, META, NAME).map(DecodedResource::config);
            if (p.isPresent()) {
                return p.get();
            }
            if (ModeStore.definedIn(TYPE, NAME) == null) {
                return GitPolicy.DEFAULT;
            }
            if (dueToLog()) {
                logger.error("The Git policy is saved but cannot be read; pushing, branch and layout switches,"
                        + " deliveries and every remote are refused until it is fixed (Versioning > Policy).");
            }
        } catch (Exception e) {
            if (dueToLog()) {
                logger.error("The Git policy cannot be read; pushing, branch and layout switches, deliveries and"
                        + " every remote are refused until it is fixed (Versioning > Policy).", e);
            }
        }
        return GitPolicy.LOCKED;
    }

    /** Where the effective policy comes from: core, a mode, or null when none is saved. */
    public static String definedIn() {
        return ModeStore.definedIn(TYPE, NAME);
    }

    public static void save(GitPolicy c) {
        try {
            ModeStore.save(handler, META, NAME, c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Writes the default to core when the gateway has no policy yet, so a mode has one to copy.
     * Never over one that exists but cannot be read: that stays locked until someone fixes it.
     */
    public static void ensureCore() {
        Handler h = handler;
        if (h != null && ModeStore.definedIn(TYPE, NAME) == null && ModeStore.find(h, META, NAME).isEmpty()) {
            ModeStore.writingTo(ModeStore.CORE, () -> {
                save(GitPolicy.DEFAULT);
                return null;
            });
        }
    }

}
