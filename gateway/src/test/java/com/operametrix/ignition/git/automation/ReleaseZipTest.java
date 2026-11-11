package com.operametrix.ignition.git.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A release zip is unpacked within its limits, and never outside its folder. */
class ReleaseZipTest {

    @TempDir
    Path tmp;

    private Path zip(String... namesAndSizes) throws Exception {
        Path z = tmp.resolve("r" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(z))) {
            for (int i = 0; i < namesAndSizes.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndSizes[i]));
                out.write(new byte[Integer.parseInt(namesAndSizes[i + 1])]);
                out.closeEntry();
            }
        }
        return z;
    }

    @Test
    void unpacksWithinTheLimits() throws Exception {
        Path dest = tmp.resolve("ok");
        assertThat(ReleaseZip.unzip(zip("project.json", "10", "a/b.json", "20"), dest, 100, 10)).isEqualTo(2);
        assertThat(Files.size(dest.resolve("a/b.json"))).isEqualTo(20);
    }

    @Test
    void stopsPastTheByteCap() throws Exception {
        assertThatThrownBy(() -> ReleaseZip.unzip(zip("a", "60", "b", "60"), tmp.resolve("big"), 100, 10))
                .hasMessageContaining("unpacks to more than");
    }

    @Test
    void stopsPastTheEntryCap() throws Exception {
        assertThatThrownBy(() -> ReleaseZip.unzip(zip("a", "1", "b", "1", "c", "1"), tmp.resolve("many"), 100, 2))
                .hasMessageContaining("more than 2 entries");
    }

    @Test
    void refusesAnEntryOutsideTheFolder() throws Exception {
        assertThatThrownBy(() -> ReleaseZip.unzip(zip("../evil", "1"), tmp.resolve("x"), 100, 10))
                .hasMessageContaining("outside the project folder");
        assertThat(Files.exists(tmp.resolve("evil"))).isFalse();
    }

}
