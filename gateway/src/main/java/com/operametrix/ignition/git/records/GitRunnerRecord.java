package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.NamedResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.operametrix.ignition.git.GatewayHook;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Gateway-level singleton holding the GitHub Actions runner configuration.
 *
 * <p>A self-hosted runner needs no inbound path to the gateway — it dials out to GitHub and is
 * handed jobs over its own connection — so the only thing the gateway has to accept is a call
 * from the runner on the local network. That call carries the token held here.
 *
 * <p>The token is a {@link SecretConfig} for the same reason every other credential in this
 * module is: it is the sole thing standing between an open route and anyone who can reach the
 * gateway, so it is never written to disk in the clear and never returned to the browser after
 * the one time it is generated.
 */
public class GitRunnerRecord {

    public static final String MODULE_ID = "com.operametrix.ignition.git";

    /** The one resource name; the runner configuration is a gateway-level singleton. */
    private static final String NAME = "runner";

    /** How a runner delivers to a project: a release zip replacing it, or a pull of its branch. */
    public static final String MODE_RELEASE = "release";
    public static final String MODE_REPO = "repo";

    /**
     * {@code ignitionUser} and {@code modes} arrived in 3.1.0, so a record saved earlier decodes
     * them as null. {@code modes} is a JSON object of project name to mode.
     *
     * <p>A {@code labels} field was carried until 3.3.0. Nothing ever read it when a runner
     * called — it only filled in generated commands the page no longer shows — so it is gone, and
     * a resource saved by an earlier version decodes with that key ignored. {@code gatewayUrl}
     * went the same way in 3.4.0: it only filled in the check command, and a saved copy on the
     * gateway was mistaken for the address deploys use.
     *
     * <p>{@code optIn} arrived in 3.5.0, when delivery became opt-in: a project with no mode is
     * refused. It is kept only so a record saved by 3.5.0 or 3.6.0 still decodes; nothing reads it.
     *
     * <p>{@code cleared} arrived in 3.7.0. 3.5.0 upgraded gateways by giving every project folder
     * Release, which set deliveries nobody had chosen — including on projects that were not even
     * versioned. A record saved before 3.7.0 decodes it as false, which triggers the one-time
     * {@link #clearAssignedDeliveries}; from then on a delivery only ever comes from the drawer.
     */
    public record Config(boolean enabled, SecretConfig token, String ignitionUser,
                         String modes, boolean optIn, boolean cleared) {}

    public static final ResourceType TYPE = new ResourceType(MODULE_ID, "git-runner");

    public static final ResourceTypeMeta<Config> META =
            ResourceTypeMeta.newBuilder(Config.class)
                    .resourceType(TYPE)
                    .categoryName("Git Actions Runner")
                    .build();

    public static final class Handler extends NamedResourceHandler<Config> {
        public Handler(GatewayContext context) {
            super(context, META);
        }
    }

    private static volatile Handler handler;

    public static void setHandler(Handler h) {
        handler = h;
    }

    private boolean enabled;
    private SecretConfig token;
    private String ignitionUser = "";
    private JsonObject modes = new JsonObject();
    // Kept so a 3.5.0/3.6.0 record still round-trips; nothing reads it.
    private boolean optIn = true;
    // True for anything created from 3.7.0 on; only a record saved earlier starts false.
    private boolean cleared = true;

    public GitRunnerRecord() {
    }

    private GitRunnerRecord(Config c) {
        this.enabled = c.enabled();
        this.token = c.token();
        this.ignitionUser = c.ignitionUser() == null ? "" : c.ignitionUser();
        this.optIn = c.optIn();
        this.cleared = c.cleared();
        try {
            JsonObject m = c.modes() == null || c.modes().isBlank()
                    ? null : new Gson().fromJson(c.modes(), JsonObject.class);
            this.modes = m == null ? new JsonObject() : m;
        } catch (Exception e) {
            this.modes = new JsonObject();
        }
    }

