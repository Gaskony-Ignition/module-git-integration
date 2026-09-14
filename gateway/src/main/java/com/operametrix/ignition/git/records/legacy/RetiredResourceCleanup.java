package com.operametrix.ignition.git.records.legacy;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMetaRegistry;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Deletes, once, the config resources of types this module no longer registers a handler for:
 * {@code git-automation} and {@code git-trigger} (removed in 3.0.0 along with the event-delivery
 * and outbound-trigger features), and {@code git-webhook} (removed in 2.14.0, never cleaned up).
 *
 * <p>A resource of an unregistered type is inert: nothing decodes it, nothing reads it, and
 * nothing else in the module would ever notice it is still there. Left alone it sits in
 * {@code data/config/resources/core/com.operametrix.ignition.git/} forever. That is more than a
 * tidiness problem here — a retired {@code git-trigger} resource's free-text {@code headers}
 * field could hold a plaintext token typed into an outbound-trigger header, and
 * {@code data/config} is versioned by this module's own config-as-code repo, so leaving it means
 * a credential sitting in git history rather than just on disk.
 *
 * <p>Follows the same registration mechanism as every {@code *Record} class: a {@link
 * ResourceTypeMeta} per retired type, backed by a {@link NamedResourceHandler}. The config shape
 * doesn't matter — {@link Retired} decodes any leftover JSON harmlessly, since Gson ignores
 * fields it doesn't declare — only the {@link ResourceType} id has to match what the old resource
 * was written under, so the platform's resource scan still finds it.
 *
 * <p>This class can be removed in a later release, once every gateway that was ever on a
 * pre-3.0.0 (or pre-2.14.0, for the webhook type) build has started at least once on a version
 * that carries this cleanup.
 */
public final class RetiredResourceCleanup {

    private static final Logger logger = LoggerFactory.getLogger(RetiredResourceCleanup.class);

    private static final String MODULE_ID = "com.operametrix.ignition.git";

    /** Decodes any retired resource's JSON harmlessly; its own fields are never read. */
    private record Retired() {}

    private static final ResourceTypeMeta<Retired> AUTOMATION_META =
            ResourceTypeMeta.newBuilder(Retired.class)
                    .resourceType(new ResourceType(MODULE_ID, "git-automation"))
                    .categoryName("Git Automation (retired)")
                    .build();

    private static final ResourceTypeMeta<Retired> TRIGGER_META =
            ResourceTypeMeta.newBuilder(Retired.class)
                    .resourceType(new ResourceType(MODULE_ID, "git-trigger"))
                    .categoryName("Git Triggers (retired)")
                    .build();

    private static final ResourceTypeMeta<Retired> WEBHOOK_META =
            ResourceTypeMeta.newBuilder(Retired.class)
                    .resourceType(new ResourceType(MODULE_ID, "git-webhook"))
                    .categoryName("Git Webhook (retired)")
                    .build();

    private RetiredResourceCleanup() {
    }

    /** Called from {@code GatewayHook.setup()}, alongside the module's live resource types. */
    public static void registerMetas(ResourceTypeMetaRegistry registry) {
        registry.register(AUTOMATION_META);
        registry.register(TRIGGER_META);
        registry.register(WEBHOOK_META);
    }

    /**
     * Called from {@code GatewayHook.startup()}, after the live handlers have started and before
     * {@code ConfigAutoCommitter} attaches — so the deletions land inside the same startup window
     * {@code commitLeftovers()} sweeps into one config commit, rather than each triggering its own.
     *
     * <p>Never throws: a cleanup that can't run (a redundant backup node refusing local writes, a
     * resource type the platform doesn't recognise this run) must not be able to block startup.
     */
    public static void deleteRetired(GatewayContext ctx) {
        deleteAll(ctx, "git-automation", AUTOMATION_META);
        deleteAll(ctx, "git-trigger", TRIGGER_META);
        deleteAll(ctx, "git-webhook", WEBHOOK_META);
    }

    private static void deleteAll(GatewayContext ctx, String typeId, ResourceTypeMeta<Retired> meta) {
        Handler handler = null;
        try {
            handler = new Handler(ctx, meta);
            handler.startup();
            List<DecodedResource<Retired>> resources = handler.getResources();
            int removed = 0;
            int failed = 0;
            String lastFailure = null;
            for (DecodedResource<Retired> r : resources) {
                try {
                    handler.delete(r.name()).join();
                    removed++;
                } catch (Throwable t) {
                    failed++;
                    lastFailure = t.toString();
                }
            }
            if (removed > 0) {
                logger.info("Removed {} retired {} resource(s).", removed, typeId);
            } else if (failed == 0) {
                logger.debug("No retired {} resources to remove.", typeId);
            }
            if (failed > 0) {
                // WARN, not DEBUG: on a normal gateway this means a resource that may hold a token
                // is still on disk and in the config repo. A redundant backup node refuses local
                // config writes and will log this too — the master's delete replicates to it.
                logger.warn("Could not remove {} retired {} resource(s); they stay on disk until a"
                        + " later start succeeds. Last error: {}", failed, typeId, lastFailure);
            }
        } catch (Throwable t) {
            logger.warn("Retired-resource cleanup for {} did not run: {}", typeId, t.toString());
        } finally {
            if (handler != null) {
                try {
                    handler.shutdown();
                } catch (Throwable t) {
                    logger.debug("Shutting down the retired {} handler failed: {}", typeId, t.toString());
                }
            }
        }
    }

    private static final class Handler extends NamedResourceHandler<Retired> {
        Handler(GatewayContext context, ResourceTypeMeta<Retired> meta) {
            super(context, meta);
        }
    }
}
