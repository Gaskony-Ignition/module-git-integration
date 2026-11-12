package com.operametrix.ignition.git;

import com.operametrix.ignition.git.automation.GitAudit;
import com.operametrix.ignition.git.automation.GitEvent;
import com.operametrix.ignition.git.automation.ReleaseReceiver;
import com.operametrix.ignition.git.automation.RunnerAuth;
import com.operametrix.ignition.git.automation.RunnerTrigger;
import com.operametrix.ignition.git.automation.GitEvents;
import com.operametrix.ignition.git.automation.SyncScheduler;
import com.operametrix.ignition.git.records.GitConfigRemoteRecord;
import com.operametrix.ignition.git.records.GitProjectsConfigRecord;
import com.operametrix.ignition.git.records.GitPolicy;
import com.operametrix.ignition.git.records.GitPolicyRecord;
import com.operametrix.ignition.git.records.GitRunnerRecord;
import com.operametrix.ignition.git.records.ModeStore;
import com.operametrix.ignition.git.managers.PolicyGate;
import com.operametrix.ignition.git.records.GitSyncRecord;
import com.operametrix.ignition.git.records.GitRemoteCredentialsRecord;
import com.operametrix.ignition.git.records.GitReposUsersRecord;
import com.operametrix.ignition.git.records.GitUserHttpsCredentialRecord;
import com.operametrix.ignition.git.records.GitUserSshKeyRecord;
import com.operametrix.ignition.git.records.legacy.GitLegacyImporter;
import com.operametrix.ignition.git.records.legacy.RetiredResourceCleanup;
import com.operametrix.ignition.git.managers.ConfigAutoCommitter;
import com.operametrix.ignition.git.managers.BranchGraph;
import com.operametrix.ignition.git.managers.BranchSwitch;
import com.operametrix.ignition.git.managers.ConfigSync;
import com.operametrix.ignition.git.managers.IgnitionReformat;
import com.operametrix.ignition.git.managers.CredentialCheck;
import com.operametrix.ignition.git.managers.ProjectRecords;
import com.operametrix.ignition.git.managers.DataDirGitManager;
import com.operametrix.ignition.git.managers.GitManager;
import com.operametrix.ignition.git.managers.PolicyWatch;
import com.operametrix.ignition.git.managers.RepoHardening;
import com.operametrix.ignition.git.managers.SafePaths;
import com.operametrix.ignition.git.managers.LayoutSwitch;
import com.operametrix.ignition.git.managers.MasterRename;
import com.operametrix.ignition.git.managers.PullGuard;
import com.operametrix.ignition.git.managers.RepoLocator;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

public class GatewayHook extends AbstractGatewayModuleHook {
    static public String MODULE_NAME = "Git";

    /** Alias for both the mounted JS bundle (/res/<alias>/…) and the data routes (/data/<alias>/…). */
    private static final String MOUNT_ALIAS = "git-config";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private GatewayScriptModule scriptModule;
    private ProjectRecords projectRecords;
    private static GatewayContext context;

    private GitProjectsConfigRecord.Handler projectHandler;
    private GitReposUsersRecord.Handler repoUserHandler;
    private GitUserSshKeyRecord.Handler sshKeyHandler;
    private GitUserHttpsCredentialRecord.Handler httpsCredHandler;
    private GitRemoteCredentialsRecord.Handler remoteCredHandler;
    private GitConfigRemoteRecord.Handler configRemoteHandler;
    private GitSyncRecord.Handler syncHandler;
    private GitRunnerRecord.Handler runnerHandler;
    private GitPolicyRecord.Handler policyHandler;
    private com.operametrix.ignition.git.records.GitAuditRecord.Handler auditHandler;
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
        registry.register(GitPolicyRecord.META);
        registry.register(com.operametrix.ignition.git.records.GitAuditRecord.META);
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
        policyHandler = new GitPolicyRecord.Handler(context);
        auditHandler = new com.operametrix.ignition.git.records.GitAuditRecord.Handler(context);

        GitProjectsConfigRecord.setHandler(projectHandler);
        GitReposUsersRecord.setHandler(repoUserHandler);
        GitUserSshKeyRecord.setHandler(sshKeyHandler);
        GitUserHttpsCredentialRecord.setHandler(httpsCredHandler);
        GitRemoteCredentialsRecord.setHandler(remoteCredHandler);
        GitConfigRemoteRecord.setHandler(configRemoteHandler);
        GitSyncRecord.setHandler(syncHandler);
        GitRunnerRecord.setHandler(runnerHandler);
        GitPolicyRecord.setHandler(policyHandler);
        com.operametrix.ignition.git.records.GitAuditRecord.setHandler(auditHandler);
        com.operametrix.ignition.git.automation.GitAudit.setContext(context);

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
        policyHandler.startup();
        auditHandler.startup();

        // 3.5.0 handed Release to every project folder on a gateway whose runner was on. A stored
        // delivery is indistinguishable from a chosen one, so 3.7.0 forgets them all once and never
        // assigns another.
        try {
            clearAssignedDeliveries();
        } catch (Exception e) {
            logger.error("Could not clear the deliveries an earlier version assigned; check each"
                    + " project's delivery on the Projects tab.", e);
        }

        // .gitignore alone decides what the config repository versions (3.8.0). 3.9.12 makes it
        // exclude the data root apart from config/, and untracks what 3.8.0 swept in, once.
        try {
            List<String> untracked = DataDirGitManager.denyRootOnce();
            if (!untracked.isEmpty()) {
                logger.info("Config repository now versions only config/ by default; untracked {}"
                        + " data-root files.", untracked.size());
                GitEvents.fire(GitEvent.of("ignore").config()
                        .message("Only config/ is versioned by default — data-root files untracked")
                        .files(untracked).success());
            }
        } catch (Exception e) {
            logger.error("Could not restrict the config repository to config/; check the Git ignore tab.", e);
        }

        // 4.1.0: the Git policy is versioned with the config (every other record stays out), and a
        // gateway always has one in core so a deployment mode has something to override.
        try {
            if (DataDirGitManager.versionPolicyOnce()) {
                logger.info("Config repository now versions the Git policy.");
            }
            if (DataDirGitManager.combinedRulesOnce()) {
                logger.info("Combined layout: config ignore rules now apply under config/ only.");
            }
        } catch (Exception e) {
            logger.error("Could not version the Git policy; check the Git ignore tab.", e);
        }
        try {
            GitPolicyRecord.ensureCore();
        } catch (Exception e) {
            logger.error("Could not write the default Git policy.", e);
        }

        // 4.3.0: TLS checks back on, no symlinks in checkouts, no passwords in remote URLs, every
        // stored key encrypted and every HTTPS credential bound to a host, before anything syncs.
        RepoHardening.atStartup();
        com.operametrix.ignition.git.managers.GitHubMerged.loadNow();
        PolicyWatch.check("start-up: a mode switch or a change made while the gateway was down");

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

        // Deletes and renames made while the module was not running reach no listener.
        projectRecords = new ProjectRecords();
        context.getProjectManager().addListener(projectRecords);
        ProjectRecords.reconcile();

