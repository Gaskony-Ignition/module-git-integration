package com.operametrix.ignition.git.managers;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonArray;
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
 * What each stored credential can still do: when a GitHub token expires, what it is allowed to
 * touch, and whether it can read and push each remote that uses it. A token is opaque, so all of it
 * is asked of the host — the same host the token is sent to on every pull, so nothing new sees it.
 *
 * <p>Results are held in memory: they are re-derived at startup, daily, and whenever a credential is
 * added or checked by hand. Every failure is kept and shown, because "could not ask" and "nothing to
 * report" look identical otherwise, and a check that silently reports nothing looks broken.
 */
public final class CredentialCheck {

    private static final Logger logger = LoggerFactory.getLogger(CredentialCheck.class);

    /** Can this credential read, and push to, one remote that uses it. */
    public record Reach(String target, boolean read, boolean push, String error) {}

    /**
     * What the token is allowed to touch at all, which is not the same question as which of this
     * gateway's projects use it. {@code kind} ∈ classic | fine-grained; {@code summary} is the line
     * shown, {@code repos} the names behind it.
     */
    public record Scope(String kind, String summary, List<String> repos) {}

    /**
     * {@code expires}: ISO date, {@code "never"}, or null when the host cannot say (SSH keys, hosts
     * other than GitHub). {@code rejected}: the host refused the token outright — expired or revoked.
     * {@code error}: why the host could not be asked, shown in place of the expiry.
     */
    public record Result(long checkedAt, String account, String expires, boolean rejected,
                         String error, Scope scope, List<Reach> reach) {}

    /** One remote to probe: a project name (or "gateway config"), its URL and local repository. */
    private record Target(String name, String url, String repoDir, String error) {}

    /** The GitHub header carrying a personal access token's expiry, e.g. "2026-12-01 00:00:00 UTC". */
    private static final String EXPIRY_HEADER = "github-authentication-token-expiration";

    /** Present only for a classic token, which is how the two kinds are told apart. */
    private static final String SCOPES_HEADER = "x-oauth-scopes";

    private static final String API = "https://api.github.com";

    /** Within this many days an expiry is flagged on the Projects tab too. */
    public static final int WARN_DAYS = 14;

