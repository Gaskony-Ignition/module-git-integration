package com.operametrix.ignition.git.managers;

import com.operametrix.ignition.git.records.GitPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which remote URLs the gateway uses, with and without the insecure-remotes opt-in. */
class RemoteUrlsTest {

    @Test
    void httpsAndSshAreAlwaysAllowed() {
        for (String ok : List.of("https://github.com/o/r.git", "ssh://git@github.com/o/r.git",
                "git@github.com:o/r.git", "https://token@github.com/o/r.git", "https://git.corp.example:8443/r")) {
            assertThat(RemoteUrls.problem(ok, false)).as(ok).isNull();
        }
    }

    @Test
    void insecureSchemesAndLocalFoldersNeedTheOptIn() {
        for (String u : List.of("file:///srv/git/r.git", "/srv/git/r.git", "git://example.com/r.git",
                "http://example.com/r.git", "C:\\repos\\r.git")) {
            assertThat(RemoteUrls.problem(u, false)).as(u).isNotNull();
            assertThat(RemoteUrls.problem(u, true)).as(u).isNull();
        }
        assertThat(RemoteUrls.problem("ftp://example.com/r.git", true)).isNotNull();
    }

    @Test
    void loopbackAndLinkLocalTargetsNeedTheOptIn() {
        for (String u : List.of("https://localhost/r.git", "https://127.0.0.1/r.git", "https://127.1.2.3:8443/r",
                "https://169.254.169.254/latest", "https://[::1]/r.git", "https://[fe80::1]/r.git",
                "git@127.0.0.1:o/r.git", "https://0.0.0.0/r")) {
            assertThat(RemoteUrls.problem(u, false)).as(u).isNotNull();
            assertThat(RemoteUrls.problem(u, true)).as(u).isNull();
        }
        assertThat(RemoteUrls.problem("https://10.1.2.3/r.git", false)).isNull();
    }

    @Test
    void aPasswordInTheUrlIsNeverAccepted() {
        assertThat(RemoteUrls.problem("https://me:hunter2@github.com/o/r.git", true)).contains("password");
        assertThat(RemoteUrls.withoutPassword("https://me:hunter2@github.com/o/r.git"))
                .isEqualTo("https://me@github.com/o/r.git");
        assertThat(RemoteUrls.withoutPassword("https://github.com/o/r.git")).isEqualTo("https://github.com/o/r.git");
        assertThat(RemoteUrls.withoutPassword("git@github.com:o/r.git")).isEqualTo("git@github.com:o/r.git");
    }

    @Test
    void thePolicyDecidesWithTheUrlRulesFirst() {
        GitPolicy secure = GitPolicy.DEFAULT;
        GitPolicy open = new GitPolicy(null, null, null, null, null, null, null, true);
        assertThat(PolicyGate.decideUrl(secure, "Prod", "file:///srv/r.git").getMessage())
                .startsWith("Not allowed in deployment mode Prod: A file:// remote");
        assertThat(PolicyGate.decideUrl(open, null, "file:///srv/r.git")).isNull();
        GitPolicy hosts = new GitPolicy(null, null, null, null, null, List.of("github.com"), null, null);
        assertThat(PolicyGate.decideUrl(hosts, null, "https://gitlab.com/r.git")).isNotNull();
        assertThat(PolicyGate.decideUrl(hosts, null, "https://github.com/r.git")).isNull();
    }
}