        logger.info("startup()");
    }

    @Override
    public void shutdown() {
        if (projectRecords != null) {
            context.getProjectManager().removeListener(projectRecords);
            projectRecords.shutdown();
        }
        SyncScheduler.shutdown();
        CredentialCheck.shutdown();
        if (runnerHandler != null) runnerHandler.shutdown();
        if (policyHandler != null) policyHandler.shutdown();
        if (auditHandler != null) auditHandler.shutdown();
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
        return Optional.of(GatewayRpcImplementation.of(GitScriptInterface.SERIALIZER, RpcErrors.wrap(scriptModule)));
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

        routes.newRoute("/secret-ref").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE).nocache()
                .handler(this::handleSecretRef).mount();

        // WRITE, unlike the other reads: it names every secret in every provider. POST, because
        // the gateway only mounts a WRITE route on an unsafe method (and wants its CSRF token).
        routes.newRoute("/secret-providers").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE).nocache()
                .handler(this::handleGetSecretProviders).mount();

        routes.newRoute("/remote").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(scoped(this::handleSaveRemote)).mount();

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
                .handler(scoped(this::handleAddCredential)).mount();

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
                .handler(scoped(this::handleProjectInit)).mount();

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
                .handler(scoped(this::handleSaveDelivery)).mount();

        routes.newRoute("/sync-now").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSyncNow).mount();

        routes.newRoute("/abort-merge").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleAbortMerge).mount();

        routes.newRoute("/restore").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleRestore).mount();

        routes.newRoute("/init").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleInit).mount();

        routes.newRoute("/config-delivery").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSaveConfigDelivery).mount();

        routes.newRoute("/config-sync-now").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleConfigSyncNow).mount();

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
                .handler(scoped(this::handleSaveRunner)).mount();

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
        routes.newRoute("/layout").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSaveLayout).mount();

        routes.newRoute("/branch-graph").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleBranchGraph).mount();
        routes.newRoute("/branch-rename-master").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleRenameMaster).mount();
        routes.newRoute("/branch-checkout").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleBranchCheckout).mount();
        routes.newRoute("/policy").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(scoped(this::handleSavePolicy)).mount();
        routes.newRoute("/mode-reset").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleModeReset).mount();
        routes.newRoute("/mode-override").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleModeOverride).mount();
        routes.newRoute("/definition").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleGetDefinition).mount();
        routes.newRoute("/config-fetch").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleConfigFetch).mount();
        routes.newRoute("/config-pull").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleConfigPull).mount();
        routes.newRoute("/config-join").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleConfigJoin).mount();
        routes.newRoute("/project-add-peek").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleProjectAddPeek).mount();
        routes.newRoute("/project-add").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(scoped(this::handleProjectAdd)).mount();
        routes.newRoute("/audit").method(HttpMethod.GET).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.READ).nocache()
                .handler(this::handleGetAudit).mount();
        routes.newRoute("/audit").method(HttpMethod.POST).type(RouteGroup.TYPE_JSON)
                .requirePermission(PermissionType.WRITE)
                .handler(this::handleSaveAudit).mount();
    }

    private Object handleConfigFetch(RequestContext req, HttpServletResponse resp) {
        try {
            DataDirGitManager.fetchConfig();
            DataDirGitManager.Relation r = DataDirGitManager.relation();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("ahead", DataDirGitManager.aheadCount());
            o.addProperty("behind", r.behind());
            o.addProperty("unrelated", r.unrelated());
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** Pull for the gateway repository; an audit row only when commits came in. */
    private Object handleConfigPull(RequestContext req, HttpServletResponse resp) {
        String who = webUser(req).orElse(req.getActor());
        try {
            DataDirGitManager.PullResult r = DataDirGitManager.pullConfig();
            JsonObject o = new JsonObject();
            o.addProperty("ok", r.changed() || "up to date".equals(r.note()));
            o.addProperty("changed", r.changed());
            o.addProperty("message", r.changed() ? r.files().size() + " changed file(s)" : r.note());
            JsonArray files = new JsonArray();
            r.files().forEach(files::add);
            o.add("files", files);
            if (r.changed()) {
                String[] hb = DataDirGitManager.headAndBranch();
                GitEvents.fire(GitEvent.of(GitEvent.PULL).config().user(who).branch(hb[1]).commit(r.commit())
                        .message("Pulled " + r.files().size() + " changed file(s)").files(r.files()).success());
                GitAudit.record(GitAudit.PULL, who, null, hb[1], r.commit(), r.files().size() + " file(s)");
            } else if (!"up to date".equals(r.note())) {
                resp.setStatus(HttpServletResponse.SC_CONFLICT);
                o.addProperty("error", r.note());
            }
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleConfigJoin(RequestContext req, HttpServletResponse resp) {
        String who = webUser(req).orElse(req.getActor());
        try {
            String commit = DataDirGitManager.joinRemoteHistory(who);
            GitAudit.record(GitAudit.JOIN, who, null, DataDirGitManager.headAndBranch()[1], commit,
                    "this gateway's config committed on top of the remote branch");
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("commit", commit);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private static long optId(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsLong() : 0L;
    }

    /** Add from repository, step one: the repository's branches and a suggested project name. */
    private Object handleProjectAddPeek(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            GatewayScriptModule.RepoPeek p = scriptModule.peekRepository(optString(body, "url"),
                    optString(body, "branch"), req.getActor(), optId(body, "sshKeyId"), optId(body, "httpsCredentialId"));
            JsonObject o = new JsonObject();
            JsonArray branches = new JsonArray();
            p.branches().forEach(branches::add);
            o.add("branches", branches);
            o.addProperty("defaultBranch", p.defaultBranch());
            o.addProperty("suggestedName", p.suggestedName());
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * Add a project the gateway does not have from its repository: {@code url}, {@code branch}
     * (blank: the remote's default), {@code name}, a credential, and optionally a delivery.
     */
    private Object handleProjectAdd(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String name = optString(body, "name");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A project name is required.");
            }
            name = name.trim();
            String delivery = body.has("delivery") ? checkDelivery(body) : null;
            long ssh = optId(body, "sshKeyId");
            long https = optId(body, "httpsCredentialId");
            String[] r = scriptModule.addProjectFromRepository(name, optString(body, "url"),
                    optString(body, "branch"), req.getActor(), ssh, https);
            if (ssh > 0 || https > 0) {
                CredentialCheck.checkSoon(ssh > 0 ? "SSH" : "HTTPS", ssh > 0 ? ssh : https);
            }
            if (delivery != null && !delivery.equals("off")) {
                if (!body.has("branch") || optString(body, "branch") == null || optString(body, "branch").isBlank()) {
                    body.addProperty("branch", r[0]);
                }
                applyDelivery(body, name, delivery, req.getActor());
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("project", name);
            o.addProperty("branch", r[0]);
            o.addProperty("commit", r[1]);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** The audit setting: on/off, the chosen profile (blank: the gateway's), and the choices. */
    private Object handleGetAudit(RequestContext req, HttpServletResponse resp) {
        try {
            com.operametrix.ignition.git.records.GitAuditRecord.Config c =
                    com.operametrix.ignition.git.records.GitAuditRecord.get();
            JsonObject o = new JsonObject();
            o.addProperty("enabled", c.on());
            o.addProperty("profile", c.profileOrBlank());
            o.addProperty("gatewayHasProfile", GitAudit.gatewayHasProfile());
            JsonArray profiles = new JsonArray();
            GitAudit.available().forEach(profiles::add);
            o.add("profiles", profiles);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleSaveAudit(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            boolean enabled = !body.has("enabled") || body.get("enabled").isJsonNull() || body.get("enabled").getAsBoolean();
            String profile = optString(body, "profile");
            profile = profile == null ? "" : profile.trim();
            if (!profile.isEmpty() && !GitAudit.available().contains(profile)) {
                throw new IllegalArgumentException("There is no audit profile '" + profile + "' on this gateway.");
            }
            // Written to the profile in use before the change, so turning auditing off is on record.
            GitAudit.record(GitAudit.AUDIT, webUser(req).orElse(req.getActor()), null, null, null,
                    enabled ? "audit profile " + (profile.isEmpty() ? "of the gateway" : profile) : "auditing off");
            com.operametrix.ignition.git.records.GitAuditRecord.save(
                    new com.operametrix.ignition.git.records.GitAuditRecord.Config(enabled, profile));
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
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
            o.addProperty("layout", GitManager.combined() ? "combined" : "separate");
            o.add("policy", policyJson());
            // Every mode created here, active or not: the drawers' Create Override offers each.
            JsonArray modes = new JsonArray();
            ModeStore.modes().forEach(modes::add);
            o.add("modes", modes);
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
            // A merge left open in the gateway repository (a Designer closed mid-conflict) blocks
            // config commits and pulls until it is completed or aborted.
            String busy = DataDirGitManager.midOperationNow();
            if (busy != null) {
                o.addProperty("midOperation", busy);
            }
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleAbortMerge(RequestContext req, HttpServletResponse resp) {
        try {
            DataDirGitManager.abortOpenMerge();
            String[] hb = DataDirGitManager.headAndBranch();
            GitEvents.fire(GitEvent.of(GitEvent.PULL).config().user(webUser(req).orElse(req.getActor()))
                    .branch(hb[1]).commit(hb[0]).message("Aborted the merge left open in the gateway repository")
                    .success());
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** Repository layout switch: {@code {"layout":"separate"|"combined","restoreHistory":bool}}. */
    private Object handleSaveLayout(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String layout = optString(body, "layout");
            if (!"separate".equals(layout) && !"combined".equals(layout)) {
                throw new RuntimeException("Unknown layout '" + layout + "'.");
            }
            LayoutSwitch.Result r = DataDirGitManager.switchLayout("combined".equals(layout)
                    ? RepoLocator.Layout.COMBINED : RepoLocator.Layout.SEPARATE, optBool(body, "restoreHistory"),
                    optBool(body, "archiveLeftovers"), webUser(req).orElse(null));
            JsonObject o = new JsonObject();
            GitAudit.record(GitAudit.LAYOUT, webUser(req).orElse(req.getActor()), null,
                    DataDirGitManager.headAndBranch()[1], DataDirGitManager.headAndBranch()[0],
                    "to " + layout + (optBool(body, "restoreHistory") ? ", project history restored" : ""));
            o.addProperty("ok", true);
            o.addProperty("layout", layout);
            o.addProperty("projects", r.projects().size());
            o.addProperty("restored", r.restored().size());
            o.addProperty("archived", r.archived().size());
            if (!"combined".equals(layout)) {
                // A new repository has no remote, so a Sync delivery kept from before would fail on
                // every interval until one is set.
                List<String> noRemote = new ArrayList<>();
                for (GitSyncRecord sync : GitSyncRecord.listEnabled()) {
                    try {
                        String url = GitManager.getRemoteUrl(GitManager.repoFor(sync.getProject()).root(),
                                sync.getRemoteName());
                        if (url == null || url.isBlank()) {
                            noRemote.add(sync.getProject());
                        }
                    } catch (Exception e) {
                        noRemote.add(sync.getProject());
                    }
                }
                if (!noRemote.isEmpty()) {
                    o.addProperty("warning", "Sync delivery is on for " + String.join(", ", noRemote)
                            + ", but the repository has no remote now. Add one in the Designer's Remotes,"
                            + " or set the delivery to Off on the Projects tab.");
                }
            }
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }


    /**
     * The commit graph for one repository: {@code ?project=<name>} (in Combined, the gateway
     * repository with commits outside {@code projects/<name>/} flagged) or {@code ?repo=config}.
     */
    private Object handleBranchGraph(RequestContext req, HttpServletResponse resp) {
        try {
            String project = req.getParameter("project");
            boolean config = "config".equals(req.getParameter("repo"));
            int limit = Math.max(1, Math.min(GitManager.MAX_LOG,
                    parseInt(req.getParameter("limit"), BranchGraph.DEFAULT_LIMIT)));
            boolean combined = GitManager.combined();
            Path root;
            String prefix = null;
            Map<String, String> guarded;
            if (config) {
                root = GitManager.getDataFolderPath();
                guarded = gatewayGuarded();
            } else {
                if (project == null || project.isBlank()) {
                    throw new RuntimeException("A project or repo=config is required.");
                }
                RepoLocator.Repo repo = GitManager.repoFor(project.trim());
                root = repo.root();
                if (repo.scoped()) {
                    prefix = repo.prefix();
                    guarded = gatewayGuarded();
                } else {
                    guarded = new HashMap<>();
                    GitSyncRecord sync = GitSyncRecord.findByProject(project.trim());
                    if (sync != null && sync.isEnabled() && !sync.getBranch().isBlank()) {
                        guarded.put(sync.getBranch(), "sync");
                    }
                }
            }
            if (!java.nio.file.Files.isDirectory(root.resolve(".git"))) {
                throw new RuntimeException(config || combined
                        ? "The gateway repository is not initialised."
                        : "Project '" + project + "' is not under version control.");
            }
            BranchGraph.Result r;
            try (org.eclipse.jgit.api.Git git = GitManager.getGit(root)) {
                r = BranchGraph.build(git, limit, prefix, guarded);
            }
            JsonObject o = new JsonObject();
            o.addProperty("layout", combined ? "combined" : "separate");
            o.addProperty("scope", config ? "config" : combined ? "gateway" : "project");
            o.addProperty("branch", r.branch());
            o.addProperty("headSha", r.headSha());
            o.addProperty("lanes", r.lanes());
            o.addProperty("truncated", r.truncated());
            o.addProperty("filtered", prefix != null);
            JsonArray commits = new JsonArray();
            for (BranchGraph.Commit c : r.commits()) {
                JsonObject co = new JsonObject();
                co.addProperty("sha", c.sha());
                co.addProperty("shortSha", c.shortSha());
                JsonArray parents = new JsonArray();
                c.parents().forEach(parents::add);
                co.add("parents", parents);
                co.addProperty("author", c.author());
                co.addProperty("time", c.time());
                co.addProperty("subject", c.subject());
                co.addProperty("lane", c.lane());
                co.addProperty("touches", c.touches());
                commits.add(co);
            }
            o.add("commits", commits);
            JsonArray edges = new JsonArray();
            for (BranchGraph.Edge e : r.edges()) {
                JsonObject eo = new JsonObject();
                eo.addProperty("from", e.from());
                eo.addProperty("fromLane", e.fromLane());
                eo.addProperty("to", e.to());
                eo.addProperty("toLane", e.toLane());
                eo.addProperty("lane", e.lane());
                edges.add(eo);
            }
            o.add("edges", edges);
            JsonArray refs = new JsonArray();
            for (BranchGraph.RefInfo f : r.refs()) {
                JsonObject fo = new JsonObject();
                fo.addProperty("name", f.name());
                fo.addProperty("type", f.type());
                fo.addProperty("sha", f.sha());
                fo.addProperty("head", f.head());
                refs.add(fo);
            }
            o.add("refs", refs);
            JsonArray branches = new JsonArray();
            for (BranchGraph.Branch b : r.branches()) {
                JsonObject bo = new JsonObject();
                bo.addProperty("name", b.info().name());
                bo.addProperty("upstream", b.info().upstream());
                bo.addProperty("gone", b.info().gone());
                bo.addProperty("ahead", b.info().ahead());
                bo.addProperty("behind", b.behind());
                bo.addProperty("merged", b.info().merged());
                bo.addProperty("protectedReason", b.info().protectedReason());
                bo.addProperty("sha", b.sha());
                bo.addProperty("subject", b.subject());
                bo.addProperty("time", b.time());
                branches.add(bo);
            }
            o.add("branches", branches);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** The signed-in gateway user behind a page request, for the Logs tab. */
    private static java.util.Optional<String> webUser(RequestContext req) {
        return WebUiSession.find(req)
                .flatMap(s -> s.getUserContext().getWebAuthUser())
                .map(u -> u.getUserName());
    }

    /** Branches the gateway delivery follows, which a gateway-wide repository must keep. */
    private static Map<String, String> gatewayGuarded() {
        Map<String, String> out = new HashMap<>();
        ConfigSync.Settings gw = ConfigSync.get();
        if (!ConfigSync.MODE_OFF.equals(gw.mode()) && !gw.branch().isBlank()) {
            out.put(gw.branch(), "delivery");
        }
        return out;
    }

    /**
     * Switch the branch of one repository ({@code project} or {@code repo: "config"}). Refuses,
     * with the files, when uncommitted work would be affected; never stashes.
     */
    /**
     * The name a repository's lone {@code master} should take to match its remote: {@code wanted},
     * else (no branch configured) {@code main} when origin has main and no master. Null when there
     * is nothing to offer or the repository cannot be read.
     */
    private static String renameTarget(Path root, String wanted) {
        if (!java.nio.file.Files.isDirectory(root.resolve(".git"))) {
            return null;
        }
        try (org.eclipse.jgit.api.Git git = GitManager.getGit(root)) {
            org.eclipse.jgit.lib.Repository r = git.getRepository();
            String w = wanted;
            if (w == null || w.isBlank()) {
                w = r.exactRef("refs/remotes/origin/main") != null
                        && r.exactRef("refs/remotes/origin/master") == null ? "main" : null;
            }
            return MasterRename.target(r, w);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A repository made before 4.2.0 sits on a lone {@code master} while its remote's branch is
     * {@code main}, so the page read "Remote sync (master → main)" as if the module had the branch
     * wrong. Renamed here, where the page first sees it, with a line on the Logs tab. Returns the
     * name still to offer on the Rename button: only when the policy forbids branch changes or the
     * rename failed. MasterRename leaves anything but a lone, checked-out master alone, and a
     * delivery that follows master by name keeps it.
     *
     * @param project the project, or null for the gateway repository
     */
    private static String autoRenameMaster(Path root, String wanted, String project) {
        String to = renameTarget(root, wanted);
        if (to == null) {
            return null;
        }
        try {
            PolicyGate.check(PolicyGate.Action.BRANCH_SWITCH);
        } catch (Exception refused) {
            return to;
        }
        try {
            synchronized (project == null ? DataDirGitManager.lock() : GitManager.lockFor(GitManager.repoFor(project))) {
                try (org.eclipse.jgit.api.Git git = GitManager.getGit(root)) {
                    if (MasterRename.target(git.getRepository(), to) == null) {
                        return null;
                    }
                    MasterRename.rename(git, to, "origin");
                }
            }
            GitEvents.fire(GitEvent.of(GitEvent.CHECKOUT).project(project == null ? "" : project).user("gateway")
                    .branch(to).message("Renamed local master to " + to + " to match the remote").success());
            return null;
        } catch (Exception e) {
            // The page asks every few seconds: say it once per repository, not on every poll.
            if (RENAME_WARNED.add(root.toString())) {
                LoggerFactory.getLogger(GatewayHook.class).warn("Could not rename local master to {}{}: {}", to,
                        project == null ? "" : " in project " + project, e.getMessage());
            }
            return to;
        }
    }

    private static final java.util.Set<String> RENAME_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Rename local master to the remote's branch name: {@code project}, or {@code repo:"config"}. */
    private Object handleRenameMaster(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            PolicyGate.check(PolicyGate.Action.BRANCH_SWITCH);
            String project = optString(body, "project");
            boolean gateway = "config".equals(optString(body, "repo")) || GitManager.combined();
            Path root;
            String to;
            if (gateway) {
                root = GitManager.getDataFolderPath();
                GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
                to = renameTarget(root, remote == null ? null : remote.getBranch());
            } else {
                if (project == null || project.isBlank()) {
                    throw new RuntimeException("A project or repo=config is required.");
                }
                root = GitManager.repoFor(project.trim()).root();
                GitSyncRecord sync = GitSyncRecord.findByProject(project.trim());
                to = renameTarget(root, sync != null && sync.isEnabled() && !sync.getBranch().isBlank()
                        ? sync.getBranch() : null);
            }
            if (to == null) {
                throw new RuntimeException("Nothing to rename: the repository is not on a lone master branch"
                        + " behind a remote with another name.");
            }
            synchronized (gateway ? DataDirGitManager.lock() : GitManager.lockFor(GitManager.repoFor(project.trim()))) {
                try (org.eclipse.jgit.api.Git git = GitManager.getGit(root)) {
                    MasterRename.rename(git, to, "origin");
                }
            }
            GitEvents.fire(GitEvent.of(GitEvent.CHECKOUT).project(gateway ? "" : project)
                    .user(webUser(req).orElse("Versioning page")).branch(to)
                    .message("Renamed local master to " + to).success());
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("branch", to);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleBranchCheckout(RequestContext req, HttpServletResponse resp) {
        String project = null;
        boolean config = false;
        String branch = null;
        String who = webUser(req).orElse("Versioning page");
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            project = optString(body, "project");
            config = "config".equals(optString(body, "repo"));
            branch = optString(body, "branch");
            PolicyGate.check(PolicyGate.Action.BRANCH_SWITCH);
            if (branch == null || branch.isBlank()) {
                throw new RuntimeException("A branch is required.");
            }
            if (!config && (project == null || project.isBlank())) {
                throw new RuntimeException("A project or repo=config is required.");
            }
            branch = branch.trim();
            boolean gateway = config || GitManager.combined();
            Path root = gateway ? GitManager.getDataFolderPath() : GitManager.repoFor(project.trim()).root();
            if (!java.nio.file.Files.isDirectory(root.resolve(".git"))) {
                throw new RuntimeException("That repository is not initialised.");
            }

            String followed;
            String where;
            if (gateway) {
                ConfigSync.Settings gw = ConfigSync.get();
                followed = ConfigSync.MODE_OFF.equals(gw.mode()) ? null : gw.branch();
                where = "Configure versioning > Delivery";
            } else {
                GitSyncRecord sync = GitSyncRecord.findByProject(project.trim());
                followed = sync != null && sync.isEnabled() ? sync.getBranch() : null;
                where = "the Projects tab (Edit > Delivery)";
            }

            BranchSwitch.Result r;
            // Separate: the project's own lock, which its Designer and sync timer take too.
            synchronized (gateway ? DataDirGitManager.lock() : GitManager.lockFor(GitManager.repoFor(project.trim()))) {
                if (gateway) {
                    DataDirGitManager.refuseMidOperation();
                }
                ConfigAutoCommitter.hold(30_000);
                try (org.eclipse.jgit.api.Git git = GitManager.getGit(root)) {
                    r = BranchSwitch.switchTo(git, branch, followed, where, gateway, DIRTY,
                            () -> DataDirGitManager.commitAllIfDirty(
                                    "Config changes before switching branch"));
                }
            }

            JsonObject o = new JsonObject();
            o.addProperty("ok", r.ok());
            o.addProperty("message", r.message());
            if (!r.ok()) {
                o.addProperty("refused", r.refused());
                JsonArray files = new JsonArray();
                r.files().forEach(files::add);
                o.add("files", files);
                return o.toString();
            }
            o.addProperty("branch", r.branch());

            // Only a real switch changes files; "already on it" must not rescan.
            if (!r.message().startsWith("Already")) {
                if (gateway) {
                    ConfigAutoCommitter.hold(10_000);
                    DataDirGitManager.applyConfigToRunningGateway();
                    if (!config) {
                        DataDirGitManager.applyProjects(null);
                    } else if (GitManager.combined()) {
                        DataDirGitManager.applyProjects(null);
                    }
                } else {
                    GitProjectManager.importProject(project.trim());
                    getContext().getProjectManager().requestScan();
                }
                GitEvent.Builder ev = GitEvent.of(GitEvent.CHECKOUT).user(who).branch(r.branch())
                        .message("Switched to '" + r.branch() + "'" + (r.created() ? " (new tracking branch)" : "")
                                + (gateway ? " for the whole gateway repository" : ""));
                GitEvents.fire((gateway ? ev.config() : ev.project(project.trim())).success());
                org.eclipse.jgit.lib.ObjectId head;
                try (org.eclipse.jgit.api.Git g = GitManager.getGit(root)) {
                    head = g.getRepository().resolve("HEAD");
                }
                GitAudit.record(GitAudit.BRANCH, who, config ? null : project.trim(), r.branch(),
                        head == null ? "" : head.getName(), gateway ? "whole gateway repository" : "");
            }
            return o.toString();
        } catch (Exception e) {
            GitEvents.fire((config ? GitEvent.of(GitEvent.CHECKOUT).config()
                    : GitEvent.of(GitEvent.CHECKOUT).project(project == null ? "" : project.trim()))
                    .user(who).branch(branch == null ? "" : branch).failure(GitEvents.reason(e)));
            return error(resp, e);
        }
    }

    private static final BranchSwitch.Dirty DIRTY = new BranchSwitch.Dirty() {
        public java.util.Set<String> real(org.eclipse.jgit.lib.Repository repo, org.eclipse.jgit.api.Status s) {
            return IgnitionReformat.realChanges(repo, s);
        }

        public java.util.Set<String> reformatted(org.eclipse.jgit.lib.Repository repo, org.eclipse.jgit.api.Status s) {
            return IgnitionReformat.reformatted(repo, s);
        }
    };

    /** Ahead/behind of a repository's checked-out branch against its upstream; null with none. */
    private static int[] aheadBehind(Path root) {
        try (org.eclipse.jgit.api.Git git = GitManager.getGit(root)) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            String b = repo.getBranch();
            org.eclipse.jgit.lib.BranchTrackingStatus st = org.eclipse.jgit.lib.BranchTrackingStatus.of(repo, b);
            return st == null ? null : new int[]{st.getAheadCount(), st.getBehindCount()};
        } catch (Exception e) {
            return null;
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
            int limit = Math.max(1, Math.min(GitManager.MAX_LOG, parseInt(req.getParameter("limit"), 25)));
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
                o.addProperty("from", remote.definedIn());
                o.add("defined", definedJson(GitConfigRemoteRecord.TYPE, GitConfigRemoteRecord.SINGLETON_NAME));
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
                // Which provider secret it names is for WRITE users only: POST /secret-ref.
                ConfigSync.Settings sync = ConfigSync.get();
                JsonObject delivery = new JsonObject();
                delivery.addProperty("mode", sync.mode());
                delivery.addProperty("branch", sync.branch());
                delivery.addProperty("intervalSeconds", sync.intervalSeconds());
                ConfigSync.LastRun last = ConfigSync.lastRun();
                if (last != null) {
                    JsonObject lr = new JsonObject();
                    lr.addProperty("time", last.time());
                    lr.addProperty("ok", last.ok());
                    lr.addProperty("message", last.message());
                    lr.addProperty("commit", last.commit());
                    delivery.add("last", lr);
                }
                o.add("delivery", delivery);
                // Before the counts below, which a rename changes.
                String renameMaster = autoRenameMaster(GitManager.getDataFolderPath(), remote.getBranch(), null);
                // Unsynced-commit count for the header sync indicator.
                o.addProperty("ahead", DataDirGitManager.aheadCount());
                DataDirGitManager.Relation rel = DataDirGitManager.relation();
                o.addProperty("behind", rel.behind());
                o.addProperty("unrelated", rel.unrelated());
                if (rel.unrelated()) {
                    // Shown in the Join confirmation, so nobody deletes another gateway's files unawares.
                    List<String> gone = DataDirGitManager.joinDeletions();
                    JsonArray list = new JsonArray();
                    gone.stream().limit(20).forEach(list::add);
                    o.add("joinDeletes", list);
                    o.addProperty("joinDeletesCount", gone.size());
                }
                o.addProperty("renameMaster", renameMaster);
                // What Remote sync pushes: the checked-out branch, and where it goes.
                String[] target = DataDirGitManager.pushTargetNow(remote);
                o.addProperty("pushFrom", target[0]);
                o.addProperty("pushTo", target[1]);
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
            for (GitUserSshKeyRecord key : GitUserSshKeyRecord.listEverywhere()) {
                JsonObject c = new JsonObject();
                c.addProperty("id", key.getId());
                c.addProperty("type", "SSH");
                c.addProperty("label", key.getKeyName());
                c.addProperty("name", key.getKeyName());
                c.add("check", checkJson(CredentialCheck.get("SSH", key.getId())));
                addFrom(c, GitUserSshKeyRecord.TYPE, String.valueOf(key.getId()), key.definedIn());
                arr.add(c);
            }
            for (GitUserHttpsCredentialRecord cred : GitUserHttpsCredentialRecord.listEverywhere()) {
                JsonObject c = new JsonObject();
                c.addProperty("id", cred.getId());
                c.addProperty("type", "HTTPS");
                c.addProperty("label", cred.getHostPattern() + " — " + cred.getUserName());
                c.addProperty("host", cred.getHostPattern());
                c.addProperty("username", cred.getUserName());
                c.add("check", checkJson(CredentialCheck.get("HTTPS", cred.getId())));
                addFrom(c, GitUserHttpsCredentialRecord.TYPE, String.valueOf(cred.getId()), cred.definedIn());
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
            PolicyGate.checkHost(uri.trim());
            String branch = optString(body, "branch");
            if (branch == null || branch.isBlank()) {
                branch = "main";
            }
            // Create/update the config remote's own credential in place from the inline secret
            // (no user-entered name; auto-derived), then link it. Keeps a single dedicated record.
            boolean ssh = !uri.trim().toLowerCase().startsWith("http");
            boolean reference = "reference".equalsIgnoreCase(optString(body, "mode"));
            GitConfigRemoteRecord existing = GitConfigRemoteRecord.get();
            // A stored secret was given for the old host; it is not sent to a new one unasked.
            boolean newHost = existing != null && existing.getUri() != null
                    && !hostOf(existing.getUri()).equalsIgnoreCase(hostOf(uri));
            String again = newHost ? " The host changed from " + hostOf(existing.getUri()) + " to " + hostOf(uri)
                    + ", so enter it again." : "";
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
                    SecretConfig kept = keptReference(body, rec.hasSecret() ? rec.getSecret() : null, newHost, again);
                    rec.setSSHKeySecret(kept);
                } else {
                    String key = optString(body, "key");
                    if (key != null && !key.isBlank()) {
                        rec.setSSHKey(key);
                    } else if (newHost || !rec.hasSecret()) {
                        throw new RuntimeException("A private key is required." + again);
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
                    SecretConfig kept = keptReference(body, rec.hasSecret() ? rec.getSecret() : null, newHost, again);
                    rec.setPasswordSecret(kept);
                } else {
                    String password = optString(body, "password");
                    if (password != null && !password.isBlank()) {
                        rec.setPassword(password);
                    } else if (newHost || !rec.hasSecret()) {
                        throw new RuntimeException("A password/token is required." + again);
                    }
                }
                rec.save();
                httpsCredentialId = rec.getId();
            }
            DataDirGitManager.saveRemote(uri, branch, sshKeyId, httpsCredentialId);
            // The Credentials tab otherwise shows the last check of the old URL and secret (or the
            // other mode's) until the daily check.
            CredentialCheck.checkSoon(sshKeyId > 0 ? "SSH" : "HTTPS",
                    sshKeyId > 0 ? sshKeyId : httpsCredentialId);
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

    /**
     * The Secret Provider and secret the config remote's credential points at, in the definition
     * {@code collection} names, for the drawer to prefill. WRITE: a READ user sees only that it
     * is a reference.
     */
    private Object handleSecretRef(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String collection = ModeStore.target(optString(body, "collection"));
            return ModeStore.readingFrom(collection, () -> {
                JsonObject o = new JsonObject();
                GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
                SecretConfig secret = null;
                if (remote != null && remote.getSshKeyId() > 0) {
                    GitUserSshKeyRecord key = GitUserSshKeyRecord.findById(remote.getSshKeyId());
                    secret = key == null ? null : key.getSecret();
                } else if (remote != null && remote.getHttpsCredentialId() > 0) {
                    GitUserHttpsCredentialRecord cred = GitUserHttpsCredentialRecord.findById(remote.getHttpsCredentialId());
                    secret = cred == null ? null : cred.getSecret();
                }
                if (secret != null && secret.isReferenced()) {
                    o.addProperty("providerName", secret.getAsReferenced().getProviderName());
                    o.addProperty("secretName", secret.getAsReferenced().getSecretName());
                }
                return o.toString();
            });
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * The reference to save: the one named in the request, or with both names blank the stored
     * reference, so changing the branch or URL path needs nothing re-entered. A new host is asked
     * for again, as an embedded secret is.
     */
    private static SecretConfig keptReference(JsonObject body, SecretConfig stored, boolean newHost, String again) {
        String p = optString(body, "providerName");
        String n = optString(body, "secretName");
        boolean blank = (p == null || p.isBlank()) && (n == null || n.isBlank());
        if (blank && !newHost && stored != null && stored.isReferenced()) {
            return stored;
        }
        if (blank && newHost) {
            throw new RuntimeException("A provider and secret name are required." + again);
        }
        return referencedSecret(body);
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

    /**
     * Whether a saved credential holds this Secret Provider reference and, for HTTPS, belongs to the
     * URL's host. An SSH key has no host: its secret never leaves the gateway, only a signature.
     */
    private static boolean referenceInUse(SecretConfig cfg, String uri, boolean ssh) {
        String provider = cfg.getAsReferenced().getProviderName();
        String name = cfg.getAsReferenced().getSecretName();
        java.util.function.Predicate<SecretConfig> same = c -> c != null && c.isReferenced()
                && provider.equals(c.getAsReferenced().getProviderName())
                && name.equals(c.getAsReferenced().getSecretName());
        if (ssh) {
            return GitUserSshKeyRecord.listEverywhere().stream().anyMatch(k -> same.test(k.getSecret()));
        }
        return GitUserHttpsCredentialRecord.listEverywhere().stream().anyMatch(c -> same.test(c.getSecret())
                && PolicyGate.credentialMismatch(c.getHostPattern() == null ? "" : c.getHostPattern(), uri) == null);
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
            ConfigSync.save(ConfigSync.MODE_OFF, "", ConfigSync.DEFAULT_INTERVAL_SECONDS);
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
            PolicyGate.checkHost(uri);
            boolean ssh = uri != null && !uri.trim().toLowerCase().startsWith("http");
            boolean reference = "reference".equalsIgnoreCase(optString(body, "mode"));
            String username = optString(body, "username");
            String fingerprint = SshTransportConfigCallback.captureFingerprint(() -> {
                if (reference) {
                    // Resolve the referenced secret to plaintext for the test — only one a stored
                    // credential already uses for this host, never any provider secret at any URL.
                    SecretConfig cfg = referencedSecret(body);
                    if (!referenceInUse(cfg, uri, ssh)) {
                        throw new PolicyGate.Refused("Only a secret reference a saved credential already uses for"
                                + " this host can be tested. Save the remote first, or test with the secret typed in.",
                                "reference not in use");
                    }
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
            });
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            if (ssh && fingerprint != null) {
                o.addProperty("fingerprint", fingerprint);
            }
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handlePush(RequestContext req, HttpServletResponse resp) {
        try {
            String[] pushed = DataDirGitManager.push();
            GitConfigRemoteRecord remote = GitConfigRemoteRecord.get();
            GitAudit.record(GitAudit.PUSH, webUser(req).orElse(null), null, pushed[1],
                    DataDirGitManager.headAndBranch()[0],
                    "remote=" + (remote == null ? "" : remote.getUri()) + "; from " + pushed[0]);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("from", pushed[0]);
            o.addProperty("to", pushed[1]);
            return o.toString();
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
                if (editId == 0) {
                    GitAudit.record(GitAudit.CREDENTIAL_ADDED, actor, null, null, null,
                            "SSH key '" + name.trim() + "'");
                }
            } else if ("HTTPS".equalsIgnoreCase(type)) {
                String host = optString(body, "host");
                String username = optString(body, "username");
                if (!PolicyGate.isHostPattern(host)) {
                    throw new IllegalArgumentException("Set the host this credential belongs to, such as"
                            + " github.com: it is only ever sent there.");
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
                if (editId == 0) {
                    GitAudit.record(GitAudit.CREDENTIAL_ADDED, actor, null, null, null,
                            "HTTPS credential " + record.getUserName() + "@" + record.getHostPattern());
                }
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
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            DataDirGitManager.deleteRepo(body != null && optBool(body, "removeRemote"));
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

    // ── Deployment modes and the Git policy (4.1.0) ────────────────────────────────────────────

    private interface Handler {
        Object handle(RequestContext req, HttpServletResponse resp);
    }

    /**
     * A save route whose record writes go to {@code ?saveTo=} — core or the active mode. Left out,
     * each record is saved where it is defined now. From the query string, never the body: the
     * handlers read the body themselves.
     */
    private com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler scoped(Handler h) {
        return (req, resp) -> {
            String target;
            try {
                target = ModeStore.target(queryParam(req, "saveTo"));
            } catch (Exception e) {
                return error(resp, e);
            }
            // The record being edited is loaded from the same definition it is saved to, so a
            // blank secret keeps that mode's secret, not the effective one's.
            String t = target;
            return ModeStore.writingTo(t, () -> ModeStore.readingFrom(t, () -> h.handle(req, resp)));
        };
    }

    /**
     * {@code from} (the definition in use; for a record only another mode has, that mode) and
     * {@code defined} (every collection defining it).
     */
    private static void addFrom(JsonObject o, com.inductiveautomation.ignition.common.resourcecollection.ResourceType type,
                                String name, String from) {
        JsonArray d = definedJson(type, name);
        o.addProperty("from", from != null ? from : d.isEmpty() ? null : d.get(0).getAsString());
        o.add("defined", d);
    }

    /** The collections defining one record, core first. */
    private static JsonArray definedJson(com.inductiveautomation.ignition.common.resourcecollection.ResourceType type,
                                         String name) {
        JsonArray a = new JsonArray();
        ModeStore.definers(type, name).forEach(a::add);
        return a;
    }

    private record RecordRef(com.inductiveautomation.ignition.common.resourcecollection.ResourceType type,
                             String name) {
    }

    /** The record a drawer edits, from {@code type} plus {@code project} or {@code id}/{@code credType}. */
    private static RecordRef recordRef(JsonObject body) {
        String type = optString(body, "type");
        switch (type == null ? "" : type) {
            case "policy" -> {
                return new RecordRef(GitPolicyRecord.TYPE, GitPolicyRecord.NAME);
            }
            case "remote" -> {
                return new RecordRef(GitConfigRemoteRecord.TYPE, GitConfigRemoteRecord.SINGLETON_NAME);
            }
            case "runner" -> {
                return new RecordRef(GitRunnerRecord.TYPE, GitRunnerRecord.NAME);
            }
            case "sync" -> {
                GitSyncRecord r = GitSyncRecord.findByProject(optString(body, "project"));
                return r == null ? null : new RecordRef(GitSyncRecord.TYPE, String.valueOf(r.getId()));
            }
            case "credential" -> {
                return new RecordRef("SSH".equalsIgnoreCase(optString(body, "credType"))
                        ? GitUserSshKeyRecord.TYPE : GitUserHttpsCredentialRecord.TYPE,
                        String.valueOf(optLong(body, "id")));
            }
            default -> throw new IllegalArgumentException("Unknown record type '" + type + "'.");
        }
    }

    /**
     * One record's definition in one collection ({@code ?collection=}), in the shape the record's
     * own GET returns, plus {@code defined}. A collection with no definition answers core's, which
     * is what Create Override would copy.
     */
    private Object handleGetDefinition(RequestContext req, HttpServletResponse resp) {
        try {
            String collection = ModeStore.target(queryParam(req, "collection"));
            JsonObject q = new JsonObject();
            for (String k : List.of("type", "project", "id", "credType")) {
                String v = queryParam(req, k);
                if (v != null) {
                    q.addProperty(k, v);
                }
            }
            String type = optString(q, "type");
            return ModeStore.readingFrom(collection, () -> {
                JsonObject o;
                switch (type == null ? "" : type) {
                    case "policy" -> o = policyJson();
                    case "remote" -> o = JsonParser.parseString(
                            String.valueOf(handleGetRemote(req, resp))).getAsJsonObject();
                    case "runner" -> o = JsonParser.parseString(
                            String.valueOf(handleGetRunner(req, resp))).getAsJsonObject();
                    case "sync" -> {
                        String project = optString(q, "project");
                        GitSyncRecord sync = GitSyncRecord.findByProject(project);
                        o = new JsonObject();
                        GitRunnerRecord runner = GitRunnerRecord.get();
                        o.addProperty("delivery", deliveryOf(runner, sync, project));
                        o.addProperty("keepProjectProperties", runner.keepsProjectProperties(project));
                        o.addProperty("syncBranch", sync == null ? "" : sync.getBranch());
                        o.addProperty("syncIntervalSeconds", sync == null
                                ? GitSyncRecord.DEFAULT_INTERVAL_SECONDS : sync.getIntervalSeconds());
                        o.addProperty("syncUser", sync == null ? "" : sync.getIgnitionUser());
                    }
                    case "credential" -> {
                        long id = optLong(q, "id");
                        boolean ssh = "SSH".equalsIgnoreCase(optString(q, "credType"));
                        o = new JsonObject();
                        if (ssh) {
                            GitUserSshKeyRecord k = GitUserSshKeyRecord.findById(id);
                            if (k != null) {
                                o.addProperty("name", k.getKeyName());
                                o.addProperty("secretMode", k.getSecret() != null
                                        && k.getSecret().isReferenced() ? "reference" : "inline");
                            }
                        } else {
                            GitUserHttpsCredentialRecord c = GitUserHttpsCredentialRecord.findById(id);
                            if (c != null) {
                                o.addProperty("host", c.getHostPattern());
                                o.addProperty("username", c.getUserName());
                                SecretConfig sc = c.getSecret();
                                boolean ref = sc != null && sc.isReferenced();
                                o.addProperty("secretMode", ref ? "reference" : "inline");
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown record type '" + type + "'.");
                }
                RecordRef ref = recordRef(q);
                o.addProperty("collection", collection == null ? ModeStore.CORE : collection);
                o.add("defined", ref == null ? new JsonArray() : definedJson(ref.type(), ref.name()));
                return o.toString();
            });
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /** Create Override: copy the record's definition in {@code from} (default core) into {@code mode}. */
    private Object handleModeOverride(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String mode = ModeStore.target(optString(body, "mode"));
            String from = optString(body, "from");
            RecordRef ref = ModeStore.readingFrom(ModeStore.target(from), () -> recordRef(body));
            // A project with no delivery saved yet has nothing to copy; its first save in the
            // mode creates the record there.
            if (ref != null) {
                ModeStore.createOverride(ref.type(), ref.name(), from, mode);
                if ("remote".equals(optString(body, "type"))) {
                    DataDirGitManager.resyncOrigin();
                }
                DataDirGitManager.commitAllIfDirty("Override " + optString(body, "type") + " in " + mode);
                GitAudit.record(GitAudit.OVERRIDE_CREATED, webUser(req).orElse(req.getActor()), null, null, null,
                        recordLabel(body) + " in " + mode + " (copied from " + (from == null || from.isBlank()
                                ? ModeStore.CORE : from) + ")");
            }
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private static String queryParam(RequestContext req, String name) {
        String q = req.getRequest().getQueryString();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                return java.net.URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** The active mode, the policy's fields with where they come from, and what it refuses. */
    private static JsonObject policyJson() {
        JsonObject o = new JsonObject();
        String mode = ModeStore.activeMode().orElse(null);
        o.addProperty("mode", mode);
        o.addProperty("from", GitPolicyRecord.definedIn());
        o.add("defined", definedJson(GitPolicyRecord.TYPE, GitPolicyRecord.NAME));
        GitPolicy p = GitPolicyRecord.get();
        o.addProperty("pushAllowed", p.push());
        o.addProperty("designerCommit", p.commit());
        o.addProperty("branchSwitch", p.branches());
        o.addProperty("layoutSwitch", p.layout());
        o.addProperty("forcePushAllowed", p.forcePush());
        o.addProperty("allowInsecureRemotes", p.insecureRemotes());
        o.addProperty("githubMergedOnly", p.githubMerged());
        JsonArray gh = new JsonArray();
        p.gitHubHosts().forEach(gh::add);
        o.add("githubHosts", gh);
        JsonArray deliveries = new JsonArray();
        p.deliveries().forEach(deliveries::add);
        o.add("deliveryModes", deliveries);
        JsonArray hosts = new JsonArray();
        p.hosts().forEach(hosts::add);
        o.add("allowedRemoteHosts", hosts);
        JsonObject refused = new JsonObject();
        for (PolicyGate.Action a : PolicyGate.Action.values()) {
            PolicyGate.Refused r = PolicyGate.decide(p, mode, a);
            if (r != null) {
                refused.addProperty(a.name(), r.getMessage());
            }
        }
        for (String d : GitPolicy.DELIVERY_MODES) {
            PolicyGate.Refused r = PolicyGate.decideDelivery(p, mode, d);
            if (r != null) {
                refused.addProperty("delivery:" + d, r.getMessage());
            }
        }
        o.add("refused", refused);
        return o;
    }

    private static List<String> trimmed(List<String> in) {
        return in.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    private Object handleSavePolicy(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            // A field left out stays at its default (null), never "off".
            java.util.function.Function<String, Boolean> flag = k ->
                    body.has(k) && !body.get(k).isJsonNull() ? body.get(k).getAsBoolean() : null;
            List<String> deliveries = body.has("deliveryModes") ? trimmed(stringList(body, "deliveryModes")) : null;
            for (String d : deliveries == null ? List.<String>of() : deliveries) {
                if (!GitPolicy.DELIVERY_MODES.contains(d)) {
                    throw new IllegalArgumentException("Unknown delivery mode '" + d + "'.");
                }
            }
            GitPolicy saved = new GitPolicy(flag.apply("pushAllowed"),
                    flag.apply("designerCommit"), flag.apply("branchSwitch"),
                    flag.apply("layoutSwitch"), deliveries, trimmed(stringList(body, "allowedRemoteHosts")),
                    flag.apply("forcePushAllowed"), flag.apply("allowInsecureRemotes"),
                    flag.apply("githubMergedOnly"),
                    body.has("githubHosts") ? trimmed(stringList(body, "githubHosts")) : null);
            GitPolicyRecord.save(saved);
            DataDirGitManager.commitAllIfDirty("Git policy changed");
            String in = queryParam(req, "saveTo");
            GitAudit.record(GitAudit.POLICY, webUser(req).orElse(req.getActor()), null, null,
                    DataDirGitManager.headAndBranch()[0],
                    "saved to " + (in == null || in.isBlank() ? "where it is defined" : in) + ": " + saved.summary());
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * Deletes the active mode's override of one record so core's value applies:
     * {@code {"type":"policy"|"remote"|"runner"|"sync"|"credential", "id", "credType"}}.
     */
    private Object handleModeReset(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            String type = optString(body, "type");
            // Blank: the active mode's override, as 4.1.0's Reset to core did.
            String mode = ModeStore.target(optString(body, "mode"));
            RecordRef ref = ModeStore.readingFrom(mode, () -> recordRef(body));
            if (ref == null || !ModeStore.removeOverride(ref.type(), ref.name(), mode)) {
                throw new IllegalArgumentException("That record has no override in "
                        + (mode != null ? mode : "the active mode") + ".");
            }
            // The config remote resolves differently now; point origin at it before anything runs.
            if ("remote".equals(type)) {
                DataDirGitManager.resyncOrigin();
            }
            DataDirGitManager.commitAllIfDirty("Remove " + type + " override"
                    + (mode != null ? " in " + mode : ""));
            GitAudit.record(GitAudit.OVERRIDE_REMOVED, webUser(req).orElse(req.getActor()), null, null, null,
                    recordLabel(body) + (mode != null ? " in " + mode : ""));
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }


    /** What an override route names, for an audit row: "policy", "credential 3", "sync Example_A". */
    private static String recordLabel(JsonObject body) {
        String type = optString(body, "type");
        String key = optString(body, "project");
        if (key == null || key.isBlank()) {
            key = body.has("id") && !body.get("id").isJsonNull() ? body.get("id").getAsString() : "";
        }
        return (type == null ? "record" : type) + (key.isBlank() ? "" : " " + key);
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
            String[] hb = DataDirGitManager.headAndBranch();
            GitAudit.record(GitAudit.RESTORE, webUser(req).orElse(req.getActor()), null, hb[1], hash,
                    "config restored to this commit");
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
            GitAudit.record(GitAudit.RESTORE, webUser(req).orElse(req.getActor()), null,
                    DataDirGitManager.headAndBranch()[1], hash, "config updated from the remote");
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("hash", hash);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleSaveConfigDelivery(RequestContext req, HttpServletResponse resp) {
        try {
            JsonObject body = new Gson().fromJson(req.readBody(), JsonObject.class);
            if (GitConfigRemoteRecord.get() == null) {
                throw new RuntimeException("Set the config repository remote first.");
            }
            int interval = body.has("intervalSeconds") && !body.get("intervalSeconds").isJsonNull()
                    ? body.get("intervalSeconds").getAsInt() : ConfigSync.DEFAULT_INTERVAL_SECONDS;
            PolicyGate.checkDelivery(optString(body, "mode"));
            ConfigSync.save(optString(body, "mode"), optString(body, "branch"), interval);
            return ok();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private Object handleConfigSyncNow(RequestContext req, HttpServletResponse resp) {
        try {
            return syncOutcome(ConfigSync.syncNow());
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private String error(HttpServletResponse resp, Exception e) {
        if (e instanceof PolicyGate.Refused) {
            // Expected, and already worded for the reader: no stack trace in the gateway log.
            logger.info("Refused by the Git policy: {}", e.getMessage());
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        } else if (e instanceof SecurityException) {
            logger.info("Refused: {}", e.getMessage());
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        } else if (e instanceof IllegalArgumentException) {
            // A request the gateway cannot act on (an unknown mode or record type): the caller's.
            logger.warn("Config-versioning route refused a request", e);
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        } else {
            logger.error("Config-versioning route error", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        JsonObject o = new JsonObject();
        // The page names repository paths, never where the gateway is installed; the log has both.
        String m = SafePaths.redact(e.getMessage() == null ? e.toString() : e.getMessage(),
                GitManager.getDataFolderPath());
        // A raw JGit failure in plain words, the original kept for the page's console.
        String plain = m.contains(PullGuard.DETAILS) ? null : PullGuard.plainOrNull(e);
        o.addProperty("error", plain == null ? m : plain + PullGuard.DETAILS + m);
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
                GitAudit.record(GitAudit.CREDENTIAL_REMOVED, actor, null, null, null,
                        "SSH key '" + record.getKeyName() + "'");
            } else if ("HTTPS".equalsIgnoreCase(type)) {
                GitUserHttpsCredentialRecord record =
                        GitUserHttpsCredentialRecord.findByIdAndUser(id, actor);
                if (record == null) {
                    throw new RuntimeException("No such credential for this user.");
                }
                record.delete();
                GitAudit.record(GitAudit.CREDENTIAL_REMOVED, actor, null, null, null,
                        "HTTPS credential " + record.getUserName() + "@" + record.getHostPattern());
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
            if (!ModeStore.reading() && !runner.pruneMissing(projects.stream()
                    .map(GitProjectManager.ProjectStatus::name).toList()).isEmpty()) {
                runner.save();
            }

            JsonArray arr = new JsonArray();
            // Combined: every project is the one repository, so ask once.
            Map<Path, int[]> tracking = new HashMap<>();
            for (GitProjectManager.ProjectStatus p : projects) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.name());
                o.addProperty("title", p.title());
                o.addProperty("versioned", p.versioned());
                o.addProperty("branch", p.branch());
                if (p.versioned() && p.branch() != null) {
                    int[] ab = tracking.computeIfAbsent(GitManager.repoFor(p.name()).root(),
                            r -> {
                                int[] x = aheadBehind(r);
                                return x == null ? new int[0] : x;
                            });
                    if (ab.length == 2) {
                        o.addProperty("ahead", ab[0]);
                        o.addProperty("behind", ab[1]);
                    }
                }
                o.addProperty("remoteName", p.remoteName());
                o.addProperty("remoteUrl", p.remoteUrl());
                o.addProperty("changes", p.changes());
                o.addProperty("error", p.error());
                o.addProperty("imagePrefix", GitProjectsConfigRecord.imagePrefixFor(p.name()));
                o.addProperty("credentialIssue", CredentialCheck.issueFor(p.name()));
                if (!GitManager.combined() && p.versioned() && p.remoteUrl() != null && !p.remoteUrl().isBlank()) {
                    GitSyncRecord s0 = GitSyncRecord.findByProject(p.name());
                    o.addProperty("renameMaster", autoRenameMaster(GitManager.repoFor(p.name()).root(),
                            s0 != null && s0.isEnabled() && !s0.getBranch().isBlank() ? s0.getBranch() : null,
                            p.name()));
                }

                // A runner delivery on a runner nobody switched on delivers nothing; say so.
                o.addProperty("runnerEnabled", runnerOn);
                GitSyncRecord sync = GitSyncRecord.findByProject(p.name());
                o.addProperty("delivery", deliveryOf(runner, sync, p.name()));
                o.addProperty("keepProjectProperties", runner.keepsProjectProperties(p.name()));
                o.addProperty("syncBranch", sync == null ? "" : sync.getBranch());
                o.addProperty("syncIntervalSeconds", sync == null
                        ? GitSyncRecord.DEFAULT_INTERVAL_SECONDS : sync.getIntervalSeconds());
                o.addProperty("syncUser", sync == null ? "" : sync.getIgnitionUser());
                o.addProperty("syncFrom", sync == null ? null : sync.definedIn());
                o.add("syncDefined", sync == null ? new JsonArray()
                        : definedJson(GitSyncRecord.TYPE, String.valueOf(sync.getId())));
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.addProperty("layout", GitManager.combined() ? "combined" : "separate");
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
            // The drawer sends its delivery with the init, so leaving the page mid-save cannot
            // leave a linked project with Delivery Off. Checked first, so a bad value inits nothing.
            String delivery = body.has("delivery") ? checkDelivery(body) : null;
            boolean ok;
            if (url == null || url.isBlank()) {
                ok = scriptModule.initializeLocalProject(project, actor);
            } else {
                PolicyGate.checkHost(url.trim());
                long sshKeyId = body.has("sshKeyId") && !body.get("sshKeyId").isJsonNull()
                        ? body.get("sshKeyId").getAsLong() : 0L;
                long httpsId = body.has("httpsCredentialId") && !body.get("httpsCredentialId").isJsonNull()
                        ? body.get("httpsCredentialId").getAsLong() : 0L;
                ok = scriptModule.initializeProject(project, url.trim(), actor, sshKeyId, httpsId,
                        optString(body, "branch"));
                if (ok && (sshKeyId > 0 || httpsId > 0)) {
                    CredentialCheck.checkSoon(sshKeyId > 0 ? "SSH" : "HTTPS",
                            sshKeyId > 0 ? sshKeyId : httpsId);
                }
            }
            if (ok && delivery != null) {
                applyDelivery(body, project, delivery, actor);
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
            // Always the signed-in user's own record, and only one of their own credentials.
            String owner = req.getActor();
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
            String project = SafePaths.projectName(optString(body, "project"));
            String delivery = checkDelivery(body);
            applyDelivery(body, project, delivery, req.getActor());
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("delivery", delivery);
            return o.toString();
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    private static String checkDelivery(JsonObject body) {
        String delivery = optString(body, "delivery");
        if (!List.of("off", "runner-release", "runner-repo", "sync-pull", "sync-replace")
                .contains(delivery)) {
            throw new RuntimeException("Unknown delivery '" + delivery + "'.");
        }
        PolicyGate.checkDelivery(delivery);
        return delivery;
    }

    private static void applyDelivery(JsonObject body, String project, String delivery, String actor) {
        if (GitManager.combined()) {
            // One repository: pulls are the gateway's delivery. A release still replaces one
            // project. The project's sync record is left as it was, for a switch back to Separate.
            if (!delivery.equals("off") && !delivery.equals("runner-release")) {
                throw new RuntimeException("In the Combined layout a project's delivery is Off or Runner —"
                        + " release; pulls are set once for the gateway (Configure versioning › Delivery).");
            }
            GitRunnerRecord runner = GitRunnerRecord.get();
            if (delivery.equals("off")) {
                runner.clearMode(project);
            } else {
                runner.setMode(project, GitRunnerRecord.MODE_RELEASE);
            }
            keepProjectProperties(body, runner, project);
            runner.save();
            return;
        }
        boolean pulls = delivery.startsWith("sync-") || delivery.equals("runner-repo");
        if (pulls) {
            // The list only holds folders with a root project.json, so a missing row is not
            // a missing remote.
            GitProjectManager.ProjectStatus status = GitProjectManager.listProjectStatus().stream()
                    .filter(p -> p.name().equals(project)).findFirst().orElse(null);
            if (status == null) {
                throw new RuntimeException("'" + project + "' has no project.json at the root of its"
                        + " folder, so it is not an Ignition project as checked out.");
            }
            if (status.remoteUrl() == null || status.remoteUrl().isBlank()) {
                throw new RuntimeException("'" + project + "' has no remote to pull from; set one first.");
            }
        }

        // Unattended, so it authenticates as a named user's stored credential rather than whoever
        // is in a Designer: the admin saving it (the default), or the owner already set. Naming
        // anyone else would let this admin's delivery use that user's credential.
        String owner = null;
        if (pulls) {
            owner = optString(body, "ignitionUser");
            owner = owner == null || owner.isBlank() ? actor : owner.trim();
            if (owner == null) {
                throw new SecurityException("Sign in to set a delivery that pulls.");
            }
            GitSyncRecord existing = GitSyncRecord.findByProject(project);
            String current = existing == null ? null : existing.getIgnitionUser();
            if (!owner.equalsIgnoreCase(actor) && (current == null || !owner.equalsIgnoreCase(current))) {
                throw new SecurityException("A delivery can authenticate as you (" + actor + ") or as the user"
                        + " already set" + (current == null || current.isBlank() ? "" : " (" + current + ")")
                        + ", not as '" + owner + "'. That user can set it themselves.");
            }
        }

        GitRunnerRecord runner = GitRunnerRecord.get();
        switch (delivery) {
            case "runner-release" -> runner.setMode(project, GitRunnerRecord.MODE_RELEASE);
            case "runner-repo" -> runner.setMode(project, GitRunnerRecord.MODE_REPO);
            default -> runner.clearMode(project);
        }
        keepProjectProperties(body, runner, project);
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
                sync.setIgnitionUser(owner);
            }
            sync.save();
        }
    }

    /** Stored whatever the delivery; only Sync — replace and Release read it. Absent leaves it. */
    private static void keepProjectProperties(JsonObject body, GitRunnerRecord runner, String project) {
        if (body.has("keepProjectProperties") && !body.get("keepProjectProperties").isJsonNull()) {
            runner.setKeepProjectProperties(project, body.get("keepProjectProperties").getAsBoolean());
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
            o.addProperty("from", cfg.definedIn());
            o.add("defined", definedJson(GitRunnerRecord.TYPE, GitRunnerRecord.NAME));
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
            // A disabled record is kept only for a runner repo update's branch and credential.
            if (!cfg.isEnabled()) {
                throw new RuntimeException("Sync is off for '" + project + "'; set a Sync delivery first.");
            }
            return syncOutcome(SyncScheduler.syncNow(cfg));
        } catch (Exception e) {
            return error(resp, e);
        }
    }

    /**
     * A Sync now result for the page: {@code outcome} is "ok" (pulled, replaced or up to date),
     * "failed", or "warning" (it ran but did not pull: local changes, not merged on GitHub, behind,
     * already running), so a run that changed nothing is not shown as a success.
     */
    private static String syncOutcome(String result) {
        String r = result == null ? "" : result;
        String outcome = r.startsWith("failed") ? "failed"
                : r.equals("up to date") || r.startsWith("pulled ") || r.startsWith("replaced, ")
                        || r.startsWith("Pulled ") || r.startsWith("Replaced config") || r.startsWith("Rolled back")
                        ? "ok" : "warning";
        JsonObject o = new JsonObject();
        o.addProperty("ok", !outcome.equals("failed"));
        o.addProperty("outcome", outcome);
        o.addProperty("result", r);
        return o.toString();
    }

    private static boolean optBool(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() && body.get(key).getAsBoolean();
    }

}
