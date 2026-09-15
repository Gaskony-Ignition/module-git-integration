package com.operametrix.ignition.git.automation;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.operametrix.ignition.git.GatewayHook;
import com.operametrix.ignition.git.managers.GitManager;
import com.operametrix.ignition.git.managers.GitProjectManager;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The route a runner calls to install a release: the body is a project export zip, and the
 * project on this gateway is replaced by it.
 *
 * <p>A release replaces the whole project, not individual files — a file dropped from the
 * release must disappear from the gateway, which a copy-over never does. Two things are carried
 * across the replacement:
 * <ul>
 *   <li>{@code .git} — the module keeps the project's repository in the project folder, so
 *   deleting the folder would destroy it and every local commit. The working tree IS replaced, so
 *   afterwards the Changes list shows exactly how the gateway differs from its last commit.</li>
 *   <li>{@code ignition/global-props/data.bin} — the project properties hold per-gateway settings
 *   such as the default database connection, which a release ships neutral.</li>
 * </ul>
 *
 * <p>A release is authoritative: uncommitted edits on the gateway are overwritten, as with any
 * deployment. The upload is streamed to {@code var/git-release}, outside {@code projects/}, so a
 * scan never sees a half-written project, then moved into place on the same filesystem.
 */
public final class ReleaseReceiver {

    private static final Logger logger = LoggerFactory.getLogger(ReleaseReceiver.class);

    /** A Perspective project export is a few MB; this only stops a runaway upload filling the disk. */
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    /** Project names are directory names; nothing that could climb out of {@code projects/}. */
    private static final Pattern PROJECT_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private static final String GLOBAL_PROPS = "ignition/global-props/data.bin";

    private ReleaseReceiver() {
    }

