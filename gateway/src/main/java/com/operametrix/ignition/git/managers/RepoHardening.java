package com.operametrix.ignition.git.managers;

import com.operametrix.ignition.git.records.GitConfigRemoteRecord;
import com.operametrix.ignition.git.records.GitProjectsConfigRecord;
import com.operametrix.ignition.git.records.GitRemoteCredentialsRecord;
import com.operametrix.ignition.git.records.GitUserHttpsCredentialRecord;
import com.operametrix.ignition.git.records.GitUserSshKeyRecord;
import com.operametrix.ignition.git.records.ModeStore;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Settings every repository the module creates carries ({@link #harden}), and the one-time 4.3.0
 * start-up pass that brings repositories and records from earlier versions up to them
 * ({@link #atStartup}). Every step is idempotent and logs what it changed.
 */
public final class RepoHardening {

    private static final Logger logger = LoggerFactory.getLogger(RepoHardening.class);

    private RepoHardening() {
    }

    /**
     * TLS certificates verified (versions before 4.3.0 turned that off), a symbolic link in a
     * checkout written as a plain file, so a branch cannot point the working tree elsewhere, and
     * HTTP redirects not followed, so a credential only goes to the remote's own host.
     *
     * @return whether anything changed
     */
    public static boolean harden(Repository repo) throws IOException {
        StoredConfig config = repo.getConfig();
        boolean changed = false;
        if (config.getString("http", null, "sslVerify") != null) {
            config.unset("http", null, "sslVerify");
            changed = true;
        }
        if (config.getBoolean("core", null, "symlinks", true)) {
            config.setBoolean("core", null, "symlinks", false);
            changed = true;
        }
        // A redirect would take the credential with it to wherever the server points.
        if (!"false".equals(config.getString("http", null, "followRedirects"))) {
            config.setString("http", null, "followRedirects", "false");
            changed = true;
        }
        if (changed) {
            config.save();
        }
        return changed;
    }

    /**
     * Removes a password written into any remote URL of {@code repo}.
     *
     * @return the remotes changed
     */
    static List<String> stripRemotePasswords(Repository repo) throws IOException {
        StoredConfig config = repo.getConfig();
        List<String> changed = new ArrayList<>();
        for (String remote : config.getSubsections("remote")) {
            for (String key : List.of("url", "pushurl")) {
                String[] values = config.getStringList("remote", remote, key);
                boolean any = false;
                for (int i = 0; i < values.length; i++) {
                    String clean = RemoteUrls.withoutPassword(values[i]);
                    if (!clean.equals(values[i])) {
                        values[i] = clean;
                        any = true;
                    }
                }
                if (any) {
                    config.setStringList("remote", remote, key, List.of(values));
                    changed.add(remote);
                }
            }
        }
        if (!changed.isEmpty()) {
            config.save();
        }
        return changed;
    }

    /** Every repository the module may have made: the data directory's and each project's own. */
    static List<Path> repositories(Path dataDir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(dataDir.resolve(".git"))) {
            out.add(dataDir);
        }
        Path projects = dataDir.resolve("projects");
        if (Files.isDirectory(projects)) {
            try (Stream<Path> s = Files.list(projects)) {
                s.filter(p -> Files.isDirectory(p.resolve(".git"))).sorted().forEach(out::add);
            }
        }
        return out;
    }

    /** Runs every step; a failing step is logged and the rest still run. */
    public static void atStartup() {
        Path dataDir = GitManager.getDataFolderPath();
        step("repository settings", () -> repositorySettings(dataDir));
        step("remote URLs", () -> remotePasswords(dataDir));
        step("legacy SSH keys", RepoHardening::legacySshKeys);
        step("HTTPS credential hosts", RepoHardening::credentialHosts);
        step("the never-versioned paths", RepoHardening::denyBlock);
    }

    private interface Step {
        void run() throws Exception;
    }

    private static void step(String what, Step s) {
        try {
            s.run();
        } catch (Exception e) {
            logger.error("Start-up hardening of {} failed; it is tried again at the next start.", what, e);
        }
    }

    private static void repositorySettings(Path dataDir) throws IOException {
        int tls = 0;
        int touched = 0;
        for (Path root : repositories(dataDir)) {
            try (Git git = GitManager.getGit(root)) {
                boolean sslOff = git.getRepository().getConfig().getString("http", null, "sslVerify") != null;
                if (harden(git.getRepository())) {
                    touched++;
                    tls += sslOff ? 1 : 0;
                }
            }
        }
        if (tls > 0) {
            logger.info("TLS certificate checks turned back on in {} repositories (http.sslVerify removed)."
                    + " A server with a self-signed certificate needs its CA in the gateway's Java"
                    + " truststore.", tls);
        }
        if (touched > 0) {
            logger.info("Symbolic links in checkouts and HTTP redirects are now off in {} repositories.", touched);
        }
    }

    private static void remotePasswords(Path dataDir) throws IOException {
        for (Path root : repositories(dataDir)) {
            try (Git git = GitManager.getGit(root)) {
                for (String remote : stripRemotePasswords(git.getRepository())) {
                    logger.warn("Removed a password from the URL of remote '{}' in {}. Enter it again as a"
                            + " credential.", remote, dataDir.relativize(root).toString().isEmpty()
                            ? "the gateway repository" : dataDir.relativize(root));
                }
            }
        }
        inEachCollection(c -> {
            GitConfigRemoteRecord r = GitConfigRemoteRecord.get();
            if (r != null && definedIn(GitConfigRemoteRecord.TYPE, GitConfigRemoteRecord.SINGLETON_NAME, c)) {
                String clean = RemoteUrls.withoutPassword(r.getUri());
                if (clean != null && !clean.equals(r.getUri())) {
                    r.setUri(clean);
                    r.save();
                    logger.warn("Removed a password from the gateway repository's remote URL ({}). Enter it"
                            + " again as a credential on the Versioning page.", c);
                }
            }
        });
    }

    private static void legacySshKeys() {
        inEachCollection(c -> {
            for (GitUserSshKeyRecord k : GitUserSshKeyRecord.listAll()) {
                if (definedIn(GitUserSshKeyRecord.TYPE, String.valueOf(k.getId()), c) && k.migrateLegacyKey()) {
                    logger.info("SSH key {} '{}' ({}) is now stored encrypted.", k.getId(), k.getKeyName(), c);
                }
            }
        });
    }

    /**
     * Before 4.3.0 an HTTPS credential's host field could be a label bound to no host (no dot, or
     * not a host at all: "token v1.2"). It is bound now to the one host the remotes using it point
     * at; with none or several it is left, and refused until someone sets its host.
     */
    private static void credentialHosts() {
        inEachCollection(c -> {
            for (GitUserHttpsCredentialRecord cred : GitUserHttpsCredentialRecord.listAll()) {
                String field = cred.getHostPattern() == null ? "" : cred.getHostPattern().trim().toLowerCase(Locale.ROOT);
                if (PolicyGate.isHostPattern(field) && (field.contains(".") || field.equals("localhost"))
                        || !definedIn(GitUserHttpsCredentialRecord.TYPE, String.valueOf(cred.getId()), c)) {
                    continue;
                }
                Set<String> hosts = hostsUsing(cred.getId());
                if (hosts.size() == 1) {
                    String host = hosts.iterator().next();
                    cred.setHostPattern(host);
                    cred.save();
                    logger.info("HTTPS credential {} ('{}', {}) is now bound to {}, the host its remotes use.",
                            cred.getId(), field, c, host);
                } else {
                    logger.warn("HTTPS credential {} ('{}', {}) has no host and its remotes use {}. It is not"
                            + " sent anywhere until its host is set (Versioning > Credentials, or the"
                            + " Designer's credentials popup).", cred.getId(), field, c,
                            hosts.isEmpty() ? "none" : String.join(", ", hosts));
                }
            }
        });
    }

    /** The hosts of the remotes an HTTPS credential is attached to (projects and the gateway's). */
    private static Set<String> hostsUsing(long credentialId) {
        Set<String> hosts = new LinkedHashSet<>();
        for (GitRemoteCredentialsRecord ref : GitRemoteCredentialsRecord.listByHttpsCredentialId(credentialId)) {
            GitProjectsConfigRecord project = GitProjectsConfigRecord.findById(ref.getProjectId());
            if (project == null) {
                continue;
            }
            try {
                String url = GitManager.getRemoteUrl(GitManager.getProjectFolderPath(project.getProjectName()),
                        ref.getRemoteName());
                String host = PolicyGate.hostOf(url);
                if (!host.isEmpty()) {
                    hosts.add(host);
                }
            } catch (Exception e) {
                // A project without its repository uses nothing.
            }
        }
        GitConfigRemoteRecord gw = GitConfigRemoteRecord.get();
        if (gw != null && gw.getHttpsCredentialId() == credentialId && !PolicyGate.hostOf(gw.getUri()).isEmpty()) {
            hosts.add(PolicyGate.hostOf(gw.getUri()));
        }
        return hosts;
    }

    /**
     * Ends the data directory's {@code .gitignore} with the deny block, and takes out of the index
     * anything already tracked that it covers (the commit history still holds those files).
     */
    private static void denyBlock() throws Exception {
        if (!DataDirGitManager.isInitialized()) {
            return;
        }
        Path gitignore = DataDirGitManager.dataDir().resolve(".gitignore");
        synchronized (DataDirGitManager.lock()) {
            String before = Files.exists(gitignore) ? Files.readString(gitignore, StandardCharsets.UTF_8) : "";
            String after = IgnoreRules.withDenyBlock(before);
            List<String> untracked = new ArrayList<>();
            try (Git git = GitManager.getGit(DataDirGitManager.dataDir())) {
                if (!after.equals(before)) {
                    Files.writeString(gitignore, after, StandardCharsets.UTF_8);
                }
                List<String> denied = trackedAndDenied(git.getRepository());
                for (String path : denied) {
                    git.rm().setCached(true).addFilepattern(path).call();
                    untracked.add(path);
                }
            }
            if (!untracked.isEmpty()) {
                logger.warn("Stopped versioning {} key, certificate or local config file(s) the repository was tracking: {}."
                        + " Earlier commits still hold them; rotate those keys if the repository left this"
                        + " gateway.", untracked.size(), String.join(", ", untracked));
            }
            if (!after.equals(before) || !untracked.isEmpty()) {
                DataDirGitManager.commitAllIfDirty("Never version keys, certificates or local config");
            }
            if (untracked.stream().anyMatch(p -> p.endsWith("/users.json") && p.contains("/user-source/"))) {
                String msg = "Stopped versioning internal users' password hashes; older commits still contain"
                        + " them — rotate those passwords if the repository was shared.";
                logger.warn(msg);
                com.operametrix.ignition.git.automation.GitEvents.fire(
                        com.operametrix.ignition.git.automation.GitEvent.of("ignore").config().warning(msg));
            }
        }
    }

    /** Index entries the deny block matches, by path or by any parent folder. */
    static List<String> trackedAndDenied(Repository repo) throws IOException {
        List<String> out = new ArrayList<>();
        DirCache index = repo.readDirCache();
        for (int i = 0; i < index.getEntryCount(); i++) {
            String path = index.getEntry(i).getPathString();
            if (IgnoreRules.denied(path)) {
                out.add(path);
            }
        }
        return out;
    }

    /** Runs {@code work} once per collection (core, then each mode), reading and writing that one. */
    private static void inEachCollection(java.util.function.Consumer<String> work) {
        List<String> all = new ArrayList<>(ModeStore.modes());
        all.add(0, ModeStore.CORE);
        for (String c : new LinkedHashSet<>(all)) {
            ModeStore.writingTo(c, () -> ModeStore.readingFrom(c, () -> {
                work.accept(c);
                return null;
            }));
        }
    }

    private static boolean definedIn(com.inductiveautomation.ignition.common.resourcecollection.ResourceType type,
                                     String name, String collection) {
        return ModeStore.definers(type, name).contains(collection);
    }
}
