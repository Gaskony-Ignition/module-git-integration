package com.operametrix.ignition.git;

import com.operametrix.ignition.git.automation.GitEvent;
import com.operametrix.ignition.git.automation.ReleaseReceiver;
import com.operametrix.ignition.git.automation.RunnerAuth;
import com.operametrix.ignition.git.automation.RunnerTrigger;
import com.operametrix.ignition.git.automation.GitEvents;
import com.operametrix.ignition.git.automation.SyncScheduler;
import com.operametrix.ignition.git.records.GitConfigRemoteRecord;
import com.operametrix.ignition.git.records.GitProjectsConfigRecord;
import com.operametrix.ignition.git.records.GitRunnerRecord;
import com.operametrix.ignition.git.records.GitSyncRecord;
import com.operametrix.ignition.git.records.GitRemoteCredentialsRecord;
import com.operametrix.ignition.git.records.GitReposUsersRecord;
import com.operametrix.ignition.git.records.GitUserHttpsCredentialRecord;
import com.operametrix.ignition.git.records.GitUserSshKeyRecord;
import com.operametrix.ignition.git.records.legacy.GitLegacyImporter;
import com.operametrix.ignition.git.records.legacy.RetiredResourceCleanup;
import com.operametrix.ignition.git.managers.ConfigAutoCommitter;
import com.operametrix.ignition.git.managers.CredentialCheck;
import com.operametrix.ignition.git.managers.DataDirGitManager;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMetaRegistry;
import com.inductiveautomation.ignition.gateway.secrets.ManagedSecretProvider;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.rpc.GatewayRpcImplementation;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.operametrix.ignition.git.managers.GitProjectManager;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GatewayHook extends AbstractGatewayModuleHook {
    static public String MODULE_NAME = "Git";

    /** Alias for both the mounted JS bundle (/res/<alias>/…) and the data routes (/data/<alias>/…). */
    private static final String MOUNT_ALIAS = "git-config";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private GatewayScriptModule scriptModule;
    private static GatewayContext context;

    private GitProjectsConfigRecord.Handler projectHandler;
    private GitReposUsersRecord.Handler repoUserHandler;
    private GitUserSshKeyRecord.Handler sshKeyHandler;
    private GitUserHttpsCredentialRecord.Handler httpsCredHandler;
    private GitRemoteCredentialsRecord.Handler remoteCredHandler;
    private GitConfigRemoteRecord.Handler configRemoteHandler;
    private GitSyncRecord.Handler syncHandler;
    private GitRunnerRecord.Handler runnerHandler;
    private ConfigAutoCommitter autoCommitter;

    /** Gateway context, available after {@link #setup(GatewayContext)} has run. */
    public static GatewayContext getContext() {
        return context;
    }

    @Override
    public void setup(GatewayContext gatewayContext) {
        context = gatewayContext;

        // Register the 8.3 resource/config types (replaces SimpleORM schema bootstrap).
        ResourceTypeMetaRegistry registry = context.getConfigurationManager().getResourceTypeMetaRegistry();
        registry.register(GitProjectsConfigRecord.META);
        registry.register(GitReposUsersRecord.META);
        registry.register(GitUserSshKeyRecord.META);
        registry.register(GitUserHttpsCredentialRecord.META);
        registry.register(GitRemoteCredentialsRecord.META);
        registry.register(GitConfigRemoteRecord.META);
        registry.register(GitSyncRecord.META);
        registry.register(GitRunnerRecord.META);
        // Metas for types this module no longer registers a live handler for, so the retired
        // resources under them can be found and deleted once in startup() — see the class javadoc.
        RetiredResourceCleanup.registerMetas(registry);

        // Create the resource handlers (DAOs) and publish them to the record façades.
        projectHandler = new GitProjectsConfigRecord.Handler(context);
        repoUserHandler = new GitReposUsersRecord.Handler(context);
        sshKeyHandler = new GitUserSshKeyRecord.Handler(context);
        httpsCredHandler = new GitUserHttpsCredentialRecord.Handler(context);
        remoteCredHandler = new GitRemoteCredentialsRecord.Handler(context);
        configRemoteHandler = new GitConfigRemoteRecord.Handler(context);
        syncHandler = new GitSyncRecord.Handler(context);
        runnerHandler = new GitRunnerRecord.Handler(context);

        GitProjectsConfigRecord.setHandler(projectHandler);
        GitReposUsersRecord.setHandler(repoUserHandler);
        GitUserSshKeyRecord.setHandler(sshKeyHandler);
        GitUserHttpsCredentialRecord.setHandler(httpsCredHandler);
        GitRemoteCredentialsRecord.setHandler(remoteCredHandler);
        GitConfigRemoteRecord.setHandler(configRemoteHandler);
        GitSyncRecord.setHandler(syncHandler);
        GitRunnerRecord.setHandler(runnerHandler);

        scriptModule = new GatewayScriptModule(context);

        // Register the React gateway web page (config-as-code history/restore) under Platform → System,
        // alongside the Scan File System / Modes actions. NOTE: "system" is best-effort to merge into
        // the platform's built-in System category; verify the exact key on a live gateway and adjust
        // (fall back to a dedicated category under Platform if it doesn't merge).
        SystemJsModule jsModule = new SystemJsModule(
                "com.operametrix.ignition.git.GitConfig",
                "/res/" + MOUNT_ALIAS + "/gitConfig.js");
        context.getWebResourceManager().getNavigationModel().getPlatform()
                .addCategory("system", cat -> cat
                        .label("System")
                        .requiredPermission(PermissionType.WRITE)
                        .addPage("Versioning", page -> page
                                .position(50)
                                .mount("/config-versioning", "GitConfigPage", jsModule)));

        logger.info("setup()");
    }

    @Override
    public void startup(LicenseState licenseState) {
        projectHandler.startup();
        repoUserHandler.startup();
        sshKeyHandler.startup();
        httpsCredHandler.startup();
        remoteCredHandler.startup();
        configRemoteHandler.startup();
        syncHandler.startup();
        runnerHandler.startup();

        // 3.5.0 handed Release to every project folder on a gateway whose runner was on. A stored
        // delivery is indistinguishable from a chosen one, so 3.7.0 forgets them all once and never
        // assigns another.
        try {
            clearAssignedDeliveries();
        } catch (Exception e) {
            logger.error("Could not clear the deliveries an earlier version assigned; check each"
                    + " project's delivery on the Projects tab.", e);
        }

        // 3.8.0 lets .gitignore alone decide what the config repository versions. Paths the old
        // hard-coded scope skipped are added to the ignore file once, so the wider staging commits
        // nothing new.
        try {
            List<String> added = DataDirGitManager.widenScopeOnce();
            if (!added.isEmpty()) {
                logger.info("Added {} data-root rules to .gitignore so widening the config"
                        + " repository commits nothing new.", added.size());
                GitEvents.fire(GitEvent.of("ignore").config()
                        .message("Data-root paths added to .gitignore — the repository now"
                                + " versions everything it does not exclude")
                        .files(added).success());
            }
        } catch (Exception e) {
            logger.error("Could not widen the config repository's scope; check the Git Ignore tab.", e);
        }

        // Scheduled sync. Inert until a project has a sync record configured, so starting it
        // unconditionally costs one idle thread and keeps the wiring in one place.
        SyncScheduler.start();
        CredentialCheck.start();

        // One-time migration of any legacy SimpleORM rows from a pre-8.3 install.
        try {
            GitLegacyImporter.migrateIfNeeded(context);
        } catch (Exception e) {
            logger.error("Legacy git config migration failed; existing credentials may need to be re-entered.", e);
        }

        // One-time removal of resources left behind by features retired in earlier versions
        // (Event delivery/Outbound triggers in 3.0.0, the webhook receiver in 2.14.0). Runs after
        // the live handlers have started and before ConfigAutoCommitter attaches, so the deletion
        // lands inside the same startup window commitLeftovers() sweeps into one config commit.
        RetiredResourceCleanup.deleteRetired(context);

        // Auto-commit gateway config changes (no-op until the data-dir repo is initialized).
        // Manager-level listener — see ConfigAutoCommitter's javadoc for why not getConfigCollection().
        autoCommitter = new ConfigAutoCommitter();
        context.getConfigurationManager().addListener(autoCommitter);
        // Changes made while the gateway/module was offline can't reach the listener — sweep them.
        autoCommitter.commitLeftovers();

        logger.info("startup()");
    }

    @Override
    public void shutdown() {
        SyncScheduler.shutdown();
        CredentialCheck.shutdown();
        if (runnerHandler != null) runnerHandler.shutdown();
        if (syncHandler != null) syncHandler.shutdown();
        if (autoCommitter != null) {
            context.getConfigurationManager().removeListener(autoCommitter);
            autoCommitter.shutdown();
        }
        if (configRemoteHandler != null) configRemoteHandler.shutdown();
        if (remoteCredHandler != null) remoteCredHandler.shutdown();
        if (httpsCredHandler != null) httpsCredHandler.shutdown();
        if (sshKeyHandler != null) sshKeyHandler.shutdown();
        if (repoUserHandler != null) repoUserHandler.shutdown();
        if (projectHandler != null) projectHandler.shutdown();
        logger.info("shutdown()");
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    @Override
    public boolean isMakerEditionCompatible() {
        return true;
    }

    @Override
    public Optional<GatewayRpcImplementation> getRpcImplementation() {
        return Optional.of(GatewayRpcImplementation.of(GitScriptInterface.SERIALIZER, scriptModule));
    }

    // ── Gateway web page: mounted React bundle + REST routes ───────────────────────────────────

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of(MOUNT_ALIAS);
    }

    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    /**
     * REST routes backing the Versioning page. Mounted under {@code /data/git-config/…}.
     * Reads require READ, mutations require WRITE (gateway config-admin). All return JSON.
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        routes.newRoute("/status").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleStatus).mount();

        // CSRF token for the current web UI session. GET is CSRF-exempt; the page echoes this value
        // in the X-CSRF-Token header on mutating (POST) routes, which the gateway's web-session
        // access control requires.
        routes.newRoute("/csrf").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleCsrf).mount();

        routes.newRoute("/history").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ)
                .handler(this::handleHistory).mount();

        routes.newRoute("/commit-files").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ)
                .handler(this::handleCommitFiles).mount();

        routes.newRoute("/file-diff").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ)
                .handler(this::handleFileDiff).mount();

        routes.newRoute("/remote").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleGetRemote).mount();

        routes.newRoute("/credentials").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleGetCredentials).mount();

        routes.newRoute("/secret-providers").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleGetSecretProviders).mount();

        routes.newRoute("/remote").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSaveRemote).mount();

        routes.newRoute("/remote-remove").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleRemoveRemote).mount();

        routes.newRoute("/remote-test").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleTestRemote).mount();

        routes.newRoute("/push").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handlePush).mount();

        routes.newRoute("/credentials").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleAddCredential).mount();

        routes.newRoute("/credential-remove").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleRemoveCredential).mount();

        routes.newRoute("/credential-check").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleCheckCredential).mount();

        routes.newRoute("/projects").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleProjects).mount();

        routes.newRoute("/project-init").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleProjectInit).mount();

        routes.newRoute("/project-remote").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleProjectRemote).mount();

        routes.newRoute("/project-images").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleProjectImages).mount();

        routes.newRoute("/project-snapshot-images").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSnapshotImages).mount();


        routes.newRoute("/project-credential").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleProjectCredential).mount();

        // The Logs tab, and each project's delivery: how, if at all, changes reach it.
        routes.newRoute("/events").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleGetEvents).mount();

        routes.newRoute("/events-clear").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleClearEvents).mount();

        routes.newRoute("/delivery").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSaveDelivery).mount();

        routes.newRoute("/sync-now").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSyncNow).mount();

        routes.newRoute("/restore").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleRestore).mount();

        routes.newRoute("/init").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleInit).mount();

        routes.newRoute("/update-from-remote").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleUpdateFromRemote).mount();

        routes.newRoute("/deinit").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleDeinit).mount();

        // Runner access (Credentials tab), and the two routes the runner itself calls.
        routes.newRoute("/runner").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleGetRunner).mount();

        routes.newRoute("/runner").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSaveRunner).mount();

        // OPEN_ROUTE on purpose and uniquely: a workflow step has no gateway session and no way
        // to obtain the X-CSRF-Token that requirePermission's strategy demands, so the route
        // authenticates itself by bearer token. It stays a 404 until one is generated.
        routes.newRoute("/runner-sync").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .accessControl(AccessControlStrategy.OPEN_ROUTE).nocache()
                .handler(RunnerTrigger::handle).mount();

        // Same gate as /runner-sync. The body is a project export zip, streamed to disk.
        routes.newRoute("/runner-release").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .accessControl(AccessControlStrategy.OPEN_ROUTE).nocache()
                .handler(ReleaseReceiver::handle).mount();

        // --- Excluded files (.gitignore management) ---
        routes.newRoute("/tree").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleTree).mount();

        routes.newRoute("/tree-search").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleTreeSearch).mount();

        routes.newRoute("/ignore").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleGetIgnore).mount();

        routes.newRoute("/ignore").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSaveIgnore).mount();
    }

    /**
     * The installed module's version, as the release carries it. The build appends a timestamp so
     * every build counts as an upgrade, and the platform renders that as
     * {@code 3.7.0 (b2026092110)} — neither the build number nor a fourth segment means anything to
     * a reader, so both are trimmed off.
     */
    private String moduleVersion() {
        try {
            String full = context.getModuleManager().getActiveModules().stream()
                    .filter(m -> GitRunnerRecord.MODULE_ID.equals(m.getId()))
                    .findFirst()
                    .map(m -> m.getVersion().toString())
                    .orElse("");
            int space = full.indexOf(' ');
            String number = space > 0 ? full.substring(0, space) : full;
            String[] parts = number.split("\\.");
            return parts.length > 3
                    ? String.join(".", parts[0], parts[1], parts[2]) : number;
        } catch (Exception e) {
            logger.debug("Could not read the module version.", e);
            return "";
        }
    }

    /**
     * The one-time 3.7.0 clear: every runner mode forgotten and every scheduled sync disabled, so
     * no project delivers until somebody picks its delivery. Reported on the Logs tab as well as
     * the gateway log — a gateway that quietly stopped deploying would be worse than the problem.
     */
    private void clearAssignedDeliveries() {
        List<String> runnerModes = GitRunnerRecord.clearAssignedDeliveries();
        if (runnerModes == null) {
            return;
        }
        List<String> syncs = new ArrayList<>();
        for (GitSyncRecord sync : GitSyncRecord.listEnabled()) {
            sync.setEnabled(false);
            sync.save();
            syncs.add(sync.getProject());
        }
        List<String> all = new ArrayList<>(runnerModes);
        all.addAll(syncs);
        if (all.isEmpty()) {
            return;
        }
        logger.info("Cleared the deliveries an earlier version assigned ({}). Each project is Off"
                + " until its delivery is chosen on the Projects tab.", String.join(", ", all));
        GitEvents.fire(GitEvent.of("delivery").config()
                .message("Deliveries an earlier version assigned were cleared — choose each"
                        + " project's delivery on the Projects tab")
                .files(all)
                .success());
    }

    private Object handleStatus(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject o = new JsonObject();
            boolean init = DataDirGitManager.isInitialized();
            o.addProperty("initialized", init);
            // Every tab is drawn after this call, so it is what puts the module's version on the
            // page. Nothing else the UI fetches is both unconditional and uncached.
            o.addProperty("version", moduleVersion());
            JsonArray changes = new JsonArray();
            boolean dirty = false;
            if (init) {
                List<DataDirGitManager.ConfigChange> list = DataDirGitManager.getStatus();
                dirty = !list.isEmpty();
                for (DataDirGitManager.ConfigChange c : list) {
                    JsonObject co = new JsonObject();
                    co.addProperty("path", c.path());
                    co.addProperty("type", c.type());
                    changes.add(co);
                }
            }
            o.addProperty("dirty", dirty);
            o.add("changes", changes);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleCsrf(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject o = new JsonObject();
            WebUiSession.find(req).ifPresent(s -> o.addProperty("token", s.getCsrfToken()));
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleHistory(RequestContext req, HttpServletResponse resp) {
        try {
            int skip = parseInt(req.getParameter("skip"), 0);
            int limit = parseInt(req.getParameter("limit"), 25);
            List<String[]> commits = DataDirGitManager.history(skip, limit);
            boolean remoteConfigured = GitConfigRemoteRecord.get() != null;
            String[] pointers = DataDirGitManager.pointerHashes();
            JsonArray arr = new JsonArray();
            for (String[] c : commits) {
                JsonObject co = new JsonObject();
                co.addProperty("hash", c[0]);
                co.addProperty("shortHash", c[1]);
                co.addProperty("author", c[2]);
                co.addProperty("date", c[3]);
                co.addProperty("message", c[4]);
                co.addProperty("refs", c[5]);
                arr.add(co);
            }
            JsonObject o = new JsonObject();
            o.add("commits", arr);
            o.addProperty("remoteConfigured", remoteConfigured);
            // Ref pointers: which single commit the local and remote branch tips point at.
            o.addProperty("localHead", pointers[0]);
            o.addProperty("remoteHead", pointers[1]);
            o.addProperty("hasMore", commits.size() == limit);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleCommitFiles(RequestContext req, HttpServletResponse resp) {
        try {
            String hash = req.getParameter("hash");
            JsonArray arr = new JsonArray();
            for (String entry : DataDirGitManager.commitFiles(hash)) {
                int idx = entry.indexOf(':');
                JsonObject fo = new JsonObject();
                fo.addProperty("changeType", idx >= 0 ? entry.substring(0, idx) : "");
                fo.addProperty("path", idx >= 0 ? entry.substring(idx + 1) : entry);
                arr.add(fo);
            }
            JsonObject o = new JsonObject();
            o.add("files", arr);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleFileDiff(RequestContext req, HttpServletResponse resp) {
        try {
            String hash = req.getParameter("hash");
            String path = req.getParameter("path");
            List<String> diff = DataDirGitManager.fileDiff(hash, path);
            JsonObject o = new JsonObject();
            o.addProperty("old", diff.size() > 0 ? diff.get(0) : "");
            o.addProperty("new", diff.size() > 1 ? diff.get(1) : "");
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleGetRemote(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject o = new JsonObject();
            GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
            o.addProperty("configured", remote != null);
            if (remote != null) {
                o.addProperty("uri", remote.getUri());
                o.addProperty("branch", remote.getBranch());
                // Secret prefill: mode + (referenced) provider/secret + (https) username. The
                // embedded secret itself is never returned — the drawer leaves it blank to keep it.
                SecretConfig secret = null;
                if (remote.getSshKeyId() > 0) {
                    GitUserSshKeyRecord key = GitUserSshKeyRecord.findById(remote.getSshKeyId());
                    if (key != null) {
                        secret = key.getSecret();
                    }
                } else if (remote.getHttpsCredentialId() > 0) {
                    GitUserHttpsCredentialRecord cred =
                            GitUserHttpsCredentialRecord.findById(remote.getHttpsCredentialId());
                    if (cred != null) {
                        secret = cred.getSecret();
                        o.addProperty("username", cred.getUserName());
                    }
                }
                boolean referenced = secret != null && secret.isReferenced();
                o.addProperty("secretMode", referenced ? "reference" : "inline");
                if (referenced) {
                    o.addProperty("providerName", secret.getAsReferenced().getProviderName());
                    o.addProperty("secretName", secret.getAsReferenced().getSecretName());
                }
                // Unsynced-commit count for the header sync indicator.
                o.addProperty("ahead", DataDirGitManager.aheadCount());
                long time = DataDirGitManager.getLastPushTime();
                if (time > 0) {
                    JsonObject lastPush = new JsonObject();
                    lastPush.addProperty("time", time);
                    String err = DataDirGitManager.getLastPushError();
                    lastPush.addProperty("ok", err == null);
                    if (err != null) {
                        lastPush.addProperty("error", err);
                    }
                    o.add("lastPush", lastPush);
                }
            }
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleGetCredentials(RequestContext req, HttpServletResponse resp) {
        try {
            JsonArray arr = new JsonArray();
            for (GitUserSshKeyRecord key : GitUserSshKeyRecord.listAll()) {
                JsonObject c = new JsonObject();
                c.addProperty("id", key.getId());
                c.addProperty("type", "SSH");
                c.addProperty("label", key.getKeyName());
                c.addProperty("name", key.getKeyName());
                c.add("check", checkJson(CredentialCheck.get("SSH", key.getId())));
                arr.add(c);
            }
            for (GitUserHttpsCredentialRecord cred : GitUserHttpsCredentialRecord.listAll()) {
                JsonObject c = new JsonObject();
                c.addProperty("id", cred.getId());
                c.addProperty("type", "HTTPS");
                c.addProperty("label", cred.getHostPattern() + " — " + cred.getUserName());
                c.addProperty("host", cred.getHostPattern());
                c.addProperty("username", cred.getUserName());
                c.add("check", checkJson(CredentialCheck.get("HTTPS", cred.getId())));
                arr.add(c);
            }
            JsonObject o = new JsonObject();
            o.add("credentials", arr);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** A credential's last check, or JSON null before the first one finishes. */
    private static JsonElement checkJson(CredentialCheck.Result r) {
        if (r == null) {
            return com.inductiveautomation.ignition.common.gson.JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("checkedAt", r.checkedAt());
        o.addProperty("account", r.account());
        o.addProperty("expires", r.expires());
        o.addProperty("rejected", r.rejected());
        o.addProperty("error", r.error());
        if (r.scope() != null) {
            JsonObject scope = new JsonObject();
            scope.addProperty("kind", r.scope().kind());
            scope.addProperty("summary", r.scope().summary());
            JsonArray repos = new JsonArray();
            r.scope().repos().forEach(repos::add);
            scope.add("repos", repos);
            o.add("scope", scope);
        }
        JsonArray reach = new JsonArray();
        for (CredentialCheck.Reach x : r.reach()) {
            JsonObject t = new JsonObject();
            t.addProperty("target", x.target());
            t.addProperty("read", x.read());
            t.addProperty("push", x.push());
            t.addProperty("error", x.error());
            reach.add(t);
        }
        o.add("reach", reach);
        return o;
    }

    private Object handleCheckCredential(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String type = optString(body, "type");
            if (!"SSH".equalsIgnoreCase(type) && !"HTTPS".equalsIgnoreCase(type)) {
                throw new RuntimeException("Unknown credential type: " + type);
            }
            if (CredentialCheck.check(type, optLong(body, "id")) == null) {
                throw new RuntimeException("No such credential.");
            }
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleSaveRemote(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String uri = optString(body, "uri");
            if (uri == null || uri.isBlank()) {
                throw new RuntimeException("A repository URI is required.");
            }
            String branch = optString(body, "branch");
            if (branch == null || branch.isBlank()) {
                branch = "main";
            }
            // Create/update the config remote's own credential in place from the inline secret
            // (no user-entered name; auto-derived), then link it. Keeps a single dedicated record.
            boolean ssh = !uri.trim().toLowerCase().startsWith("http");
            boolean reference = "reference".equalsIgnoreCase(optString(body, "mode"));
            GitConfigRemoteRecord existing = GitConfigRemoteRecord.get();
            long sshKeyId = 0, httpsCredentialId = 0;
            if (ssh) {
                GitUserSshKeyRecord rec = existing != null && existing.getSshKeyId() > 0
                        ? GitUserSshKeyRecord.findById(existing.getSshKeyId()) : null;
                if (rec == null) {
                    rec = new GitUserSshKeyRecord();
                }
                rec.setIgnitionUser(req.getActor());
                rec.setKeyName("Config repository (" + hostOf(uri) + ")");
                if (reference) {
                    rec.setSSHKeySecret(referencedSecret(body));
                } else {
                    String key = optString(body, "key");
                    if (key != null && !key.isBlank()) {
                        rec.setSSHKey(key);
                    } else if (!rec.hasSecret()) {
                        throw new RuntimeException("A private key is required.");
                    }
                }
                rec.save();
                sshKeyId = rec.getId();
            } else {
                GitUserHttpsCredentialRecord rec = existing != null && existing.getHttpsCredentialId() > 0
                        ? GitUserHttpsCredentialRecord.findById(existing.getHttpsCredentialId()) : null;
                if (rec == null) {
                    rec = new GitUserHttpsCredentialRecord();
                }
                rec.setIgnitionUser(req.getActor());
                rec.setHostPattern(hostOf(uri));
                String username = optString(body, "username");
                rec.setUserName(username == null ? "" : username.trim());
                if (reference) {
                    rec.setPasswordSecret(referencedSecret(body));
                } else {
                    String password = optString(body, "password");
                    if (password != null && !password.isBlank()) {
                        rec.setPassword(password);
                    } else if (!rec.hasSecret()) {
                        throw new RuntimeException("A password/token is required.");
                    }
                }
                rec.save();
                httpsCredentialId = rec.getId();
            }
            DataDirGitManager.saveRemote(uri, branch, sshKeyId, httpsCredentialId);
            // Commit the new remote/credential resources synchronously so the History table
            // reflects them the moment the call returns, rather than after the async auto-commit's
            // quiesce window and the next poll. No-op if the tree is already clean; the later
            // auto-commit for the same change then finds nothing to do.
            DataDirGitManager.commitAllIfDirty("Configure config repository remote");
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** Builds a referenced {@link SecretConfig} from the request's providerName/secretName. */
    private static SecretConfig referencedSecret(JsonObject body) {
        String providerName = optString(body, "providerName");
        String secretName = optString(body, "secretName");
        if (providerName == null || providerName.isBlank() || secretName == null || secretName.isBlank()) {
            throw new RuntimeException("A provider and secret name are required to reference a stored secret.");
        }
        return SecretConfig.referenced(providerName.trim(), secretName.trim());
    }

    /** Best-effort host extracted from a git URI, for auto-naming the credential. */
    private static String hostOf(String uri) {
        if (uri == null) {
            return "remote";
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?:://|@)([^/:]+)").matcher(uri.trim());
        return m.find() ? m.group(1) : "remote";
    }

    private Object handleRemoveRemote(RequestContext req, HttpServletResponse resp) {
        try {
            DataDirGitManager.removeRemote();
            DataDirGitManager.commitAllIfDirty("Remove config repository remote");
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleTestRemote(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String uri = optString(body, "uri");
            boolean ssh = uri != null && !uri.trim().toLowerCase().startsWith("http");
            boolean reference = "reference".equalsIgnoreCase(optString(body, "mode"));
            String username = optString(body, "username");
            if (reference) {
                // Resolve the referenced secret to plaintext for the test.
                SecretConfig cfg = referencedSecret(body);
                Secret<?> secret = Secret.create(context, cfg);
                Plaintext pt = secret.getPlaintext();
                try {
                    String plain = pt.getAsString(java.nio.charset.StandardCharsets.UTF_8);
                    DataDirGitManager.testRemoteRaw(uri, ssh ? plain : null, username, ssh ? null : plain);
                } finally {
                    pt.clear();
                }
            } else {
                String key = optString(body, "key");
                String password = optString(body, "password");
                String secretVal = ssh ? key : password;
                if (secretVal == null || secretVal.isBlank()) {
                    // Editing without re-entering the embedded secret: test the saved credential.
                    GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
                    if (remote == null) {
                        throw new RuntimeException("Enter a secret to test, or save the remote first.");
                    }
                    DataDirGitManager.testRemote(uri, remote.getSshKeyId(), remote.getHttpsCredentialId());
                } else {
                    DataDirGitManager.testRemoteRaw(uri, ssh ? key : null, username, ssh ? null : password);
                }
            }
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handlePush(RequestContext req, HttpServletResponse resp) {
        try {
            DataDirGitManager.push();
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleAddCredential(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String type = optString(body, "type");
            // mode ∈ inline (type the secret, encrypted at rest) | reference (point at a
            // Secret Provider secret). Defaults to inline so older callers keep working.
            String mode = optString(body, "mode");
            boolean reference = "reference".equalsIgnoreCase(mode);
            SecretConfig referenced = null;
            if (reference) {
                String providerName = optString(body, "providerName");
                String secretName = optString(body, "secretName");
                if (providerName == null || providerName.isBlank()
                        || secretName == null || secretName.isBlank()) {
                    throw new RuntimeException("A provider and secret name are required to reference a stored secret.");
                }
                referenced = SecretConfig.referenced(providerName.trim(), secretName.trim());
            }
            // With an id this edits that credential in place, so the projects using it stay linked
            // when its token is replaced. A blank inline secret keeps the stored one.
            long editId = optLong(body, "id");
            String actor = req.getActor();
            JsonObject o = new JsonObject();
            if ("SSH".equalsIgnoreCase(type)) {
                String name = optString(body, "name");
                if (name == null || name.isBlank()) {
                    throw new RuntimeException("A key name is required.");
                }
                GitUserSshKeyRecord record = editId > 0
                        ? GitUserSshKeyRecord.findByIdAndUser(editId, actor) : new GitUserSshKeyRecord();
                if (record == null) {
                    throw new RuntimeException("No such SSH key for this user.");
                }
                record.setIgnitionUser(actor);
                record.setKeyName(name.trim());
                if (reference) {
                    record.setSSHKeySecret(referenced);
                } else {
                    String key = optString(body, "key");
                    if (key != null && !key.isBlank()) {
                        record.setSSHKey(key);
                    } else if (editId == 0) {
                        throw new RuntimeException("The private key is required.");
                    }
                }
                record.save();
                o.addProperty("id", record.getId());
            } else if ("HTTPS".equalsIgnoreCase(type)) {
                String host = optString(body, "host");
                String username = optString(body, "username");
                if (host == null || host.isBlank()) {
                    throw new RuntimeException("A host label is required.");
                }
                GitUserHttpsCredentialRecord record = editId > 0
                        ? GitUserHttpsCredentialRecord.findByIdAndUser(editId, actor)
                        : new GitUserHttpsCredentialRecord();
                if (record == null) {
                    throw new RuntimeException("No such credential for this user.");
                }
                record.setIgnitionUser(actor);
                record.setHostPattern(host.trim());
                record.setUserName(username == null ? "" : username.trim());
                if (reference) {
                    record.setPasswordSecret(referenced);
                } else {
                    String password = optString(body, "password");
                    if (password != null && !password.isBlank()) {
                        record.setPassword(password);
                    } else if (editId == 0) {
                        throw new RuntimeException("The password/token is required.");
                    }
                }
                record.save();
                o.addProperty("id", record.getId());
            } else {
                throw new RuntimeException("Unknown credential type: " + type);
            }
            CredentialCheck.checkSoon(type, o.get("id").getAsLong());
            o.addProperty("ok", true);
            o.addProperty("type", type.toUpperCase());
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleGetSecretProviders(RequestContext req, HttpServletResponse resp) {
        try {
            JsonArray providers = new JsonArray();
            for (ManagedSecretProvider provider : context.getSecretProviderManager().getProviders()) {
                JsonObject po = new JsonObject();
                po.addProperty("name", provider.getResource().name());
                JsonArray secrets = new JsonArray();
                try {
                    // ManagedSecretProvider extends SecretProvider, so list() is available directly.
                    for (String s : provider.list()) {
                        secrets.add(s);
                    }
                } catch (Exception e) {
                    // A provider that can't list (or doesn't support it) shouldn't blank the picker.
                    po.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
                }
                po.add("secrets", secrets);
                providers.add(po);
            }
            JsonObject o = new JsonObject();
            o.add("providers", providers);
            return o.toString();
        } catch (Exception e) {
            // Degrade to inline-only rather than 500 the drawer.
            logger.warn("Could not list secret providers", e);
            JsonObject o = new JsonObject();
            o.add("providers", new JsonArray());
            return o.toString();
        }
    }

    private Object handleDeinit(RequestContext req, HttpServletResponse resp) {
        try {
            DataDirGitManager.deleteRepo();
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * A string field, or null when it is absent, null, or not a primitive.
     *
     * <p>The last case is deliberate: {@code getAsString()} on an object or array throws
     * {@code UnsupportedOperationException: JsonObject}, which surfaces as an opaque 500 naming
     * a Gson type rather than the field that was wrong. A front-end that sends the wrong shape
     * should get the field ignored and a validation message, not a stack trace.
     */
    private static String optString(JsonObject body, String key) {
        if (body == null || !body.has(key)) {
            return null;
        }
        JsonElement value = body.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static long optLong(JsonObject body, String key) {
        return body != null && body.has(key) && !body.get(key).isJsonNull()
                ? body.get(key).getAsLong() : 0L;
    }

    private static String ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o.toString();
    }

    private Object handleRestore(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String hash = body != null && body.has("hash") && !body.get("hash").isJsonNull()
                    ? body.get("hash").getAsString() : null;
            if (hash == null || hash.isEmpty()) {
                throw new RuntimeException("Missing commit hash.");
            }
            DataDirGitManager.restoreToCommit(hash, req.getActor());
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("applied", true);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleInit(RequestContext req, HttpServletResponse resp) {
        try {
            DataDirGitManager.initRepo();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("initialized", true);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * Fetch the configured remote and bring config to its HEAD — used to pull committed changes,
     * and to re-attach + recover after a gateway-backup restore (which drops {@code .git} but keeps
     * the remote record). See {@link DataDirGitManager#updateFromRemote()}.
     */
    private Object handleUpdateFromRemote(RequestContext req, HttpServletResponse resp) {
        try {
            String hash = DataDirGitManager.updateFromRemote();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("hash", hash);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private String error(HttpServletResponse resp, Exception e) {
        logger.error("Config-versioning route error", e);
        resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        JsonObject o = new JsonObject();
        o.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
        return o.toString();
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * One directory level of the data dir with each entry's exclusion state. Lazy on purpose — the
     * big directories in a data dir (db, logs, caches) are exactly the excluded ones, so walking
     * eagerly would cost the most where it buys the least.
     */
    private Object handleTree(RequestContext req, HttpServletResponse resp) {
        try {
            String path = req.getParameter("path");
            JsonArray arr = new JsonArray();
            for (DataDirGitManager.TreeEntry e : DataDirGitManager.listTree(path)) {
                arr.add(treeJson(e));
            }
            JsonObject out = new JsonObject();
            out.addProperty("path", path == null ? "" : path);
            out.add("entries", arr);
            return out.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleTreeSearch(RequestContext req, HttpServletResponse resp) {
        try {
            DataDirGitManager.SearchResult res =
                    DataDirGitManager.searchTree(req.getParameter("q"));
            JsonArray arr = new JsonArray();
            for (DataDirGitManager.TreeEntry e : res.entries()) {
                arr.add(treeJson(e));
            }
            JsonObject out = new JsonObject();
            out.add("entries", arr);
            out.addProperty("truncated", res.truncated());
            return out.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private static JsonObject treeJson(DataDirGitManager.TreeEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("name", e.name());
        o.addProperty("path", e.path());
        o.addProperty("directory", e.directory());
        o.addProperty("excluded", e.excluded());
        o.addProperty("tracked", e.tracked());
        o.addProperty("rule", e.rule());
        o.addProperty("ownRule", e.ownRule());
        o.addProperty("childState", e.childState());
        o.addProperty("reincludable", e.reincludable());
        return o;
    }

    private Object handleGetIgnore(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("text", DataDirGitManager.readIgnoreFile());
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * Two shapes, so the tree never has to reconstruct a file it did not author: {@code {text}}
     * replaces {@code .gitignore} wholesale (the source view), while {@code {exclude, include}}
     * applies tick/untick edits by appending to the managed block.
     */
    private Object handleSaveIgnore(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            JsonObject out = new JsonObject();
            if (body.has("text") && !body.get("text").isJsonNull()) {
                DataDirGitManager.writeIgnoreFile(body.get("text").getAsString());
                out.addProperty("untracked", 0);
            } else {
                int untracked = DataDirGitManager.applyIgnoreEdits(
                        stringList(body, "exclude"), stringList(body, "include"));
                out.addProperty("untracked", untracked);
            }
            // A .gitignore write is a plain file change, so ConfigAutoCommitter — which listens for
            // config RESOURCE changes — never sees it. Without this the edit sat uncommitted until
            // something else happened to commit, and a newly included file showed as "not yet
            // committed" indefinitely.
            DataDirGitManager.commitAllIfDirty("Updated .gitignore");
            out.addProperty("success", true);
            return out.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private static List<String> stringList(JsonObject body, String key) {
        List<String> out = new ArrayList<>();
        if (body.has(key) && body.get(key).isJsonArray()) {
            for (var el : body.getAsJsonArray(key)) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    /**
     * Delete a stored credential. Only the acting user's own credentials can be removed — the
     * records are per-user and a gateway admin editing someone else's would break their remotes
     * without telling them.
     */
    private Object handleRemoveCredential(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String type = optString(body, "type");
            long id = body.get("id").getAsLong();
            String actor = req.getActor();
            if ("SSH".equalsIgnoreCase(type)) {
                GitUserSshKeyRecord record = GitUserSshKeyRecord.findByIdAndUser(id, actor);
                if (record == null) {
                    throw new RuntimeException("No such SSH key for this user.");
                }
                record.delete();
            } else if ("HTTPS".equalsIgnoreCase(type)) {
                GitUserHttpsCredentialRecord record =
                        GitUserHttpsCredentialRecord.findByIdAndUser(id, actor);
                if (record == null) {
                    throw new RuntimeException("No such credential for this user.");
                }
                record.delete();
            } else {
                throw new RuntimeException("Unknown credential type: " + type);
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** Every project on the gateway with its git state — see GitProjectManager#listProjectStatus. */
    private Object handleProjects(RequestContext req, HttpServletResponse resp) {
        try {
            GitRunnerRecord runner = GitRunnerRecord.get();
            boolean runnerOn = runner.isEnabled() && runner.hasToken();
            List<GitProjectManager.ProjectStatus> projects = GitProjectManager.listProjectStatus();
            // A mode outliving its project would be inherited by the next project created under
            // that name, which is a delivery nobody chose.
            if (!runner.pruneMissing(projects.stream()
                    .map(GitProjectManager.ProjectStatus::name).toList()).isEmpty()) {
                runner.save();
            }

            JsonArray arr = new JsonArray();
            for (GitProjectManager.ProjectStatus p : projects) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.name());
                o.addProperty("title", p.title());
                o.addProperty("versioned", p.versioned());
                o.addProperty("branch", p.branch());
                o.addProperty("remoteName", p.remoteName());
                o.addProperty("remoteUrl", p.remoteUrl());
                o.addProperty("changes", p.changes());
                o.addProperty("error", p.error());
                o.addProperty("imagePrefix", GitProjectsConfigRecord.imagePrefixFor(p.name()));
                o.addProperty("credentialIssue", CredentialCheck.issueFor(p.name()));

                // A runner delivery on a runner nobody switched on delivers nothing; say so.
                o.addProperty("runnerEnabled", runnerOn);
                GitSyncRecord sync = GitSyncRecord.findByProject(p.name());
                o.addProperty("delivery", deliveryOf(runner, sync, p.name()));
                o.addProperty("syncBranch", sync == null ? "" : sync.getBranch());
                o.addProperty("syncIntervalSeconds", sync == null
                        ? GitSyncRecord.DEFAULT_INTERVAL_SECONDS : sync.getIntervalSeconds());
                o.addProperty("syncUser", sync == null ? "" : sync.getIgnitionUser());
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.add("projects", arr);
            out.add("imageFolders", imageStoreFolders());
            return out.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * Writes the project's configured image folder into its repository.
     *
     * <p>The same snapshot the Designer's Commit panel offers. It belongs here too because this is
     * where the folder is chosen — configuring a prefix on the gateway and then having to open a
     * Designer to act on it is a split nobody would design on purpose.
     */
    private Object handleSnapshotImages(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String project = optString(body, "project");
            if (project == null || project.isBlank()) {
                throw new RuntimeException("A project name is required.");
            }
            boolean ok = scriptModule.snapshotImages(project.trim());
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("imagePrefix", GitProjectsConfigRecord.imagePrefixFor(project.trim()));
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * Top-level folders in the gateway image store, so the Projects tab can offer them rather than
     * asking someone to type a path they have to go and look up.
     */
    private JsonArray imageStoreFolders() {
        JsonArray out = new JsonArray();
        com.operametrix.ignition.git.managers.GitImageManager.listFolders().forEach(out::add);
        return out;
    }


    /**
     * Set which image-store folder a project versions. Empty means none, which is the default.
     */
    private Object handleProjectImages(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String project = optString(body, "project");
            if (project == null || project.isBlank()) {
                throw new RuntimeException("A project name is required.");
            }
            GitProjectsConfigRecord record = GitProjectsConfigRecord.findByProjectName(project.trim());
            if (record == null) {
                throw new RuntimeException("Project '" + project.trim() + "' is not under version control.");
            }
            String prefix = optString(body, "imagePrefix");
            record.setImagePrefix(prefix == null ? "" : prefix);
            record.save();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("imagePrefix", record.getImagePrefix());
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * Put a project under version control: clone when a URL is given, otherwise initialise a local
     * repository. The same two paths the Designer's setup wizard offers, so a gateway admin can do
     * it before anyone opens a Designer.
     */
    private Object handleProjectInit(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String project = optString(body, "project");
            if (project == null || project.isBlank()) {
                throw new RuntimeException("A project name is required.");
            }
            String url = optString(body, "url");
            String actor = req.getActor();
            boolean ok;
            if (url == null || url.isBlank()) {
                ok = scriptModule.initializeLocalProject(project, actor);
            } else {
                long sshKeyId = body.has("sshKeyId") && !body.get("sshKeyId").isJsonNull()
                        ? body.get("sshKeyId").getAsLong() : 0L;
                long httpsId = body.has("httpsCredentialId") && !body.get("httpsCredentialId").isJsonNull()
                        ? body.get("httpsCredentialId").getAsLong() : 0L;
                ok = scriptModule.initializeProject(project, url.trim(), actor, sshKeyId, httpsId);
                if (ok && (sshKeyId > 0 || httpsId > 0)) {
                    CredentialCheck.checkSoon(sshKeyId > 0 ? "SSH" : "HTTPS",
                            sshKeyId > 0 ? sshKeyId : httpsId);
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** Attach or replace a project's remote, with the credential it should authenticate with. */
    /**
     * Attaches a stored credential to a project's remote.
     *
     * <p>Without this the Projects tab could set a remote it could never authenticate to: the
     * credential association lived only in the Designer's Remotes popup, so a project set up
     * entirely from the gateway page still needed a Designer before it could fetch or push.
     * Auth type follows the remote's URL, so exactly one of the two ids is meaningful; passing
     * zero for both clears the association.
     */
    private Object handleProjectCredential(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String project = optString(body, "project");
            if (project == null || project.isBlank()) {
                throw new RuntimeException("A project is required.");
            }
            String remoteName = optString(body, "remoteName");
            if (remoteName == null || remoteName.isBlank()) {
                remoteName = "origin";
            }
            String owner = optString(body, "ignitionUser");
            if (owner == null || owner.isBlank()) {
                owner = req.getActor();
            }
            long sshKeyId = optLong(body, "sshKeyId");
            long httpsCredentialId = optLong(body, "httpsCredentialId");
            boolean ok = scriptModule.setRemoteCredentialRefImpl(
                    project, remoteName, owner, sshKeyId, httpsCredentialId);
            if (!ok) {
                throw new RuntimeException(
                        "Could not attach the credential — check the gateway log.");
            }
            CredentialCheck.checkSoon(sshKeyId > 0 ? "SSH" : "HTTPS",
                    sshKeyId > 0 ? sshKeyId : httpsCredentialId);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleProjectRemote(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String project = optString(body, "project");
            String name = optString(body, "name");
            String url = optString(body, "url");
            if (project == null || project.isBlank() || url == null || url.isBlank()) {
                throw new RuntimeException("A project and a remote URL are required.");
            }
            String remoteName = (name == null || name.isBlank()) ? "origin" : name.trim();
            boolean ok = scriptModule.addRemote(project, remoteName, url.trim(), req.getActor());
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    // ── Delivery and logs ─────────────────────────────────────────────────────────────────────────────

    /** The Logs tab: the last events, and how many fired and failed since the gateway started. */
    private Object handleGetEvents(RequestContext req, HttpServletResponse resp) {
        try {
            JsonArray log = new JsonArray();
            for (GitEvent e : GitEvents.recent()) {
                JsonObject eo = new JsonObject();
                eo.addProperty("type", e.type());
                eo.addProperty("outcome", e.outcome());
                eo.addProperty("scope", e.scope());
                eo.addProperty("project", e.project());
                eo.addProperty("user", e.user());
                eo.addProperty("branch", e.branch());
                eo.addProperty("remote", e.remote());
                eo.addProperty("commit", e.commit());
                eo.addProperty("message", e.message());
                eo.addProperty("fileCount", e.files().size());
                eo.addProperty("timestamp", e.timestamp());
                log.add(eo);
            }
            GitEvents.Stats stats = GitEvents.stats();
            JsonObject so = new JsonObject();
            so.addProperty("fired", stats.fired());
            so.addProperty("failures", stats.failures());

            JsonObject o = new JsonObject();
            o.add("log", log);
            o.add("stats", so);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleClearEvents(RequestContext req, HttpServletResponse resp) {
        try {
            GitEvents.clearLog();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** off, runner-release, runner-repo, sync-pull or sync-replace — the one answer per project. */
    private static String deliveryOf(GitRunnerRecord runner, GitSyncRecord sync, String project) {
        String mode = runner.chosenMode(project);
        if (GitRunnerRecord.MODE_RELEASE.equals(mode)) {
            return "runner-release";
        }
        if (GitRunnerRecord.MODE_REPO.equals(mode)) {
            return "runner-repo";
        }
        if (sync != null && sync.isEnabled()) {
            return sync.isReplace() ? "sync-replace" : "sync-pull";
        }
        return "off";
    }

    /**
     * Sets how a project receives changes. The runner's mode and the sync record are written
     * together so they can never both claim the project: the runner delivers only to a project
     * whose mode is set, and the scheduler only runs an enabled sync record.
     */
    private Object handleSaveDelivery(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String project = optString(body, "project");
            String delivery = optString(body, "delivery");
            if (project == null || project.isBlank()) {
                throw new RuntimeException("A project name is required.");
            }
            if (!List.of("off", "runner-release", "runner-repo", "sync-pull", "sync-replace")
                    .contains(delivery)) {
                throw new RuntimeException("Unknown delivery '" + delivery + "'.");
            }

            boolean pulls = delivery.startsWith("sync-") || delivery.equals("runner-repo");
            if (pulls && GitProjectManager.listProjectStatus().stream().noneMatch(p ->
                    p.name().equals(project) && p.remoteUrl() != null && !p.remoteUrl().isBlank())) {
                throw new RuntimeException("'" + project + "' has no remote to pull from; set one first.");
            }

            GitRunnerRecord runner = GitRunnerRecord.get();
            switch (delivery) {
                case "runner-release" -> runner.setMode(project, GitRunnerRecord.MODE_RELEASE);
                case "runner-repo" -> runner.setMode(project, GitRunnerRecord.MODE_REPO);
                default -> runner.clearMode(project);
            }
            runner.save();

            // The sync record also carries the branch and credential a runner repo update pulls
            // with, so it is kept (disabled) rather than deleted when the timer is not wanted.
            GitSyncRecord sync = GitSyncRecord.findByProject(project);
            if (sync == null && pulls) {
                sync = new GitSyncRecord();
                sync.setProject(project);
            }
            if (sync != null) {
                sync.setEnabled(delivery.startsWith("sync-"));
                if (pulls) {
                    sync.setMode(delivery.equals("sync-replace")
                            ? GitSyncRecord.MODE_REPLACE : GitSyncRecord.MODE_PULL);
                    sync.setBranch(optString(body, "branch"));
                    if (body.has("intervalSeconds")) {
                        sync.setIntervalSeconds((int) optLong(body, "intervalSeconds"));
                    }
                    if (body.has("remoteName")) {
                        sync.setRemoteName(optString(body, "remoteName"));
                    }
                    // Unattended, so it authenticates as a named user's stored credential rather
                    // than whoever is in a Designer. Default to the admin saving it.
                    String owner = optString(body, "ignitionUser");
                    sync.setIgnitionUser(owner == null || owner.isBlank() ? req.getActor() : owner);
                }
                sync.save();
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("delivery", delivery);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** Runner access, on the Credentials tab: whether it is on, and whether a token exists. */
    private Object handleGetRunner(RequestContext req, HttpServletResponse resp) {
        try {
            GitRunnerRecord cfg = GitRunnerRecord.get();
            JsonObject o = new JsonObject();
            o.addProperty("enabled", cfg.isEnabled());
            // Never the token itself: it is shown once, when generated.
            o.addProperty("hasToken", cfg.hasToken());
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleSaveRunner(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            GitRunnerRecord cfg = GitRunnerRecord.get();
            if (body.has("enabled")) {
                cfg.setEnabled(body.get("enabled").getAsBoolean());
            }
            // Whoever configures the runner is the fallback credential owner for repo updates.
            cfg.setIgnitionUser(req.getActor());

            String issued = null;
            if (body.has("generateToken") && body.get("generateToken").getAsBoolean()) {
                issued = cfg.generateToken();
            } else if (body.has("clearToken") && body.get("clearToken").getAsBoolean()) {
                cfg.setToken(null);
            }
            cfg.save();

            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("hasToken", cfg.hasToken());
            if (issued != null) {
                // The only time this value ever leaves the gateway. It is not recoverable
                // afterwards — a lost token is replaced, not read back.
                o.addProperty("token", issued);
            }
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleSyncNow(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String project = optString(body, "project");
            GitSyncRecord cfg = GitSyncRecord.findByProject(project);
            if (cfg == null) {
                throw new RuntimeException("Sync is not configured for '" + project + "'.");
            }
            String result = SyncScheduler.syncNow(cfg);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("result", result);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private static boolean optBool(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() && body.get(key).getAsBoolean();
    }

}
