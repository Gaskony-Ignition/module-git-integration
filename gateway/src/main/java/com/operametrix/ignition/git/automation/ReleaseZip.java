package com.operametrix.ignition.git.automation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Unpacks a release zip, within limits. No servlet types here, so it is unit tested. */
final class ReleaseZip {

    private ReleaseZip() {
    }

    /** A zip the gateway will not unpack; {@code status} is the HTTP status to answer. */
    static final class Refused extends IOException {
        final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * Extracts every entry under {@code dest}, refusing any whose path would land outside it.
     * Backslash separators are normalised: Windows PowerShell 5.1's Compress-Archive writes them.
     * Stops at {@code maxBytes} unpacked or {@code maxEntries} entries, counted as written rather
     * than trusted from the entry headers.
     */
    static int unzip(Path zip, Path dest, long maxBytes, int maxEntries) throws IOException {
        Files.createDirectories(dest);
        Path root = dest.toAbsolutePath().normalize();
        int files = 0;
        int entries = 0;
        long written = 0;
        byte[] buf = new byte[65536];
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            boolean any = false;
            while ((entry = zin.getNextEntry()) != null) {
                any = true;
                if (++entries > maxEntries) {
                    throw new Refused(413,
                            "the zip has more than " + maxEntries + " entries");
                }
                String name = entry.getName().replace('\\', '/');
                Path p = root.resolve(name).normalize();
                if (!p.startsWith(root) || p.equals(root) && !entry.isDirectory()) {
                    throw new Refused(400,
                            "the zip has an entry outside the project folder: " + name);
                }
                if (entry.isDirectory() || name.endsWith("/")) {
                    Files.createDirectories(p);
                } else {
                    Files.createDirectories(p.getParent());
                    try (java.io.OutputStream out = Files.newOutputStream(p)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            written += n;
                            if (written > maxBytes) {
                                throw new Refused(413,
                                        "the zip unpacks to more than " + (maxBytes / (1024 * 1024)) + " MB");
                            }
                            out.write(buf, 0, n);
                        }
                    }
                    files++;
                }
            }
            if (!any) {
                throw new Refused(400, "the body is not a zip");
            }
        }
        return files;
    }

}
