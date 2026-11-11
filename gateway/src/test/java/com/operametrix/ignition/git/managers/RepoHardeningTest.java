package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Repositories from earlier versions get TLS checks back, no symlinks and no URL passwords. */
class RepoHardeningTest {

    @TempDir
    Path dir;

    @Test
    void anOldRepositoryIsHardenedOnce() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            StoredConfig c = git.getRepository().getConfig();
            c.setBoolean("http", null, "sslVerify", false);
            c.setString("remote", "origin", "url", "https://me:hunter2@github.com/o/r.git");
            c.setString("remote", "backup", "url", "git@github.com:o/r.git");
            c.save();

            assertThat(RepoHardening.harden(git.getRepository())).isTrue();
            assertThat(RepoHardening.harden(git.getRepository())).isFalse();
            assertThat(RepoHardening.stripRemotePasswords(git.getRepository())).containsExactly("origin");
            assertThat(RepoHardening.stripRemotePasswords(git.getRepository())).isEmpty();
        }
        try (Git git = Git.open(dir.toFile())) {
            StoredConfig c = git.getRepository().getConfig();
            assertThat(c.getString("http", null, "sslVerify")).isNull();
            assertThat(c.getBoolean("core", null, "symlinks", true)).isFalse();
            assertThat(c.getString("remote", "origin", "url")).isEqualTo("https://me@github.com/o/r.git");
            assertThat(c.getString("remote", "backup", "url")).isEqualTo("git@github.com:o/r.git");
        }
    }
}