    private static final class Refused extends Exception {
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static Object handle(RequestContext req, HttpServletResponse resp) {
        String rejected = RunnerAuth.reject(req, resp);
        if (rejected != null) {
            return rejected;
        }

        // Read from the raw query string, never getParameter(): without a zip Content-Type (curl's
        // default is form-urlencoded) getParameter makes Jetty parse the 256 MB body as a form and
        // the request dies with a 500 before a line of this runs.
        String project = queryParam(req, "project");
        String version = queryParam(req, "version");
        version = version == null ? "" : version.trim();
        if (project == null || !PROJECT_NAME.matcher(project).matches()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return error("name the project with ?project=, letters, digits, _ and - only");
        }
        if (!SyncScheduler.acquire(project)) {
            resp.setStatus(HttpServletResponse.SC_CONFLICT);
            return error("a sync or release of '" + project + "' is already running");
        }

        Path work = null;
        try {
            Path base = GitManager.getDataFolderPath().resolve("var").resolve("git-release");
            Files.createDirectories(base);
            work = Files.createTempDirectory(base, project + "-");

            Path zip = work.resolve("release.zip");
            long size = copyCapped(req.getRequest().getInputStream(), zip);
            if (size == 0) {
                throw new Refused(HttpServletResponse.SC_BAD_REQUEST,
                        "no release zip in the request body");
            }

            Path staged = work.resolve("project");
            int files = unzip(zip, staged);
            if (!Files.isRegularFile(staged.resolve("project.json"))) {
                throw new Refused(HttpServletResponse.SC_BAD_REQUEST,
                        "the zip has no project.json at its root, so it is not a project export");
            }
            // A release carries no repository of its own; one in the zip would replace ours.
            deleteRecursively(staged.resolve(".git"));

            boolean[] kept = replace(project, staged, work);

            GitProjectManager.importProject(project);
            try {
                GatewayHook.getContext().getProjectManager().requestScan();
            } catch (Exception e) {
                logger.warn("Project scan request after installing a release failed.", e);
            }

            String label = version.isEmpty() ? project : project + " " + version;
            logger.info("Runner installed release {} ({} files).", label, files);
            GitEvents.fire(GitEvent.of(GitEvent.RELEASE)
                    .project(project)
                    .user("runner")
                    .message("Installed " + label + " (" + files + " files)")
                    .success());

            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("project", project);
            o.addProperty("version", version);
            o.addProperty("files", files);
            o.addProperty("keptRepository", kept[0]);
            o.addProperty("keptProjectProperties", kept[1]);
            return o.toString();
        } catch (Refused r) {
            resp.setStatus(r.status);
            GitEvents.fire(GitEvent.of(GitEvent.RELEASE).project(project).user("runner")
                    .failure(r.getMessage()));
            return error(r.getMessage());
        } catch (Exception e) {
            logger.warn("Installing a release of '{}' failed.", project, e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            GitEvents.fire(GitEvent.of(GitEvent.RELEASE).project(project).user("runner")
                    .failure(GitEvents.reason(e)));
            return error(GitEvents.reason(e));
        } finally {
            SyncScheduler.release(project);
            if (work != null) {
                try {
                    deleteRecursively(work);
                } catch (IOException e) {
                    logger.warn("Could not remove release staging folder {}.", work, e);
                }
            }
        }
    }

    /**
     * Swaps the staged project in, keeping the repository and the project properties.
     * Returns whether each was kept. The repository is put back even if the swap fails part way.
     */
    private static boolean[] replace(String project, Path staged, Path work) throws IOException {
        Path target = GitManager.getProjectFolderPath(project);
        Path keptGit = work.resolve("kept-git");
        Path keptProps = work.resolve("kept-global-props.bin");

        boolean hadGit = Files.isDirectory(target.resolve(".git"));
        boolean hadProps = Files.isRegularFile(target.resolve(GLOBAL_PROPS));
        if (hadProps) {
            Files.copy(target.resolve(GLOBAL_PROPS), keptProps);
        }
        if (hadGit) {
            Files.move(target.resolve(".git"), keptGit);
        }
        try {
            deleteRecursively(target);
            Files.move(staged, target);
            if (hadProps) {
                Path props = target.resolve(GLOBAL_PROPS);
                Files.createDirectories(props.getParent());
                Files.copy(keptProps, props, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (hadGit) {
                Files.createDirectories(target);
                Files.move(keptGit, target.resolve(".git"));
            }
        }
        return new boolean[] {hadGit, hadProps};
    }

    private static long copyCapped(InputStream in, Path out) throws IOException, Refused {
        long total = 0;
        try (InputStream src = in; OutputStream dst = Files.newOutputStream(out)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = src.read(buf)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new Refused(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                            "release is larger than " + (MAX_BYTES / (1024 * 1024)) + " MB");
                }
                dst.write(buf, 0, read);
            }
        }
        return total;
    }

    /**
     * Extracts every entry under {@code dest}, refusing any whose path would land outside it.
     * Backslash separators are normalised: Windows PowerShell 5.1's Compress-Archive writes them.
     */
    private static int unzip(Path zip, Path dest) throws IOException, Refused {
        Files.createDirectories(dest);
        Path root = dest.toAbsolutePath().normalize();
        int files = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            boolean any = false;
            while ((entry = zin.getNextEntry()) != null) {
                any = true;
                String name = entry.getName().replace('\\', '/');
                Path p = root.resolve(name).normalize();
                if (!p.startsWith(root) || p.equals(root) && !entry.isDirectory()) {
                    throw new Refused(HttpServletResponse.SC_BAD_REQUEST,
                            "the zip has an entry outside the project folder: " + name);
                }
                if (entry.isDirectory() || name.endsWith("/")) {
                    Files.createDirectories(p);
                } else {
                    Files.createDirectories(p.getParent());
                    Files.copy(zin, p, StandardCopyOption.REPLACE_EXISTING);
                    files++;
                }
            }
            if (!any) {
                throw new Refused(HttpServletResponse.SC_BAD_REQUEST, "the body is not a zip");
            }
        }
        return files;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String queryParam(RequestContext req, String name) {
        String query = req.getRequest().getQueryString();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o.toString();
    }
}
