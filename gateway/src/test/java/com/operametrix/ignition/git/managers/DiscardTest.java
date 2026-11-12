package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Discard from the Designer's commit panel, one resource at a time. */
class DiscardTest {

    @TempDir
    Path root;

    @Test
    void discardingASavedViewKeepsTheViewAndDropsOnlyItsNewFile() throws Exception {
        Path v = root.resolve("views/V1");
        Files.createDirectories(v);
        Files.writeString(v.resolve("view.json"), "{\"w\":1}");
        Files.writeString(v.resolve("resource.json"), "{}");
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@x").setCommitter("t", "t@x").call();
        }
        Files.writeString(v.resolve("view.json"), "{\"w\":833}");
        Files.writeString(v.resolve("thumbnail.png"), "png");

        assertThat(GitManager.discardChanges(root, List.of("views/V1"))).isTrue();

        assertThat(Files.readString(v.resolve("view.json"))).isEqualTo("{\"w\":1}");
        assertThat(v.resolve("resource.json")).exists();
        assertThat(v.resolve("thumbnail.png")).doesNotExist();
    }

    @Test
    void discardingANewViewRemovesIt() throws Exception {
        Files.writeString(root.resolve("a.json"), "{}");
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@x").setCommitter("t", "t@x").call();
        }
        Path v = root.resolve("views/New");
        Files.createDirectories(v);
        Files.writeString(v.resolve("view.json"), "{}");

        assertThat(GitManager.discardChanges(root, List.of("views/New"))).isTrue();

        assertThat(v).doesNotExist();
    }
}
