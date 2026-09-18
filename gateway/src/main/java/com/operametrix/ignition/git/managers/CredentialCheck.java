package com.operametrix.ignition.git.managers;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.operametrix.ignition.git.SshTransportConfigCallback;
import com.operametrix.ignition.git.records.GitConfigRemoteRecord;
import com.operametrix.ignition.git.records.GitProjectsConfigRecord;
import com.operametrix.ignition.git.records.GitRemoteCredentialsRecord;
import com.operametrix.ignition.git.records.GitUserHttpsCredentialRecord;
import com.operametrix.ignition.git.records.GitUserSshKeyRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What each stored credential can still do: when a GitHub token expires, and whether it can read
 * and push each remote that uses it. A fine-grained token is opaque, so both are asked of the host
 * — the same host the token is sent to on every pull, so nothing new sees it.
 *
 * <p>Results are held in memory: they are re-derived at startup, daily, and whenever a credential
 * is added or checked by hand.
 */
public final class CredentialCheck {

    private static final Logger logger = LoggerFactory.getLogger(CredentialCheck.class);

    /** Can this credential read, and push to, one remote that uses it. */
    public record Reach(String target, boolean read, boolean push, String error) {}

    /**
     * {@code expires}: ISO date, {@code "never"}, or null when the host cannot say (SSH keys, hosts
     * other than GitHub). {@code rejected}: the host refused the token outright — expired or revoked.
     */
    public record Result(long checkedAt, String account, String expires, boolean rejected,
                         List<Reach> reach) {}

    /** The GitHub header carrying a personal access token's expiry, e.g. "2026-12-01 00:00:00 UTC". */
    private static final String EXPIRY_HEADER = "github-authentication-token-expiration";

    /** Within this many days an expiry is flagged on the Projects tab too. */
    public static final int WARN_DAYS = 14;

    private static final Map<String, Result> results = new ConcurrentHashMap<>();
    private static ScheduledExecutorService executor;

    private CredentialCheck() {
    }

