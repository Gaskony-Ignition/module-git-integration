package com.operametrix.ignition.git.records;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.SingletonResourceHandler;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

/**
 * Gateway-level remote for the data-directory config repo (singleton, fixed id). Holds the
 * remote URI, target branch, and a credential FK into the user-level SSH key / HTTPS credential
 * records — same FK model as the per-project remotes. Pushing is always a manual action.
 */
public class GitConfigRemoteRecord {

    public record Config(long id, String uri, String branch, long sshKeyId, long httpsCredentialId) {}

    public static final ResourceType TYPE =
            new ResourceType(GitProjectsConfigRecord.MODULE_ID, "git-config-remote");

    public static final ResourceTypeMeta<Config> META =
            ModeStore.singleton(Config.class, TYPE, "Git Config Remote",
                    new Config(1L, "", "", 0L, 0L));

    public static final class Handler extends SingletonResourceHandler<Config> {
        public Handler(GatewayContext context) {
            super(context, META);
        }
    }

    private static volatile Handler handler;

    public static void setHandler(Handler h) {
        handler = h;
    }

    private static final long SINGLETON_ID = 1L;
    /** The name it was stored under before 4.5.0 made it a singleton ({@code SingletonMigration}). */
    public static final String LEGACY_NAME = String.valueOf(SINGLETON_ID);

    private String uri;
    private String branch;
    private long sshKeyId;
    private long httpsCredentialId;

    public GitConfigRemoteRecord() {
    }

    private GitConfigRemoteRecord(Config c) {
        this.uri = c.uri();
        this.branch = c.branch();
        this.sshKeyId = c.sshKeyId();
        this.httpsCredentialId = c.httpsCredentialId();
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public long getSshKeyId() {
        return sshKeyId;
    }

    public void setSshKeyId(long sshKeyId) {
        this.sshKeyId = sshKeyId;
    }

    public long getHttpsCredentialId() {
        return httpsCredentialId;
    }

    public void setHttpsCredentialId(long httpsCredentialId) {
        this.httpsCredentialId = httpsCredentialId;
    }

    public void save() {
        try {
            Config c = new Config(SINGLETON_ID, uri, branch, sshKeyId, httpsCredentialId);
            ModeStore.saveSingleton(META, c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Blanks the remote where it is defined: a singleton stays, a blank URI reads as none. */
    public void delete() {
        try {
            ModeStore.saveSingleton(META, new Config(SINGLETON_ID, "", "", 0L, 0L));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return the configured remote, or null when none is set. A blank URI is none: as a singleton
     *         the record always exists in core (the platform writes the blank default at startup).
     */
    public static GitConfigRemoteRecord get() {
        return ModeStore.findSingleton(META)
                .filter(d -> d.config().uri() != null && !d.config().uri().isBlank())
                .map(d -> new GitConfigRemoteRecord(d.config()))
                .orElse(null);
    }

    /** Where this record's effective value comes from: core, a mode, or null when unsaved. */
    public String definedIn() {
        return ModeStore.definedIn(TYPE, null);
    }

}
