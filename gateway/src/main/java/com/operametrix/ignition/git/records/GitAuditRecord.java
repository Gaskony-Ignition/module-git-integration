package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

/**
 * Which audit profile the module writes its essential actions to (4.4.0). One record per gateway,
 * not per mode and not versioned (the data-dir {@code .gitignore} keeps this module's records out
 * except the policy). Absent, it follows the gateway's own audit profile.
 *
 * @param enabled null or true writes, false writes nothing
 * @param profile a profile name; blank follows the gateway's audit profile (Gateway Settings)
 */
public class GitAuditRecord {

    public record Config(Boolean enabled, String profile) {
        public boolean on() {
            return enabled == null || enabled;
        }

        public String profileOrBlank() {
            return profile == null ? "" : profile.trim();
        }
    }

    public static final Config DEFAULT = new Config(true, "");

    public static final ResourceType TYPE = new ResourceType(GitProjectsConfigRecord.MODULE_ID, "git-audit");

    public static final ResourceTypeMeta<Config> META =
            ResourceTypeMeta.newBuilder(Config.class)
                    .resourceType(TYPE)
                    .categoryName("Git Audit")
                    .build();

    public static final String NAME = "audit";

    public static final class Handler extends NamedResourceHandler<Config> {
        public Handler(GatewayContext context) {
            super(context, META);
        }
    }

    private static volatile Handler handler;

    public static void setHandler(Handler h) {
        handler = h;
    }

    private GitAuditRecord() {
    }

    public static Config get() {
        Handler h = handler;
        if (h == null) {
            return DEFAULT;
        }
        try {
            return h.findResource(NAME).map(DecodedResource::config).orElse(DEFAULT);
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    public static void save(Config c) {
        try {
            if (handler.findResource(NAME).isPresent()) {
                handler.modify(NAME, c).join();
            } else {
                handler.create(NAME, c).join();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
