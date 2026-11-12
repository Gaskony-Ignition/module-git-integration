package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceSignature;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.ConfigurationManager;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.operametrix.ignition.git.GatewayHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The singleton paths of {@link ModeStore} (4.5.0: the policy, config remote and runner records)
 * against an in-memory configuration manager that behaves like the platform's for what ModeStore
 * uses: core under the active mode, per-collection definitions, and pushes that are refused when
 * a create lands on an existing definition or a modify/delete carries a stale signature.
 */
class ModeStoreSingletonTest {

    record Cfg(String value) {
    }

    private static final ResourceType TYPE = new ResourceType("com.operametrix.ignition.git", "git-test");
    private static final ResourceTypeMeta<Cfg> META = ModeStore.singleton(Cfg.class, TYPE, "Test", null);
    private static final ResourcePath PATH = TYPE.rootPath();

    /** collection -> path -> resource */
    private final Map<String, Map<ResourcePath, Resource>> store = new HashMap<>();
    private final List<ChangeOperation> pushed = new ArrayList<>();
    private final Set<String> modes = new TreeSet<>();
    private String active;

    @BeforeEach
    void install() throws Exception {
        setContext(context());
    }

    @AfterEach
    void uninstall() throws Exception {
        setContext(null);
    }

    private static void setContext(GatewayContext ctx) throws Exception {
        Field f = GatewayHook.class.getDeclaredField("context");
        f.setAccessible(true);
        f.set(null, ctx);
    }

    // --- the fake ------------------------------------------------------------------------

    private Resource stored(String collection, String value) throws Exception {
        var b = Resource.newBuilder().setResourcePath(PATH).setResourceCollectionName(collection);
        META.getCodec().encode(new Cfg(value), b);
        Resource r = b.build();
        store.computeIfAbsent(collection, k -> new HashMap<>()).put(PATH, r);
        if (!ModeStore.CORE.equals(collection)) {
            modes.add(collection);
        }
        return r;
    }

    private Optional<Resource> in(String collection) {
        return Optional.ofNullable(store.getOrDefault(collection, Map.of()).get(PATH));
    }

    private Optional<String> valueIn(String collection) {
        return in(collection).flatMap(r -> META.getCodec().decodeIfValid(r)).map(d -> d.config().value());
    }

    private Set<String> definers(ResourcePath p) {
        Set<String> out = new LinkedHashSet<>();
        store.forEach((c, m) -> {
            if (m.containsKey(p)) {
                out.add(c);
            }
        });
        return out;
    }

    private Optional<Resource> effective(ResourcePath p) {
        if (active != null && store.getOrDefault(active, Map.of()).containsKey(p)) {
            return Optional.of(store.get(active).get(p));
        }
        return Optional.ofNullable(store.getOrDefault(ModeStore.CORE, Map.of()).get(p));
    }

    private void apply(ChangeOperation op) {
        pushed.add(op);
        if (op instanceof ChangeOperation.CreateResourceOperation c) {
            Resource r = c.getResource();
            Map<ResourcePath, Resource> m = store.computeIfAbsent(r.getCollectionName(), k -> new HashMap<>());
            if (m.containsKey(r.getResourcePath())) {
                throw new IllegalStateException("create over an existing definition in " + r.getCollectionName());
            }
            m.put(r.getResourcePath(), r);
        } else if (op instanceof ChangeOperation.ModifyResourceOperation mo) {
            checkSignature(mo.getBaseSignature());
            Resource r = mo.getResource();
            store.get(r.getCollectionName()).put(r.getResourcePath(), r);
        } else if (op instanceof ChangeOperation.DeleteResourceOperation d) {
            checkSignature(d.getResourceSignature());
            store.get(d.getResourceId().getResourceCollectionName()).remove(d.getResourceId().getResourcePath());
        } else {
            throw new UnsupportedOperationException(op.toString());
        }
    }

    private void checkSignature(ResourceSignature sig) {
        Resource cur = store.getOrDefault(sig.resourceId().getResourceCollectionName(), Map.of())
                .get(sig.resourceId().getResourcePath());
        if (cur == null || !cur.getContentDigest().equals(sig.signature())) {
            throw new IllegalStateException("stale signature for " + sig.resourceId());
        }
    }

