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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Where this module's records are written when the gateway runs in an 8.3 deployment mode.
 *
 * <p>A mode override is a whole copy of a resource under {@code config/resources/<Mode>/}; the
 * handlers already read the effective one. The platform's own writes go to the preferred
 * collection on create ({@code core}) and to the defining collection on modify, so without this a
 * record could never be given a per-mode value from the Versioning page. A route handler names the
 * target with {@link #writingTo}; every record save inside it lands there. {@link #readingFrom}
 * does the same for reads, so a drawer can show and edit any mode's definition — not only the
 * active one — the way the platform's own resource pages do.
 */
public final class ModeStore {

    public static final String CORE = "core";

    private static final String ACTOR = "Git Integration";

    private static final ThreadLocal<String> TARGET = new ThreadLocal<>();

    private static final ThreadLocal<String> READ = new ThreadLocal<>();

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

    /** Every deployment mode created on this gateway, sorted; empty when there are none. */
    public static List<String> modes() {
        try {
            ConfigurationManager cm = configManager();
            if (cm == null) {
                return List.of();
            }
            Set<String> out = new TreeSet<>(cm.getModes());
            out.remove(CORE);
            return new ArrayList<>(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * A requested collection checked against the gateway: blank means none; otherwise it must be
     * {@code core} or a mode created on this gateway (active or not).
     */
    public static String target(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String r = requested.trim();
        if (CORE.equals(r) || modes().contains(r)) {
            return r;
        }
        throw new IllegalArgumentException("There is no deployment mode '" + r + "' on this gateway.");
    }

    /** The collections that define one record, core first: {@code [core, Prod]}. */
    public static List<String> definers(ResourceType type, String name) {
        try {
            ConfigurationManager cm = configManager();
            if (cm == null) {
                return List.of();
            }
            Set<String> d = cm.getDefiningCollectionNames(type.childPath(name));
            List<String> out = new ArrayList<>();
            if (d.contains(CORE)) {
                out.add(CORE);
            }
            d.stream().filter(c -> !CORE.equals(c)).sorted().forEach(out::add);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Runs {@code work} with every record read inside it answered from {@code collection}'s definition. */
    public static <T> T readingFrom(String collection, Supplier<T> work) {
        return withLocal(READ, collection, work);
    }

    /** Whether reads answer from a named definition rather than the effective records. */
    public static boolean reading() {
        return READ.get() != null;
    }

    /** Runs {@code work} against the effective records, whatever definition the route edits. */
    public static <T> T effective(Supplier<T> work) {
        return withLocal(READ, null, work);
    }

    private static <T> T withLocal(ThreadLocal<String> local, String value, Supplier<T> work) {
        String before = local.get();
        if (value == null) {
            local.remove();
        } else {
            local.set(value);
        }
        try {
            return work.get();
        } finally {
            if (before == null) {
                local.remove();
            } else {
                local.set(before);
            }
        }
    }

    /**
     * One record as the current read sees it: the effective one, or under {@link #readingFrom}
     * that collection's own definition, else core's (what an override there would start from).
     */
    public static <R> Optional<DecodedResource<R>> find(NamedResourceHandler<R> handler, ResourceTypeMeta<R> meta,
                                                        String name) {
        String c = READ.get();
        if (c == null) {
            return handler.findResource(name);
        }
        ConfigurationManager cm = configManager();
        ResourcePath path = meta.getResourceType().childPath(name);
        Set<String> d = cm.getDefiningCollectionNames(path);
        String from = d.contains(c) ? c : d.contains(CORE) ? CORE : null;
        if (from == null) {
            return Optional.empty();
        }
        return cm.getResource(from, path).flatMap(r -> meta.getCodec().decodeIfValid(r));
    }

    /** Every record as the current read sees it (see {@link #find}). */
    public static <R> List<DecodedResource<R>> all(NamedResourceHandler<R> handler, ResourceTypeMeta<R> meta) {
        String c = READ.get();
        if (c == null) {
            return handler.getResources();
        }
        List<DecodedResource<R>> out = new ArrayList<>();
        for (String name : names(meta.getResourceType(), List.of(CORE, c))) {
            find(handler, meta, name).ifPresent(out::add);
        }
        return out;
    }

    /** Every definition in core and every mode — for choosing a new id no mode has used. */
    public static <R> List<DecodedResource<R>> everywhere(NamedResourceHandler<R> handler, ResourceTypeMeta<R> meta) {
        Map<String, DecodedResource<R>> out = new LinkedHashMap<>();
        for (DecodedResource<R> d : handler.getResources()) {
            out.put(d.resource().getResourcePath().getName(), d);
        }
        List<String> all = new ArrayList<>(modes());
        all.add(0, CORE);
        for (String c : all) {
            for (String name : names(meta.getResourceType(), List.of(c))) {
                if (!out.containsKey(name)) {
                    readingFrom(c, () -> find(handler, meta, name)).ifPresent(d -> out.put(name, d));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private static Set<String> names(ResourceType type, List<String> collections) {
        Set<String> out = new LinkedHashSet<>();
        ConfigurationManager cm = configManager();
        for (String c : collections) {
            cm.find(c).ifPresent(rc -> rc.getResourcesOfType(type)
                    .forEach(r -> out.add(r.getResourcePath().getName())));
        }
        return out;
    }

    /** Runs {@code work} with every record save inside it written to {@code collection}. */
    public static <T> T writingTo(String collection, Supplier<T> work) {
        return withLocal(TARGET, collection, work);
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
     * Deletes one mode's override of a record, so core's value applies there again — the
     * platform's Remove Override. With no mode named, the active mode's.
     *
     * @return false when that mode has no override of the record
     */
    public static boolean removeOverride(ResourceType type, String name, String mode) throws Exception {
        String m = mode == null || mode.isBlank()
                ? activeMode().orElseThrow(() -> new IllegalStateException(
                        "No deployment mode is active, so there is no override to remove."))
                : mode;
        if (CORE.equals(m)) {
            throw new IllegalArgumentException("core is the base definition, not an override.");
        }
        ConfigurationManager cm = configManager();
        ResourcePath path = type.childPath(name);
        if (!cm.getDefiningCollectionNames(path).contains(m)) {
            return false;
        }
        Resource existing = cm.getResource(m, path).orElseThrow();
        ResourceSignature sig = new ResourceSignature(new ResourceId(m, path), existing.getContentDigest());
        cm.push(List.of(ChangeOperation.newDeleteOp(sig))).join();
        return true;
    }

    /**
     * The platform's Create Override: a copy of the record's definition in {@code from} (core when
     * blank) written into {@code mode}, to be edited there.
     */
    public static void createOverride(ResourceType type, String name, String from, String mode) throws Exception {
        String to = target(mode);
        String src = from == null || from.isBlank() ? CORE : target(from);
        if (to == null || CORE.equals(to)) {
            throw new IllegalArgumentException("Choose a deployment mode for the override.");
        }
        ConfigurationManager cm = configManager();
        ResourcePath path = type.childPath(name);
        Set<String> d = cm.getDefiningCollectionNames(path);
        if (d.contains(to)) {
            throw new IllegalArgumentException(to + " already has its own definition.");
        }
        if (!d.contains(src)) {
            throw new IllegalArgumentException("There is no definition in " + src + " to copy.");
        }
        Resource existing = cm.getResource(src, path).orElseThrow();
        ResourceBuilder b = existing.toBuilder();
        b.setResourceCollectionName(to);
        cm.push(List.of(ChangeOperation.newCreateOp(LastModification.update(b.build(), ACTOR)))).join();
    }
}