    /** Seconds. A dead host must not hold a request thread, or the Check button looks hung. */
    private static final int NET_TIMEOUT = 10;

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
        String sshKey = null;
        String user = null;
        String password = null;
        String host = null;

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
            host = c.getHostPattern();
        }

        List<Target> targets = targets(ssh, id);
        List<Reach> reach = new ArrayList<>();
        for (Target t : targets) {
            reach.add(t.error() != null
                    ? new Reach(t.name(), false, false, t.error())
                    : probe(t, sshKey, user, password));
        }

        // The host field is a label the user typed, so a remote this credential actually reaches is
        // the better evidence of who the host is.
        boolean github = !ssh && (isGitHub(host)
                || targets.stream().anyMatch(t -> isGitHub(t.url())));
        Result result = github
                ? askGitHub(password, reach)
                : new Result(System.currentTimeMillis(), null, null, false, null, null, reach);
        results.put(key(type, id), result);
        return result;
    }

    /** Expiry, account and scope, straight from GitHub. Any failure comes back as {@code error}. */
    private static Result askGitHub(String token, List<Reach> reach) {
        long now = System.currentTimeMillis();
        try {
            HttpResponse<String> r = send(API + "/user", token);
            if (r.statusCode() == 401) {
                return new Result(now, null, null, true, null, null, reach);
            }
            if (r.statusCode() != 200) {
                return new Result(now, null, null, false,
                        "GitHub answered " + r.statusCode(), null, reach);
            }
            JsonObject body = new Gson().fromJson(r.body(), JsonObject.class);
            String account = body != null && body.has("login")
                    ? body.get("login").getAsString() : null;
            String expires = r.headers().firstValue(EXPIRY_HEADER)
                    .map(v -> v.trim().substring(0, Math.min(10, v.trim().length())))
                    .orElse("never");
            Optional<String> scopes = r.headers().firstValue(SCOPES_HEADER);
            Scope scope = scopes.isPresent()
                    ? classicScope(scopes.get()) : fineGrainedScope(token);
            return new Result(now, account, expires, false, null, scope, reach);
        } catch (Exception e) {
            return new Result(now, null, null, false, reason(e), null, reach);
        }
    }

    /** A classic token carries scopes and reaches every repository its account can. */
    private static Scope classicScope(String header) {
        String scopes = header.trim();
        return new Scope("classic",
                "Every repository this account can access"
                        + (scopes.isEmpty() ? "" : " (" + scopes + ")"),
                List.of());
    }

    /**
     * A fine-grained token names the repositories it was granted, if GitHub will say so:
     * {@code /installation/repositories} answers for the token itself and states whether the grant
     * is every repository or a list.
     *
     * <p>There is deliberately no {@code /user/repos} fallback. That endpoint returns every public
     * repository the account can see plus the granted private ones, so a token scoped to one
     * repository reported fifteen — a number that is wrong is worse than no number. **Reaches**
     * carries the measured truth instead.
     */
    private static Scope fineGrainedScope(String token) {
        try {
            for (String url : new String[] {
                    API + "/installation/repositories?per_page=100",
                    API + "/user/installations/repositories?per_page=100" }) {
                HttpResponse<String> r = send(url, token);
                if (r.statusCode() != 200) {
                    continue;
                }
                JsonObject body = new Gson().fromJson(r.body(), JsonObject.class);
                List<String> repos = names(body == null ? null
                        : body.getAsJsonArray("repositories"));
                int total = body != null && body.has("total_count")
                        ? body.get("total_count").getAsInt() : repos.size();
                boolean all = body != null && body.has("repository_selection")
                        && "all".equals(body.get("repository_selection").getAsString());
                return new Scope("fine-grained", all
                        ? "All repositories of " + owner(repos) : count(total), repos);
            }
            return new Scope("fine-grained", "GitHub does not report its grants", List.of());
        } catch (Exception e) {
            return new Scope("fine-grained", "GitHub does not report its grants — " + reason(e),
                    List.of());
        }
    }

    private static String count(int n) {
        return n + (n == 1 ? " repository" : " repositories");
    }

    /** The owner every granted repository shares, for "All repositories of <org>". */
    private static String owner(List<String> repos) {
        String first = repos.isEmpty() ? "" : repos.get(0);
        int slash = first.indexOf('/');
        String owner = slash > 0 ? first.substring(0, slash) : "";
        boolean same = !owner.isEmpty() && repos.stream().allMatch(r -> r.startsWith(owner + "/"));
        return same ? owner : "its owner";
    }

    private static List<String> names(JsonArray repos) {
        List<String> out = new ArrayList<>();
        if (repos == null) {
            return out;
        }
        for (int i = 0; i < repos.size(); i++) {
            JsonObject o = repos.get(i).getAsJsonObject();
            if (o.has("full_name")) {
                out.add(o.get("full_name").getAsString());
            }
        }
        return out;
    }

    private static HttpResponse<String> send(String url, String token) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(NET_TIMEOUT)).build()
                .send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(NET_TIMEOUT + 5))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "ignition-git-module")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A message worth showing in a table cell: the cause's, since the wrapper's is often empty. */
    private static String reason(Throwable e) {
        Throwable t = e;
        while (t.getMessage() == null && t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    /** Whether a URL or host label points at github.com. */
    private static boolean isGitHub(String value) {
        if (value == null) {
            return false;
        }
        String h = value.trim().toLowerCase();
        int scheme = h.indexOf("://");
        if (scheme >= 0) {
            h = h.substring(scheme + 3);
        }
        int at = h.indexOf('@');
        if (at >= 0) {
            h = h.substring(at + 1);
        }
        int cut = h.length();
        for (char c : new char[] {'/', ':'}) {
            int i = h.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        h = h.substring(0, cut);
        return h.equals("github.com") || h.endsWith(".github.com");
    }

    /** Remotes that authenticate with this credential. A broken link is reported, never skipped. */
    private static List<Target> targets(boolean ssh, long id) {
        Map<String, Target> out = new LinkedHashMap<>();
        List<GitRemoteCredentialsRecord> refs = ssh
                ? GitRemoteCredentialsRecord.listBySshKeyId(id)
                : GitRemoteCredentialsRecord.listByHttpsCredentialId(id);
        for (GitRemoteCredentialsRecord ref : refs) {
            GitProjectsConfigRecord project = GitProjectsConfigRecord.findById(ref.getProjectId());
            if (project == null) {
                continue;
            }
            String name = project.getProjectName();
            if (out.containsKey(name)) {
                continue;
            }
            try {
                Path folder = GitManager.getProjectFolderPath(name);
                String url = GitManager.getRemoteUrl(folder, ref.getRemoteName());
                out.put(name, url == null
                        ? new Target(name, null, null,
                                "no remote named \"" + ref.getRemoteName() + "\" any more")
                        : new Target(name, url, folder.toString(), null));
            } catch (Exception e) {
                out.put(name, new Target(name, null, null, reason(e)));
            }
        }
        GitConfigRemoteRecord config = GitConfigRemoteRecord.get();
        if (config != null && (ssh ? config.getSshKeyId() : config.getHttpsCredentialId()) == id) {
            out.put("gateway config", new Target("gateway config", config.getUri(),
                    DataDirGitManager.dataDir().toString(), null));
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Read: fetch's ref advertisement. Push: receive-pack's — a host refuses a token without write
     * access there, before anything is sent, so nothing is pushed.
     */
    private static Reach probe(Target target, String sshKey, String user, String password) {
        boolean read = false;
        try (Git git = Git.open(Path.of(target.repoDir()).toFile())) {
            Repository repo = git.getRepository();
            try (Transport t = open(repo, target.url(), sshKey, user, password)) {
                t.openFetch().close();
                read = true;
            }
            try (Transport t = open(repo, target.url(), sshKey, user, password);
                 PushConnection c = t.openPush()) {
                return new Reach(target.name(), true, true, null);
            }
        } catch (Exception e) {
            return new Reach(target.name(), read, false, read ? null : reason(e));
        }
    }

    private static Transport open(Repository repo, String url, String sshKey, String user,
                                  String password) throws Exception {
        Transport t = Transport.open(repo, new URIish(url));
        t.setTimeout(NET_TIMEOUT);
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
