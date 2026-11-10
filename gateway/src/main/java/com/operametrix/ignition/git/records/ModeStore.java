package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.LastModification;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceId;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceSignature;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.ConfigurationManager;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.actions.ResourceActionSet;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.operametrix.ignition.git.GatewayHook;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Where this module's records are written when the gateway runs in an 8.3 deployment mode.
 *
 * <p>A mode override is a whole copy of a resource under {@code config/resources/<Mode>/}; the
 * handlers already read the effective one. The platform's own writes go to the preferred
 * collection on create ({@code core}) and to the defining collection on modify, so without this a
 * record could never be given a per-mode value from the Versioning page. A route handler names the
 * target with {@link #writingTo}; every record save inside it lands there.
 */
public final class ModeStore {

    public static final String CORE = "core";

    private static final String ACTOR = "Git Integration";

    private static final ThreadLocal<String> TARGET = new ThreadLocal<>();

    private ModeStore() {
    }

    /**
     * Copy, move and the rest of the platform's resource actions, plus its REST routes, so the
     * Modes page and {@code /data/api/v1/resources/copy} can override a record per mode. Without an
     * action set the platform answers "COPY operation not allowed".
     */
    public static <T> ResourceTypeMeta<T> overridable(Class<T> config, ResourceType type, String category) {
        return ResourceTypeMeta.newBuilder(config)
                .resourceType(type)
                .categoryName(category)
                .withActionSet(ResourceActionSet.DEFAULT)
                .buildRouteDelegate(b -> {
                })
                .build();
    }

    private static ConfigurationManager configManager() {
        GatewayContext ctx = GatewayHook.getContext();
        return ctx == null ? null : ctx.getConfigurationManager();
    }

    /** The gateway's active deployment mode, empty when it runs on core alone. */
    public static Optional<String> activeMode() {
        try {
            ConfigurationManager cm = configManager();
            return cm == null ? Optional.empty()
                    : cm.getActiveMode().filter(m -> !m.isBlank() && !CORE.equals(m));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The collection a record's effective value comes from ({@code core} or a mode), or null. */
    public static String definedIn(ResourceType type, String name) {
        try {
            ConfigurationManager cm = configManager();
            return cm == null ? null
                    : cm.getNamedResource(type, name).map(Resource::getDefiningCollectionName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A requested "Save to" checked against the gateway: blank keeps each record where it is
     * defined now; otherwise it must be {@code core} or the active mode.
     */
    public static String target(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String r = requested.trim();
        if (CORE.equals(r) || r.equals(activeMode().orElse(null))) {
            return r;
        }
        throw new IllegalArgumentException("Cannot save to '" + r + "': this gateway runs in "
                + activeMode().map(m -> "deployment mode " + m).orElse("core only") + ".");
    }

    /** Runs {@code work} with every record save inside it written to {@code collection}. */
    public static <T> T writingTo(String collection, Supplier<T> work) {
        String before = TARGET.get();
        TARGET.set(collection);
        try {
            return work.get();
        } finally {
            if (before == null) {
                TARGET.remove();
            } else {
                TARGET.set(before);
            }
        }
    }

    /**
     * Create or update one named record. With no target this is what the records always did:
     * modify where it is defined, create in core. With one, the write lands in that collection,
     * creating the override there if the record is only defined elsewhere.
     */
    static <R> void save(NamedResourceHandler<R> handler, ResourceTypeMeta<R> meta, String name, R config)
            throws Exception {
        String target = TARGET.get();
        Optional<DecodedResource<R>> found = handler.findResource(name);
        if (target == null) {
            if (found.isPresent()) {
                handler.modify(name, config).join();
            } else {
                handler.create(name, config).join();
            }
            return;
        }
        ConfigurationManager cm = configManager();
        ResourcePath path = meta.getResourceType().childPath(name);
        Set<String> definers = cm.getDefiningCollectionNames(path);
        if (!definers.contains(target)) {
            handler.create(name, config, ACTOR, b -> b.setResourceCollectionName(target)).join();
            return;
        }
        String effective = found.map(d -> d.resource().getDefiningCollectionName()).orElse(null);
        if (target.equals(effective)) {
            handler.modify(name, config).join();
            return;
        }
        // Defined in the target but overridden above it (core under an active mode): the handler
        // only writes the effective copy, so build the same modify it would, against the target.
        Resource existing = cm.getResource(target, path)
                .orElseThrow(() -> new IllegalStateException("No " + name + " in " + target));
        ResourceBuilder b = existing.toBuilder();
        b.setResourceCollectionName(target);
        meta.getCodec().encode(config, b);
        Resource updated = LastModification.update(b.build(), ACTOR);
        ResourceSignature sig = new ResourceSignature(new ResourceId(target, path), existing.getContentDigest());
        cm.push(List.of(ChangeOperation.newModifyOp(updated, sig))).join();
    }

    /**
     * Deletes the active mode's override of one record, so core's value applies again.
     *
     * @return false when the record has no override in the active mode
     */
    public static <R> boolean reset(NamedResourceHandler<R> handler, ResourceType type, String name)
            throws Exception {
        String mode = activeMode().orElseThrow(
                () -> new IllegalStateException("No deployment mode is active, so there is no override to reset."));
        if (!mode.equals(definedIn(type, name))) {
            return false;
        }
        // The handler deletes from the defining collection, which is the override.
        handler.delete(name).join();
        return true;
    }
}
