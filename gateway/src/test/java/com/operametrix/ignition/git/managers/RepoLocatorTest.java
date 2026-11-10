package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoLocatorTest {

    @TempDir
    Path data;

    @Test
    void separateIsTheProjectsOwnRepositoryWithNoPrefix() {
        RepoLocator.Repo r = RepoLocator.forProject(data, "Demo", RepoLocator.Layout.SEPARATE);
        assertThat(r.root()).isEqualTo(data.resolve("projects").resolve("Demo"));
        assertThat(r.scoped()).isFalse();
        assertThat(r.toRepoPath("ignition/x")).isEqualTo("ignition/x");
        assertThat(r.toProjectPath("ignition/x")).isEqualTo("ignition/x");
        assertThat(r.contains("anything")).isTrue();
    }

    @Test
    void combinedIsAPrefixOfTheDataDirRepository() {
        RepoLocator.Repo r = RepoLocator.forProject(data, "Demo", RepoLocator.Layout.COMBINED);
        assertThat(r.root()).isEqualTo(data);
        assertThat(r.prefix()).isEqualTo("projects/Demo/");
        assertThat(r.pathspec()).isEqualTo("projects/Demo");
        assertThat(r.toRepoPath("ignition/x")).isEqualTo("projects/Demo/ignition/x");
        assertThat(r.toProjectPath("projects/Demo/ignition/x")).isEqualTo("ignition/x");
        // Outside the project: marked, and mapped straight back.
        assertThat(r.toProjectPath("config/a.json")).isEqualTo("/config/a.json");
        assertThat(r.toRepoPath("/config/a.json")).isEqualTo("config/a.json");
        assertThat(r.contains("projects/Demo2/x")).isFalse();
        assertThat(r.inProject(new LinkedHashSet<>(List.of("projects/Demo/a", "projects/Other/b", "config/c"))))
                .containsExactly("a");
    }

    @Test
    void configIsTheConfigFolderInBothLayouts() {
        assertThat(RepoLocator.forConfig(data)).isEqualTo(new RepoLocator.Repo(data, "config/"));
    }

    @Test
    void refusesANameThatLeavesTheProjectsFolder() {
        assertThatThrownBy(() -> RepoLocator.forProject(data, "../config", RepoLocator.Layout.COMBINED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void layoutIsStoredInTheDataDirRepositoryAndDefaultsToSeparate() throws Exception {
        assertThat(RepoLocator.readLayout(data)).isEqualTo(RepoLocator.Layout.SEPARATE);
        Git.init().setDirectory(data.toFile()).call().close();
        assertThat(RepoLocator.readLayout(data)).isEqualTo(RepoLocator.Layout.SEPARATE);
        RepoLocator.writeLayout(data, RepoLocator.Layout.COMBINED);
        assertThat(RepoLocator.readLayout(data)).isEqualTo(RepoLocator.Layout.COMBINED);
        RepoLocator.writeLayout(data, RepoLocator.Layout.SEPARATE);
        assertThat(RepoLocator.readLayout(data)).isEqualTo(RepoLocator.Layout.SEPARATE);
    }
}
