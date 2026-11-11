package com.operametrix.ignition.git.managers;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Every project name and file path a Designer or page request hands in is checked here before it
 * touches the disk, so none of them can reach outside {@code projects/<name>} or the repository.
 * No gateway context, so it is unit tested.
 */
public final class SafePaths {

    /** The characters Ignition allows in a project name (a title may hold anything). */
    private static final Pattern PROJECT_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    /** A diff shows at most this much of one file. */
    public static final long MAX_READ_BYTES = 5L * 1024 * 1024;

    private SafePaths() {
    }

    /** {@code name} when it is a project name, else an {@link IllegalArgumentException}. */
    public static String projectName(String name) {
        if (name == null || !PROJECT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a project name: letters, digits, _ and - only.");
        }
        return name;
    }

    /** {@code projects/<name>} under the data directory, checked to stay there. */
    public static Path projectFolder(Path dataDir, String name) {
        Path projects = dataDir.resolve("projects").toAbsolutePath().normalize();
        Path p = projects.resolve(projectName(name)).normalize();
        if (!projects.equals(p.getParent())) {
            throw new IllegalArgumentException("Not a project name: letters, digits, _ and - only.");
        }
        return p;
    }

    /**
     * A repository-relative path with {@code /} separators. Refuses an absolute path, a drive
     * letter, a {@code ..} segment and any {@code .git} segment (the repository's own files).
     */
    public static String repoPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A file path is required.");
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Not a path in the repository: " + path);
        }
        for (String segment : p.split("/")) {
            if (segment.equals("..") || segment.equalsIgnoreCase(".git")) {
                throw new IllegalArgumentException("Not a path in the repository: " + path);
            }
        }
        return p;
    }

    /**
     * {@link #repoPath} resolved under {@code root}, and still under it once symbolic links are
     * followed: a link in the working tree must not lead the read somewhere else.
     */
    public static Path resolve(Path root, String path) throws IOException {
        String rel = repoPath(path);
        Path base = root.toAbsolutePath().normalize();
        Path p = base.resolve(rel).normalize();
        if (!p.startsWith(base)) {
            throw new IllegalArgumentException("Not a path in the repository: " + path);
        }
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.exists(p)) {
            if (!p.toRealPath().startsWith(base.toRealPath())) {
                throw new IllegalArgumentException("Not a path in the repository: " + path);
            }
        } else if (Files.isSymbolicLink(p)) {
            // A dangling link: nothing to read, and nothing to say about where it points.
            throw new IllegalArgumentException("Not a regular file: " + path);
        }
        return p;
    }

    /** A regular file's text, "" when it does not exist; refuses anything else or over {@code max}. */
    public static String readCapped(Path file, long max) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a regular file: " + file.getFileName());
        }
        if (Files.size(file) > max) {
            throw new IllegalArgumentException(file.getFileName() + " is over " + (max / (1024 * 1024))
                    + " MB, too large to show.");
        }
        return new String(Files.readAllBytes(file), Charset.defaultCharset());
    }

    /**
     * {@code message} with each folder in {@code roots} taken out, so an error shown to a Designer
     * or the page names {@code projects/x/...} rather than where the gateway is installed. The
     * gateway log keeps the original.
     */
    public static String redact(String message, Path... roots) {
        if (message == null) {
            return null;
        }
        String m = message;
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            for (String r : forms(root)) {
                m = m.replace(r + "/", "").replace(r + "\\", "").replace(r, ".");
            }
        }
        return m;
    }

    private static java.util.Set<String> forms(Path root) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        Path abs = root.toAbsolutePath().normalize();
        out.add(abs.toString());
        try {
            out.add(abs.toRealPath().toString());
        } catch (IOException ignored) {
            // Not on disk (a unit test's made-up root): the plain form is enough.
        }
        out.removeIf(s -> s.length() < 2);
        return out;
    }
}
