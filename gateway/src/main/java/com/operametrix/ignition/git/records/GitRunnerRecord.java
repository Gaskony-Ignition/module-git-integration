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
import java.util.Base64;

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

    /** Labels a generated workflow targets, and that the generated config.sh registers with. */
    public static final String DEFAULT_LABELS = "self-hosted,ignition";

    /** How a runner delivers to a project: a release zip replacing it, or a pull of its branch. */
    public static final String MODE_RELEASE = "release";
    public static final String MODE_REPO = "repo";

    /**
     * {@code ignitionUser} and {@code modes} arrived in 3.1.0, so a record saved earlier decodes
     * them as null. {@code modes} is a JSON object of project name to mode.
     */
    public record Config(boolean enabled, SecretConfig token, String gatewayUrl, String labels,
                         String ignitionUser, String modes) {}

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
    private String gatewayUrl = "";
    private String labels = DEFAULT_LABELS;
    private String ignitionUser = "";
    private JsonObject modes = new JsonObject();

    public GitRunnerRecord() {
    }

    private GitRunnerRecord(Config c) {
        this.enabled = c.enabled();
        this.token = c.token();
        this.gatewayUrl = c.gatewayUrl() == null ? "" : c.gatewayUrl();
        this.labels = c.labels() == null || c.labels().isBlank() ? DEFAULT_LABELS : c.labels();
        this.ignitionUser = c.ignitionUser() == null ? "" : c.ignitionUser();
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
     * The gateway address the runner should call, as the RUNNER sees it.
     *
     * <p>Not derivable here: the gateway knows the address a browser reached it on, which on a
     * containerised or reverse-proxied install is routinely not the one a machine on the plant
     * network would use. So it is asked for, and the generated snippets carry whatever is set.
     */
    public String getGatewayUrl() {
        return gatewayUrl == null ? "" : gatewayUrl;
    }

    public void setGatewayUrl(String v) {
        this.gatewayUrl = v == null ? "" : v.trim().replaceAll("/+$", "");
    }

    public String getLabels() {
        return labels == null || labels.isBlank() ? DEFAULT_LABELS : labels;
    }

    public void setLabels(String v) {
        this.labels = v == null || v.isBlank() ? DEFAULT_LABELS : v.trim();
    }

    /**
     * A detached copy carrying a different address and labels, for generating the setup commands
     * from values that have been typed but not yet saved.
     *
     * <p>Safe precisely because neither field is used when a runner actually calls: the gateway
     * authenticates by token and acts on the request, and never reads its own configured address
     * or labels. They exist only to fill in the commands, so previewing them changes nothing.
     * A blank override keeps the saved value. The copy is never saved.
     */
    public GitRunnerRecord withOverrides(String gatewayUrl, String labels) {
        if ((gatewayUrl == null || gatewayUrl.isBlank()) && (labels == null || labels.isBlank())) {
            return this;
        }
        GitRunnerRecord copy = new GitRunnerRecord();
        copy.enabled = this.enabled;
        copy.token = this.token;
        copy.ignitionUser = this.ignitionUser;
        copy.modes = this.modes;
        copy.setGatewayUrl(gatewayUrl == null || gatewayUrl.isBlank() ? this.gatewayUrl : gatewayUrl);
        copy.setLabels(labels == null || labels.isBlank() ? this.labels : labels);
        return copy;
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

    /** The project's delivery mode; a project nobody has chosen for receives releases. */
    public String getMode(String project) {
        String m = chosenMode(project);
        return m == null ? MODE_RELEASE : m;
    }

    /**
     * The mode somebody actually chose for this project, or null if nobody has.
     *
     * <p>The routes enforce the choice — a release upload to a project set to Repo updates is
     * refused rather than silently doing the other thing — and this is what keeps that from
     * breaking an install upgraded from a version with no modes at all. Unset means "either",
     * which is exactly how those gateways behave today; enforcement begins when a choice is made.
     */
    public String chosenMode(String project) {
        if (project == null || !modes.has(project)) {
            return null;
        }
        String m = modes.get(project).getAsString();
        return MODE_REPO.equals(m) ? MODE_REPO : MODE_RELEASE;
    }

    public void setMode(String project, String mode) {
        if (project == null || project.isBlank()) {
            return;
        }
        modes.addProperty(project, MODE_REPO.equals(mode) ? MODE_REPO : MODE_RELEASE);
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
            Config c = new Config(enabled, token, getGatewayUrl(), getLabels(), getIgnitionUser(),
                    modes.toString());
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