    /** The stored configuration, or a disabled default when none has been saved. */
    public static GitRunnerRecord get() {
        try {
            return handler.findResource(NAME)
                    .map(DecodedResource::config)
                    .map(GitRunnerRecord::new)
                    .orElseGet(GitRunnerRecord::new);
        } catch (Exception e) {
            return new GitRunnerRecord();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public boolean hasToken() {
        return token != null;
    }

    /**
     * The Ignition user who last saved the runner settings. A repo-updates pull runs unattended,
     * so when no remote credential names a user this is whose credential it falls back to.
     */
    public String getIgnitionUser() {
        return ignitionUser == null ? "" : ignitionUser;
    }

    public void setIgnitionUser(String v) {
        this.ignitionUser = v == null ? "" : v.trim();
    }

    /**
     * The runner delivery enabled for this project — {@link #MODE_RELEASE} or {@link #MODE_REPO} —
     * or null when the runner may not deliver to it at all. Delivery is opt-in: the routes refuse a
     * project with no mode.
     */
    public String chosenMode(String project) {
        if (project == null || !modes.has(project)) {
            return null;
        }
        // By value, not by presence: a key left by an older version, or one holding anything but
        // the two modes, is not a choice anyone made, and a delivery nobody chose is the bug 3.7.0
        // exists to fix.
        String m = modes.get(project).getAsString();
        if (MODE_REPO.equals(m)) {
            return MODE_REPO;
        }
        return MODE_RELEASE.equals(m) ? MODE_RELEASE : null;
    }

    public void setMode(String project, String mode) {
        if (project == null || project.isBlank()) {
            return;
        }
        modes.addProperty(project, MODE_REPO.equals(mode) ? MODE_REPO : MODE_RELEASE);
    }

    public void clearMode(String project) {
        if (project != null) {
            modes.remove(project);
        }
    }

    /**
     * Drops every runner mode for a project the gateway no longer has. A name left behind by a
     * deleted project would otherwise hand its delivery to whatever is created under that name next.
     *
     * @return the names dropped
     */
    public List<String> pruneMissing(List<String> projects) {
        List<String> gone = new ArrayList<>();
        for (String name : new ArrayList<>(modes.keySet())) {
            if (!projects.contains(name)) {
                modes.remove(name);
                gone.add(name);
            }
        }
        return gone;
    }

    /**
     * Once, on a record saved before 3.7.0: forget every runner mode, so no project carries a
     * delivery nobody picked. 3.5.0's upgrade handed Release to every project folder on a gateway
     * whose runner was on, and a stored mode is indistinguishable from a chosen one — so the only
     * honest fix is to clear them and let each be chosen again. The caller disables the scheduled
     * syncs for the same reason. Afterwards {@code cleared} is true and nothing is ever assigned
     * again.
     *
     * @return the projects whose runner delivery was cleared, or null if there was nothing to do
     */
    public static List<String> clearAssignedDeliveries() {
        Handler h = handler;
        if (h == null || h.findResource(NAME).isEmpty()) {
            return null;
        }
        GitRunnerRecord r = get();
        if (r.cleared) {
            return null;
        }
        List<String> had = new ArrayList<>(r.modes.keySet());
        r.modes = new JsonObject();
        r.cleared = true;
        r.save();
        return had;
    }

    /** Generates a new token, stores it encrypted, and returns the plaintext for one-time display. */
    public String generateToken() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        setToken(plaintext);
        return plaintext;
    }

    /** Sets the token from plaintext. A blank value clears it, which closes the route. */
    public void setToken(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            this.token = null;
            return;
        }
        try (Plaintext pt = Plaintext.fromString(plaintext, StandardCharsets.UTF_8)) {
            JsonElement ciphertext =
                    GatewayHook.getContext().getSystemEncryptionService().encryptToJson(pt);
            this.token = SecretConfig.embedded(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Could not encrypt the runner token", e);
        }
    }

    /**
     * The token's bytes, or null when unset or undecryptable.
     *
     * <p>Bytes rather than a String so the caller can zero them; a secret sitting in an immutable
     * String stays in the heap until the collector happens to reach it.
     */
    public byte[] tokenBytes() {
        if (token == null) {
            return null;
        }
        try {
            Secret<?> s = Secret.create(GatewayHook.getContext(), token);
            Plaintext pt = s.getPlaintext();
            try {
                return pt.getAsString(StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            } finally {
                pt.clear();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void save() {
        try {
            Config c = new Config(enabled, token, getIgnitionUser(), modes.toString(), optIn,
                    cleared);
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
