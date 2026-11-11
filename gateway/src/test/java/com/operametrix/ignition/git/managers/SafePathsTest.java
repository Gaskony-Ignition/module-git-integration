package com.operametrix.ignition.git.managers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Project names and client-supplied paths never leave the project or repository. */
class SafePathsTest {

    @TempDir
    Path data;

    @Test
    void projectNamesAreIgnitionNamesOnly() {
        assertThat(SafePaths.projectName("Demo_Mining-2")).isEqualTo("Demo_Mining-2");
        for (String bad : new String[]{null, "", "..", ".", "../config", "a/b", "a\\b", "a b", "/etc", "C:x"}) {
            assertThatThrownBy(() -> SafePaths.projectName(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(SafePaths.projectFolder(data, "A")).isEqualTo(data.resolve("projects").resolve("A").toAbsolutePath());
        assertThatThrownBy(() -> SafePaths.projectFolder(data, "..")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repoPathsStayRelativeAndOutOfGit() {
        assertThat(SafePaths.repoPath("ignition/x/view.json")).isEqualTo("ignition/x/view.json");
        assertThat(SafePaths.repoPath("a\\b.json")).isEqualTo("a/b.json");
        for (String bad : new String[]{"/etc/passwd", "../../etc/passwd", "a/../../b", ".git/config",
                "x/.GIT/HEAD", "C:/Windows/win.ini", "", " "}) {
            assertThatThrownBy(() -> SafePaths.repoPath(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aSymlinkLeadingOutOfTheRepositoryIsRefused() throws Exception {
        Path repo = Files.createDirectories(data.resolve("repo"));
        Path outside = Files.writeString(data.resolve("secret.txt"), "secret");
        Files.writeString(repo.resolve("ok.txt"), "ok");
        Files.createSymbolicLink(repo.resolve("link.txt"), outside);
        Files.createSymbolicLink(repo.resolve("dir"), data);
        assertThat(SafePaths.readCapped(SafePaths.resolve(repo, "ok.txt"), 100)).isEqualTo("ok");
        assertThatThrownBy(() -> SafePaths.resolve(repo, "link.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePaths.resolve(repo, "dir/secret.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SafePaths.readCapped(SafePaths.resolve(repo, "missing.txt"), 100)).isEmpty();
    }

    @Test
    void readsAreCappedAndRegularFilesOnly() throws Exception {
        Path big = Files.write(data.resolve("big.bin"), new byte[2048]);
        assertThatThrownBy(() -> SafePaths.readCapped(big, 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePaths.readCapped(data, 1024)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorsDoNotNameTheInstallFolder() {
        Path root = Path.of("/usr/local/bin/ignition/data");
        assertThat(SafePaths.redact("Cannot open /usr/local/bin/ignition/data/projects/A/.git/HEAD", root))
                .isEqualTo("Cannot open projects/A/.git/HEAD");
        assertThat(SafePaths.redact("in /usr/local/bin/ignition/data", root)).isEqualTo("in .");
    }
}
