package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Switching layouts both ways keeps every file and leaves every repository clean. */
class LayoutSwitchTest {

    @TempDir
    Path data;

    static final PersonIdent GW = new PersonIdent("gateway", "");

    static final LayoutSwitch.DirtyCheck DIRTY = (repo, status) -> {
        TreeSet<String> all = new TreeSet<>(status.getUncommittedChanges());
        all.addAll(status.getUntracked());
        return all;
    };

    static void write(Path root, String rel, String text) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @BeforeEach
    void separateGateway() throws Exception {
        // As a gateway writes it: the deny block last.
        write(data, ".gitignore", IgnoreRules.withDenyBlock(String.join("\n", "**/db/*", "**/var", "**/*-wal", "/*",
                "!/config/", "!/.gitignore")));
        write(data, "config/resources/core/a.json", "{\"a\":1}");
        write(data, "logs/wrapper.log", "noise");
        try (Git g = Git.init().setDirectory(data.toFile()).call()) {
            g.add().addFilepattern(".").call();
            g.commit().setMessage("config").setAuthor(GW).call();
        }
        // A versioned project with history and a remote, and one nobody versioned.
        write(data, "projects/A/project.json", "{\"title\":\"A\"}");
        write(data, "projects/A/ignition/script-python/db/code.py", "x = 1");
        write(data, "projects/A/ignition/global-props/data.bin", "props");
        try (Git g = Git.init().setDirectory(data.resolve("projects/A").toFile()).call()) {
            g.add().addFilepattern(".").call();
            g.commit().setMessage("A one").setAuthor(GW).call();
            write(data, "projects/A/project.json", "{\"title\":\"A two\"}");
            g.add().addFilepattern(".").call();
            g.commit().setMessage("A two").setAuthor(GW).call();
            g.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish("https://example.com/a.git")).call();
        }
        write(data, "projects/B/project.json", "{\"title\":\"B\"}");
    }

    /** Every file outside .git folders and var/, with its content. */
    private Map<String, String> files() throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(data)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = data.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".git/") || rel.contains("/.git/") || rel.startsWith("var/")) {
                    continue;
                }
                out.put(rel, Files.readString(p));
            }
        }
        return out;
    }

    private static boolean clean(Path workTree) throws Exception {
        try (Git g = Git.open(workTree.toFile())) {
            Status s = g.status().call();
            return s.isClean();
        }
    }

    @Test
    void roundTripKeepsEveryFileAndLeavesEveryRepositoryClean() throws Exception {
        Map<String, String> before = files();
        before.remove(".gitignore");
        String ignoreBefore = Files.readString(data.resolve(".gitignore"));

        LayoutSwitch.Result combined = LayoutSwitch.toCombined(data, GW, "20261110-120000", DIRTY);
        assertThat(RepoLocator.readLayout(data)).isEqualTo(RepoLocator.Layout.COMBINED);
        assertThat(combined.archived()).containsExactly("var/git-archive/A-20261110-120000.git");
        assertThat(Files.exists(data.resolve("projects/A/.git"))).isFalse();
        assertThat(clean(data)).isTrue();
        try (Git g = Git.open(data.toFile())) {
            Status s = g.status().call();
            assertThat(s.getIgnoredNotInIndex()).contains("projects/A/ignition/global-props/data.bin");
            assertThat(ProjectScope.changedFiles(g.getRepository(), g.getRepository().resolve("HEAD")))
                    .contains("projects/A/project.json", "projects/A/ignition/script-python/db/code.py",
                            "projects/B/project.json")
                    .doesNotContain("projects/A/ignition/global-props/data.bin");
        }
        Map<String, String> during = files();
        during.remove(".gitignore");
        assertThat(during).isEqualTo(before);

        LayoutSwitch.toSeparate(data, GW, "20261110-120100", false, DIRTY);
        assertThat(RepoLocator.readLayout(data)).isEqualTo(RepoLocator.Layout.SEPARATE);
        assertThat(Files.readString(data.resolve(".gitignore"))).isEqualTo(ignoreBefore);
        assertThat(clean(data)).isTrue();
        assertThat(clean(data.resolve("projects/A"))).isTrue();
        assertThat(clean(data.resolve("projects/B"))).isTrue();
        Map<String, String> after = files();
        after.remove(".gitignore");
        assertThat(after).isEqualTo(before);
        // The old history stays in the archive when it was not asked for.
        assertThat(LayoutSwitch.latestArchive(data, "A")).isNotNull();
    }

    @Test
    void restoringHistoryPutsTheArchivedRepositoryBackWithOneCommitForTheCombinedChanges() throws Exception {
        LayoutSwitch.toCombined(data, GW, "20261110-120000", DIRTY);
        write(data, "projects/A/project.json", "{\"title\":\"A three\"}");
        try (Git g = Git.open(data.toFile())) {
            ProjectScope.commit(g, RepoLocator.forProject(data, "A", RepoLocator.Layout.COMBINED),
                    java.util.List.of("project.json"), "A three", false, "eng", "");
        }

        LayoutSwitch.Result r = LayoutSwitch.toSeparate(data, GW, "20261110-120100", true, DIRTY);

        assertThat(r.restored()).containsExactly("projects/A");
        try (Git a = Git.open(data.resolve("projects/A").toFile())) {
            java.util.List<String> log = new java.util.ArrayList<>();
            a.log().call().forEach(c -> log.add(c.getShortMessage()));
            assertThat(log).containsExactly("Changes made while in the gateway repository", "A two", "A one");
            assertThat(a.getRepository().getRemoteNames()).containsExactly("origin");
            assertThat(a.status().call().isClean()).isTrue();
        }
        assertThat(LayoutSwitch.latestArchive(data, "A")).isNull();
        assertThat(clean(data)).isTrue();
    }

    @Test
    void repeatedSwitchingStaysClean() throws Exception {
        Map<String, String> before = files();
        before.remove(".gitignore");
        for (int i = 0; i < 3; i++) {
            LayoutSwitch.toCombined(data, GW, "20261110-12000" + i, DIRTY);
            assertThat(clean(data)).isTrue();
            LayoutSwitch.toSeparate(data, GW, "20261110-12010" + i, i % 2 == 0, DIRTY);
            assertThat(clean(data)).isTrue();
            assertThat(clean(data.resolve("projects/A"))).isTrue();
            assertThat(clean(data.resolve("projects/B"))).isTrue();
        }
        Map<String, String> after = files();
        after.remove(".gitignore");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void refusesWhileAProjectHasUncommittedChangesAndChangesNothing() throws Exception {
        write(data, "projects/A/project.json", "{\"title\":\"dirty\"}");
        assertThatThrownBy(() -> LayoutSwitch.toCombined(data, GW, "20261110-120000", DIRTY))
                .hasMessageContaining("Project A");
        assertThat(Files.isDirectory(data.resolve("projects/A/.git"))).isTrue();
        assertThat(RepoLocator.readLayout(data)).isEqualTo(RepoLocator.Layout.SEPARATE);
        assertThat(Files.readString(data.resolve(".gitignore"))).doesNotContain(LayoutSwitch.BLOCK_START);
    }

    @Test
    void refusesToSeparateWhileTheGatewayRepositoryIsDirty() throws Exception {
        LayoutSwitch.toCombined(data, GW, "20261110-120000", DIRTY);
        write(data, "projects/B/project.json", "{\"title\":\"dirty\"}");
        assertThatThrownBy(() -> LayoutSwitch.toSeparate(data, GW, "20261110-120100", false, DIRTY))
                .hasMessageContaining("uncommitted");
        assertThat(RepoLocator.readLayout(data)).isEqualTo(RepoLocator.Layout.COMBINED);
        assertThat(Files.exists(data.resolve("projects/A/.git"))).isFalse();
    }
}
