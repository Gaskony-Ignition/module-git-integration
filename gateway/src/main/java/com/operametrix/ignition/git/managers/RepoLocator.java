package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where a project's or the gateway config's history lives, by repository layout.
 *
 * <ul>
 *   <li><b>Separate</b> (the default, and every version before 4.0.0): each project is its own
 *       repository at {@code projects/<name>}, and the config repository at the data directory
 *       ignores {@code projects/}.</li>
 *   <li><b>Combined</b>: one repository at the data directory holds {@code config/} and
 *       {@code projects/}. A project is the path prefix {@code projects/<name>/} in it.</li>
 * </ul>
 *
 * <p>The layout is a setting of the data-directory repository, kept in its {@code .git/config}
 * like {@link ConfigSync}: a resource would sit under {@code config/}, be versioned, and travel to
 * any gateway following the branch. No data-directory repository means Separate.
 *
 * <p>No gateway context here, so it is unit tested; {@link GitManager#repoFor} adds the data dir.
 */
public final class RepoLocator {

    public enum Layout { SEPARATE, COMBINED }

    private static final String SECTION = "gitmodule";
    private static final String SUBSECTION = "layout";
    private static final String KEY = "mode";

    private RepoLocator() {
    }

    /**
     * A working tree and the part of it that belongs to one project or to config.
     *
     * @param root   the repository's working tree
     * @param prefix repo-relative prefix with a trailing slash, or "" when the whole repository is
     *               the project's (Separate)
     */
    public record Repo(Path root, String prefix) {

        /** Whether the repository is shared, so every path a caller sees is mapped. */
        public boolean scoped() {
            return !prefix.isEmpty();
        }

        /** The prefix as a git pathspec (no trailing slash). */
        public String pathspec() {
            return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        }

        /** Whether a repo-relative path is inside this project. */
        public boolean contains(String repoPath) {
            return prefix.isEmpty() || repoPath.startsWith(prefix) || repoPath.equals(pathspec());
        }

        /** Project-relative to repo-relative; a path outside the project (leading {@code /}) is refused. */
        public String toRepoPath(String projectPath) {
            if (prefix.isEmpty()) {
                return projectPath;
            }
            if (projectPath.startsWith("/")) {
                throw new IllegalArgumentException("'" + projectPath + "' is outside this project.");
            }
            return prefix + projectPath;
        }

        /**
         * As {@link #toRepoPath(String)}, also taking a leading {@code /} path (already repo-relative,
         * as {@link #toProjectPath} hands out for a merge conflict in config, say) when it is one of
         * {@code conflicting}, so the Designer can name and resolve it and nothing else outside.
         */
        public String toRepoPath(String projectPath, java.util.Collection<String> conflicting) {
            if (!prefix.isEmpty() && projectPath.startsWith("/")) {
                String p = projectPath.substring(1);
                if (conflicting.contains(p)) {
                    return p;
                }
                throw new IllegalArgumentException("'" + projectPath + "' is outside this project and not in"
                        + " conflict.");
            }
            return toRepoPath(projectPath);
        }

        /** Repo-relative to project-relative; outside the project gets a leading {@code /}. */
        public String toProjectPath(String repoPath) {
            if (prefix.isEmpty()) {
                return repoPath;
            }
            return repoPath.startsWith(prefix) ? repoPath.substring(prefix.length()) : "/" + repoPath;
        }

        /** Keeps the paths inside the project, project-relative. */
        public Set<String> inProject(Set<String> repoPaths) {
            if (prefix.isEmpty()) {
                return repoPaths;
            }
            Set<String> out = new LinkedHashSet<>();
            for (String p : repoPaths) {
                if (p.startsWith(prefix)) {
                    out.add(p.substring(prefix.length()));
                }
            }
            return out;
        }
    }

    public static Repo forProject(Path dataDir, String projectName, Layout layout) {
        SafePaths.projectName(projectName);
        return layout == Layout.COMBINED
                ? new Repo(dataDir, "projects/" + projectName + "/")
                : new Repo(dataDir.resolve("projects").resolve(projectName), "");
    }

    /** Config is {@code config/} of the data-directory repository in both layouts. */
    public static Repo forConfig(Path dataDir) {
        return new Repo(dataDir, "config/");
    }

    private static volatile Path cachedFile;
    private static volatile long cachedStamp = -1;
    private static volatile Layout cachedLayout = Layout.SEPARATE;

    /** The data directory's layout; Separate when there is no repository or no setting. */
    public static Layout readLayout(Path dataDir) {
        Path file = dataDir.resolve(".git").resolve("config");
        try {
            if (!Files.isRegularFile(file)) {
                return Layout.SEPARATE;
            }
            // Read on every git operation the Designer makes, so parse only when the file changed.
            long stamp = Files.getLastModifiedTime(file).toMillis() ^ (Files.size(file) << 20);
            if (file.equals(cachedFile) && stamp == cachedStamp) {
                return cachedLayout;
            }
            FileBasedConfig c = new FileBasedConfig(file.toFile(), FS.DETECTED);
            c.load();
            Layout l = "combined".equals(c.getString(SECTION, SUBSECTION, KEY))
                    ? Layout.COMBINED : Layout.SEPARATE;
            cachedFile = file;
            cachedStamp = stamp;
            cachedLayout = l;
            return l;
        } catch (IOException | ConfigInvalidException e) {
            return Layout.SEPARATE;
        }
    }

    public static void writeLayout(Path dataDir, Layout layout) throws IOException {
        FileBasedConfig c = new FileBasedConfig(dataDir.resolve(".git").resolve("config").toFile(),
                FS.DETECTED);
        try {
            c.load();
        } catch (ConfigInvalidException e) {
            throw new IOException(e);
        }
        if (layout == Layout.COMBINED) {
            c.setString(SECTION, SUBSECTION, KEY, "combined");
        } else {
            c.unsetSection(SECTION, SUBSECTION);
        }
        c.save();
        cachedFile = null;
    }
}