    @SuppressWarnings("unchecked")
    private GatewayContext context() {
        ConfigurationManager cm = (ConfigurationManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConfigurationManager.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getActiveMode":
                            return Optional.ofNullable(active);
                        case "getModes":
                            Set<String> all = new TreeSet<>(modes);
                            all.add(ModeStore.CORE);
                            return all;
                        case "getDefiningCollectionNames":
                            return definers((ResourcePath) args[0]);
                        case "getResource":
                            if (args.length == 1) {
                                return effective((ResourcePath) args[0]);
                            }
                            return Optional.ofNullable(
                                    store.getOrDefault((String) args[0], Map.of()).get((ResourcePath) args[1]));
                        case "push":
                            for (ChangeOperation op : (List<ChangeOperation>) args[0]) {
                                apply(op);
                            }
                            return CompletableFuture.completedFuture(null);
                        default:
                            throw new UnsupportedOperationException(m.getName());
                    }
                });
        return (GatewayContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{GatewayContext.class}, (proxy, m, args) -> {
                    if (m.getName().equals("getConfigurationManager")) {
                        return cm;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private Optional<String> read() {
        return ModeStore.findSingleton(META).map(DecodedResource::config).map(Cfg::value);
    }

    // --- path ----------------------------------------------------------------------------

    @Test
    void aSingletonLivesAtItsTypesOwnFolder() {
        assertThat(ModeStore.path(TYPE, null)).isEqualTo(TYPE.rootPath());
        assertThat(ModeStore.path(TYPE, "x")).isEqualTo(TYPE.childPath("x"));
    }

    // --- read ----------------------------------------------------------------------------

    @Test
    void findReadsTheEffectiveDefinition() throws Exception {
        stored("core", "core-value");
        assertThat(read()).contains("core-value");

        stored("Staging", "staging-value");
        assertThat(read()).as("Staging defined but not active").contains("core-value");
        active = "Staging";
        assertThat(read()).contains("staging-value");
    }

    @Test
    void findIsEmptyWhenNothingDefinesIt() {
        assertThat(read()).isEmpty();
        assertThat(ModeStore.readingFrom("Staging", this::read)).isEmpty();
    }

    @Test
    void readingFromAModeAnswersThatModesDefinitionWhateverIsActive() throws Exception {
        stored("core", "core-value");
        stored("Staging", "staging-value");
        stored("Prod", "prod-value");
        active = "Prod";
        assertThat(ModeStore.readingFrom("Staging", this::read)).contains("staging-value");
        assertThat(ModeStore.readingFrom("core", this::read)).contains("core-value");
        assertThat(read()).as("the read scope ends with the call").contains("prod-value");
    }

    @Test
    void readingFromAModeWithNoOverrideAnswersCore() throws Exception {
        stored("core", "core-value");
        modes.add("Staging");
        assertThat(ModeStore.readingFrom("Staging", this::read)).contains("core-value");
    }

    @Test
    void effectiveIgnoresAnEnclosingReadScope() throws Exception {
        stored("core", "core-value");
        stored("Staging", "staging-value");
        active = "Staging";
        assertThat(ModeStore.readingFrom("core", () -> ModeStore.effective(this::read))).contains("staging-value");
    }

    @Test
    void definersListCoreFirstThenModesSorted() throws Exception {
        stored("Staging", "s");
        stored("Prod", "p");
        stored("core", "c");
        assertThat(ModeStore.definers(TYPE, null)).containsExactly("core", "Prod", "Staging");
        active = "Staging";
        assertThat(ModeStore.definedIn(TYPE, null)).isEqualTo("Staging");
        assertThat(ModeStore.modes()).containsExactly("Prod", "Staging");
        assertThat(ModeStore.activeMode()).contains("Staging");
    }

    // --- save ----------------------------------------------------------------------------

    @Test
    void saveWithNothingDefinedCreatesItInCore() throws Exception {
        ModeStore.saveSingleton(META, new Cfg("new"));
        assertThat(valueIn("core")).contains("new");
        assertThat(pushed).singleElement().isInstanceOf(ChangeOperation.CreateResourceOperation.class);
    }

    @Test
    void saveWithNoTargetModifiesWhereTheEffectiveValueIsDefined() throws Exception {
        stored("core", "core-value");
        stored("Staging", "staging-value");
        active = "Staging";

        ModeStore.saveSingleton(META, new Cfg("edited"));

        assertThat(valueIn("Staging")).contains("edited");
        assertThat(valueIn("core")).as("core untouched").contains("core-value");
        assertThat(pushed).singleElement().isInstanceOf(ChangeOperation.ModifyResourceOperation.class);
    }

    @Test
    void saveToCoreWhileAModeOverridesItWritesCoreNotTheOverride() throws Exception {
        stored("core", "core-value");
        stored("Staging", "staging-value");
        active = "Staging";

        ModeStore.writingTo("core", () -> {
            try {
                ModeStore.saveSingleton(META, new Cfg("core-edited"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        assertThat(valueIn("core")).contains("core-edited");
        assertThat(valueIn("Staging")).contains("staging-value");
    }

    @Test
    void saveToAModeWithNoOverrideCreatesTheOverrideThere() throws Exception {
        stored("core", "core-value");
        modes.add("Prod");

        ModeStore.writingTo("Prod", () -> {
            try {
                ModeStore.saveSingleton(META, new Cfg("prod-value"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        assertThat(valueIn("Prod")).contains("prod-value");
        assertThat(valueIn("core")).contains("core-value");
        assertThat(ModeStore.definers(TYPE, null)).containsExactly("core", "Prod");
    }

    @Test
    void theWriteTargetEndsWithTheCall() throws Exception {
        stored("core", "core-value");
        modes.add("Prod");
        ModeStore.writingTo("Prod", () -> null);

        ModeStore.saveSingleton(META, new Cfg("after"));

        assertThat(valueIn("core")).contains("after");
        assertThat(in("Prod")).isEmpty();
    }

    // --- overrides -----------------------------------------------------------------------

    @Test
    void createOverrideCopiesCoreIntoTheMode() throws Exception {
        stored("core", "core-value");
        modes.add("Staging");

        ModeStore.createOverride(TYPE, null, "", "Staging");

        assertThat(valueIn("Staging")).contains("core-value");
        assertThat(in("Staging").orElseThrow().getCollectionName()).isEqualTo("Staging");
    }

    @Test
    void createOverrideCopiesFromTheNamedMode() throws Exception {
        stored("core", "core-value");
        stored("Staging", "staging-value");
        modes.add("Prod");

        ModeStore.createOverride(TYPE, null, "Staging", "Prod");

        assertThat(valueIn("Prod")).contains("staging-value");
    }

    @Test
    void createOverrideRefusesWhatItCannotDo() throws Exception {
        stored("core", "core-value");
        stored("Staging", "staging-value");
        modes.add("Prod");

        assertThatThrownBy(() -> ModeStore.createOverride(TYPE, null, "", "Staging"))
                .hasMessageContaining("already has its own definition");
        assertThatThrownBy(() -> ModeStore.createOverride(TYPE, null, "", "core"))
                .hasMessageContaining("Choose a deployment mode");
        assertThatThrownBy(() -> ModeStore.createOverride(TYPE, null, "", "Nowhere"))
                .hasMessageContaining("no deployment mode 'Nowhere'");
        assertThatThrownBy(() -> ModeStore.createOverride(TYPE, null, "Prod", "Staging"))
                .isInstanceOf(IllegalArgumentException.class);
        store.get("core").clear();
        store.get("Staging").clear();
        assertThatThrownBy(() -> ModeStore.createOverride(TYPE, null, "", "Prod"))
                .hasMessageContaining("no definition in core");
        assertThat(pushed).isEmpty();
    }

    @Test
    void removeOverrideDeletesOnlyThatModesCopy() throws Exception {
        stored("core", "core-value");
        stored("Staging", "staging-value");
        stored("Prod", "prod-value");

        assertThat(ModeStore.removeOverride(TYPE, null, "Staging")).isTrue();

        assertThat(in("Staging")).isEmpty();
        assertThat(valueIn("Prod")).contains("prod-value");
        assertThat(valueIn("core")).contains("core-value");
    }

    @Test
    void removeOverrideWithNoModeNamedRemovesTheActiveModes() throws Exception {
        stored("core", "core-value");
        stored("Staging", "staging-value");
        active = "Staging";

        assertThat(ModeStore.removeOverride(TYPE, null, " ")).isTrue();

        assertThat(in("Staging")).isEmpty();
        assertThat(read()).contains("core-value");
    }

    @Test
    void removeOverrideIsFalseWhenTheModeHasNone() throws Exception {
        stored("core", "core-value");
        modes.add("Prod");
        assertThat(ModeStore.removeOverride(TYPE, null, "Prod")).isFalse();
        assertThat(pushed).isEmpty();
    }

    @Test
    void removeOverrideRefusesCoreAndNoActiveMode() throws Exception {
        stored("core", "core-value");
        assertThatThrownBy(() -> ModeStore.removeOverride(TYPE, null, "core"))
                .hasMessageContaining("core is the base definition");
        assertThatThrownBy(() -> ModeStore.removeOverride(TYPE, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No deployment mode is active");
        assertThat(valueIn("core")).contains("core-value");
    }
}