    public static synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "git-credential-check");
            t.setDaemon(true);
            return t;
        });
        // Delayed so startup never waits on the network.
        executor.scheduleWithFixedDelay(CredentialCheck::checkAll, 30, TimeUnit.DAYS.toSeconds(1),
                TimeUnit.SECONDS);
    }

    public static synchronized void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
        results.clear();
    }

    private static String key(String type, long id) {
        return type.toUpperCase() + "-" + id;
    }

    public static Result get(String type, long id) {
        return results.get(key(type, id));
    }

    /** Queues a check, for a credential just added. */
    public static synchronized void checkSoon(String type, long id) {
        if (executor != null) {
            executor.execute(() -> check(type, id));
        }
    }

    private static void checkAll() {
        try {
            for (GitUserSshKeyRecord k : GitUserSshKeyRecord.listAll()) {
                check("SSH", k.getId());
            }
            for (GitUserHttpsCredentialRecord c : GitUserHttpsCredentialRecord.listAll()) {
                check("HTTPS", c.getId());
            }
        } catch (Throwable t) {
            // scheduleWithFixedDelay cancels the task for good if this propagates.
            logger.error("Credential check failed.", t);
        }
    }

    /** Checks one credential now and keeps the result. */
    public static Result check(String type, long id) {
        boolean ssh = "SSH".equalsIgnoreCase(type);
        String account = null;
        String expires = null;
        boolean rejected = false;
        String sshKey = null;
        String user = null;
        String password = null;

        if (ssh) {
            GitUserSshKeyRecord k = GitUserSshKeyRecord.findById(id);
            if (k == null) {
                results.remove(key(type, id));
                return null;
            }
            sshKey = k.getSSHKey();
        } else {
            GitUserHttpsCredentialRecord c = GitUserHttpsCredentialRecord.findById(id);
            if (c == null) {
                results.remove(key(type, id));
                return null;
            }
            user = c.getUserName();
            password = c.getPassword();
            if (isGitHub(c.getHostPattern())) {
                try {
                    HttpResponse<String> r = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10)).build()
                            .send(HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                                    .timeout(Duration.ofSeconds(15))
                                    .header("Authorization", "Bearer " + password)
                                    .header("Accept", "application/vnd.github+json")
                                    .header("User-Agent", "ignition-git-module")
                                    .GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() == 401) {
                        rejected = true;
                    } else if (r.statusCode() == 200) {
                        JsonObject body = new Gson().fromJson(r.body(), JsonObject.class);
                        account = body != null && body.has("login") ? body.get("login").getAsString() : null;
                        Optional<String> h = r.headers().firstValue(EXPIRY_HEADER);
                        expires = h.map(v -> v.trim().substring(0, Math.min(10, v.trim().length())))
                                .orElse("never");
                    }
                } catch (Exception e) {
                    logger.debug("Could not ask GitHub about credential {}.", id, e);
                }
            }
        }

        List<Reach> reach = new ArrayList<>();
        for (Map.Entry<String, String[]> t : targets(ssh, id).entrySet()) {
            reach.add(probe(t.getKey(), t.getValue()[0], Path.of(t.getValue()[1]), sshKey, user, password));
        }
        Result result = new Result(System.currentTimeMillis(), account, expires, rejected, reach);
        results.put(key(type, id), result);
        return result;
    }

    private static boolean isGitHub(String host) {
        return host != null && host.trim().toLowerCase().matches("(.*[@/.])?github\\.com([/:].*)?");
    }

    /** Remotes that authenticate with this credential: target name to {url, local repository}. */
    private static Map<String, String[]> targets(boolean ssh, long id) {
        Map<String, String[]> out = new LinkedHashMap<>();
        List<GitRemoteCredentialsRecord> refs = ssh
                ? GitRemoteCredentialsRecord.listBySshKeyId(id)
                : GitRemoteCredentialsRecord.listByHttpsCredentialId(id);
        for (GitRemoteCredentialsRecord ref : refs) {
            GitProjectsConfigRecord project = GitProjectsConfigRecord.findById(ref.getProjectId());
            if (project == null || out.containsKey(project.getProjectName())) {
                continue;
            }
            try {
                Path folder = GitManager.getProjectFolderPath(project.getProjectName());
                String url = GitManager.getRemoteUrl(folder, ref.getRemoteName());
                if (url != null) {
                    out.put(project.getProjectName(), new String[] {url, folder.toString()});
                }
            } catch (Exception e) {
                logger.debug("No remote to check for {}.", project.getProjectName(), e);
            }
        }
        GitConfigRemoteRecord config = GitConfigRemoteRecord.get();
        if (config != null && (ssh ? config.getSshKeyId() : config.getHttpsCredentialId()) == id) {
            out.put("gateway config", new String[] {config.getUri(), DataDirGitManager.dataDir().toString()});
        }
        return out;
    }

    /**
     * Read: fetch's ref advertisement. Push: receive-pack's — a host refuses a token without write
     * access there, before anything is sent, so nothing is pushed.
     */
    private static Reach probe(String target, String url, Path repoDir, String sshKey, String user,
                               String password) {
        boolean read = false;
        try (Git git = Git.open(repoDir.toFile())) {
            Repository repo = git.getRepository();
            try (Transport t = open(repo, url, sshKey, user, password)) {
                t.openFetch().close();
                read = true;
            }
            try (Transport t = open(repo, url, sshKey, user, password);
                 PushConnection c = t.openPush()) {
                return new Reach(target, true, true, null);
            }
        } catch (Exception e) {
            return new Reach(target, read, false, read ? null : e.getMessage());
        }
    }

    private static Transport open(Repository repo, String url, String sshKey, String user,
                                  String password) throws Exception {
        Transport t = Transport.open(repo, new URIish(url));
        if (sshKey != null) {
            new SshTransportConfigCallback(sshKey).configure(t);
        } else {
            t.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                    user == null ? "" : user, password == null ? "" : password));
        }
        return t;
    }

    /**
     * What the Projects tab should say about the credential behind a project, or null. Worst
     * first: refused outright, expired, cannot read, expiring soon.
     */
    public static String issueFor(String projectName) {
        String soon = null;
        for (Result r : results.values()) {
            Optional<Reach> mine = r.reach().stream()
                    .filter(x -> x.target().equals(projectName)).findFirst();
            if (mine.isEmpty()) {
                continue;
            }
            if (r.rejected()) {
                return "Credential rejected — expired or revoked";
            }
            if (r.expires() != null && !"never".equals(r.expires())) {
                LocalDate date = LocalDate.parse(r.expires());
                if (!date.isAfter(LocalDate.now())) {
                    return "Credential expired";
                }
                if (date.isBefore(LocalDate.now().plusDays(WARN_DAYS + 1))) {
                    soon = "Credential expires " + String.format("%02d/%02d/%d",
                            date.getDayOfMonth(), date.getMonthValue(), date.getYear());
                }
            }
            if (!mine.get().read()) {
                return "Credential can't read the remote";
            }
        }
        return soon;
    }
}
