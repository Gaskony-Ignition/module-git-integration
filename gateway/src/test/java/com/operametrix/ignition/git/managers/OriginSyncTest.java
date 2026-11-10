package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 4.0.0 defect: origin came from {@code .git/config} while the credential came from the
 * mode-resolved record. After {@link OriginSync#apply} the only URL JGit can contact is the record's.
 */
class OriginSyncTest {

    @TempDir
    Path dir;

    private static RemoteConfig origin(Git git) throws Exception {
        return new RemoteConfig(git.getRepository().getConfig(), "origin");
    }

    @Test
    void originFollowsTheEffectiveRecordNotWhatWasSavedEarlier() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            // Saved on Dev: origin points at the Dev repository.
            git.remoteAdd().setName("origin").setUri(new URIish("https://github.com/acme/dev-config.git")).call();
            // A push URL would be contacted instead of the URL on push; it must not survive.
            StoredConfig c = git.getRepository().getConfig();
            c.setString("remote", "origin", "pushurl", "https://elsewhere.example/x.git");
            c.save();

            // The gateway restarts in Prod, whose record names another host.
            assertThat(OriginSync.apply(git, "https://git.prod.example/acme/prod-config.git", "main")).isTrue();

            RemoteConfig o = origin(git);
            assertThat(o.getURIs()).extracting(URIish::toString)
                    .containsExactly("https://git.prod.example/acme/prod-config.git");
            assertThat(o.getPushURIs()).isEmpty();
            for (URIish u : o.getURIs()) {
                assertThat(PolicyGate.credentialMismatch("git.prod.example", u.toString())).isNull();
            }
            // Nothing to do the second time.
            assertThat(OriginSync.apply(git, "https://git.prod.example/acme/prod-config.git", "main")).isFalse();
        }
    }

    @Test
    void originIsCreatedWithAFetchSpecWhenMissing() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            OriginSync.apply(git, "git@github.com:acme/cfg.git", "main");
            assertThat(origin(git).getFetchRefSpecs()).extracting(Object::toString)
                    .containsExactly("+refs/heads/*:refs/remotes/origin/*");
        }
    }

    @Test
    void aBranchChangeMovesTheUpstreamsThatPushedToTheOldOne() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            StoredConfig c = git.getRepository().getConfig();
            c.setString("branch", "main", "remote", "origin");
            c.setString("branch", "main", "merge", "refs/heads/dev");
            c.setString("branch", "other", "remote", "origin");
            c.setString("branch", "other", "merge", "refs/heads/feature");
            c.save();
            OriginSync.rememberIfUnset(git, "dev");
            OriginSync.apply(git, "https://github.com/acme/cfg.git", "prod");
            c = git.getRepository().getConfig();
            assertThat(c.getString("branch", "main", "merge")).isEqualTo("refs/heads/prod");
            assertThat(c.getString("branch", "other", "merge")).isEqualTo("refs/heads/feature");
            // A remembered branch is not overwritten by the seed.
            OriginSync.rememberIfUnset(git, "dev");
            assertThat(c.getString("gitmodule", "origin", "branch")).isEqualTo("prod");
        }
    }
}
