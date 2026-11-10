package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Moves a data directory between the Separate and Combined repository layouts (see
 * {@link RepoLocator}). Both directions refuse while anything is uncommitted or mid-merge, change
 * nothing on refusal, and put everything back if a step fails part way.
 *
 * <p>Separate to Combined keeps each project's old {@code .git} under {@code var/git-archive}
 * (ignored by the {@code **}{@code /var} rule), so its history and remotes stay recoverable; no
 * history is grafted into the gateway repository. Combined to Separate starts each project's
 * repository from its current files, or puts its archived {@code .git} back when asked, adding one
 * commit for what changed while it was combined.
 *
 * <p>Pure JGit and files, no gateway context, so it is unit tested.
 */
public final class LayoutSwitch {

    /** What counts as an uncommitted change; the gateway passes {@code IgnitionReformat::realChanges}. */
    public interface DirtyCheck {
        Set<String> realChanges(Repository repo, Status status);
    }

    public record Result(List<String> projects, List<String> archived, List<String> restored,
                         String commit) {}

    public static final String BLOCK_START =
            "# --- Combined layout: projects are versioned in this repository (Git Integration) ---";
    public static final String BLOCK_END = "# --- end of Combined layout ---";

    /**
     * Appended after the root rules ({@code /*}), since the last matching rule wins. The
     * {@code **} negation is there because the template's globs ({@code **}{@code /db/*},
     * {@code **}{@code /logs}, {@code *.log} ...) were written for config and would otherwise drop a
     * project's script package called {@code db} or {@code logs}; a project's own repository in the
     * Separate layout has no ignore file at all. Then the few per-gateway files go back out.
     */
    public static final List<String> BLOCK = List.of(
            BLOCK_START,
            "!/projects/",
            "!/projects/**",
            "/projects/*/ignition/global-props/data.bin",
            "/projects/**/*-wal",
            "/projects/**/*-shm",
            "/projects/**/.resources/",
            BLOCK_END);

    public static final String ARCHIVE_DIR = "var/git-archive";

    private LayoutSwitch() {
    }

    /** Project folders: directories under {@code projects/} with a root {@code project.json}. */
    public static List<String> projectNames(Path dataDir) throws IOException {
        Path projects = dataDir.resolve("projects");
        if (!Files.isDirectory(projects)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(projects)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("project.json")))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /** Folders directly under {@code projects/} that have their own {@code .git}. */
    public static List<String> nestedRepos(Path dataDir) throws IOException {
        Path projects = dataDir.resolve("projects");
        if (!Files.isDirectory(projects)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(projects)) {
            return s.filter(p -> Files.isDirectory(p.resolve(".git")))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private static Git open(Path workTree) throws IOException {
        return new Git(new FileRepositoryBuilder().setGitDir(workTree.resolve(".git").toFile())
                .setWorkTree(workTree.toFile()).build());
    }

    private static void requireClean(Git git, String what, DirtyCheck dirty, List<String> problems)
            throws Exception {
        Repository r = git.getRepository();
        if (r.getRepositoryState() != RepositoryState.SAFE) {
            problems.add(what + " is in the middle of a " + r.getRepositoryState().getDescription());
            return;
        }
        if (r.resolve("HEAD") == null) {
            return;
        }
        int n = dirty.realChanges(r, git.status().call()).size();
        if (n > 0) {
            problems.add(what + " has " + n + " uncommitted change(s)");
        }
    }

    private static void refuseIf(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Nothing was changed: " + String.join("; ", problems)
                    + ". Commit or discard them first.");
        }
    }

    /** Separate to Combined. */
    public static Result toCombined(Path dataDir, PersonIdent author, String stamp, DirtyCheck dirty)
            throws Exception {
        if (!Files.isDirectory(dataDir.resolve(".git"))) {
            throw new IllegalStateException("Config versioning is not initialised; initialise it first.");
        }
        if (RepoLocator.readLayout(dataDir) == RepoLocator.Layout.COMBINED) {
            throw new IllegalStateException("The layout is already Combined.");
        }
        List<String> projects = projectNames(dataDir);
        List<String> problems = new ArrayList<>();
        try (Git gw = open(dataDir)) {
            requireClean(gw, "The gateway config repository", dirty, problems);
        }
        // Every folder with its own .git, not only projects: one left behind would sit in the
        // gateway repository as a nested repository.
        List<String> versioned = new ArrayList<>();
        for (String p : nestedRepos(dataDir)) {
            Path dir = dataDir.resolve("projects").resolve(p);
            {
                versioned.add(p);
                try (Git g = open(dir)) {
                    requireClean(g, "Project " + p, dirty, problems);
                }
            }
        }
        refuseIf(problems);

        Path gitignore = dataDir.resolve(".gitignore");
        String ignoreBefore = Files.exists(gitignore) ? Files.readString(gitignore, StandardCharsets.UTF_8) : null;
        List<Path[]> moved = new ArrayList<>();
        try {
            Path archive = dataDir.resolve(ARCHIVE_DIR);
            Files.createDirectories(archive);
            for (String p : versioned) {
                Path from = dataDir.resolve("projects").resolve(p).resolve(".git");
                Path to = archive.resolve(p + "-" + stamp + ".git");
                for (int i = 2; Files.exists(to); i++) {
                    to = archive.resolve(p + "-" + stamp + "-" + i + ".git");
                }
                Files.move(from, to);
                moved.add(new Path[] {from, to});
            }
            List<String> lines = ignoreBefore == null ? new ArrayList<>() : new ArrayList<>(ignoreBefore.lines().toList());
            if (!lines.contains(BLOCK_START)) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                    lines.add("");
                }
                lines.addAll(BLOCK);
                Files.writeString(gitignore, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            }
            String commit;
            try (Git gw = open(dataDir)) {
                gw.add().addFilepattern(".gitignore").call();
                if (Files.isDirectory(dataDir.resolve("projects"))) {
                    gw.add().addFilepattern("projects").call();
                }
                commit = gw.commit().setMessage(PolicyGate.trailer("Combine projects into gateway repo"))
                        .setAuthor(author).setCommitter(author).call().getName();
            }
            RepoLocator.writeLayout(dataDir, RepoLocator.Layout.COMBINED);
            List<String> archived = moved.stream().map(m -> dataDir.relativize(m[1]).toString().replace('\\', '/')).toList();
            return new Result(projects, archived, List.of(), commit);
        } catch (Exception e) {
            try (Git gw = open(dataDir)) {
                gw.reset().call();
            } catch (Exception ignored) {
                // best effort; the files below are what matter
            }
            restoreIgnore(gitignore, ignoreBefore);
            for (Path[] m : moved) {
                if (Files.exists(m[1]) && !Files.exists(m[0])) {
                    Files.move(m[1], m[0]);
                }
            }
            throw e;
        }
    }

    /** Combined to Separate. */
    public static Result toSeparate(Path dataDir, PersonIdent author, String stamp, boolean restoreHistory,
                                    DirtyCheck dirty) throws Exception {
        if (RepoLocator.readLayout(dataDir) != RepoLocator.Layout.COMBINED) {
            throw new IllegalStateException("The layout is already Separate.");
        }
        List<String> projects = projectNames(dataDir);
        List<String> problems = new ArrayList<>();
        try (Git gw = open(dataDir)) {
            requireClean(gw, "The gateway repository", dirty, problems);
        }
        for (String p : nestedRepos(dataDir)) {
            problems.add("projects/" + p + " already has its own .git folder");
        }
        refuseIf(problems);

        Path gitignore = dataDir.resolve(".gitignore");
        String ignoreBefore = Files.exists(gitignore) ? Files.readString(gitignore, StandardCharsets.UTF_8) : null;
        List<Path> created = new ArrayList<>();
        List<Path[]> restored = new ArrayList<>();
        try {
            for (String p : projects) {
                Path dir = dataDir.resolve("projects").resolve(p);
                Path archived = restoreHistory ? latestArchive(dataDir, p) : null;
                if (archived != null) {
                    Path gitDir = dir.resolve(".git");
                    Files.move(archived, gitDir);
                    restored.add(new Path[] {gitDir, archived});
                    try (Git g = open(dir)) {
                        g.add().addFilepattern(".").call();
                        g.add().setUpdate(true).addFilepattern(".").call();
                        Status s = g.status().call();
                        if (!s.getAdded().isEmpty() || !s.getChanged().isEmpty() || !s.getRemoved().isEmpty()) {
                            g.commit().setMessage(PolicyGate.trailer("Changes made while in the gateway repository"))
                                    .setAuthor(author).setCommitter(author).call();
                        }
                    }
                } else {
                    created.add(dir.resolve(".git"));
                    try (Git g = Git.init().setDirectory(dir.toFile())
                            .setInitialBranch(MasterRename.initialBranch(null)).call()) {
                        g.add().addFilepattern(".").call();
                        g.commit().setMessage(PolicyGate.trailer("Initial commit (from the gateway repository)"))
                                .setAuthor(author).setCommitter(author).call();
                    }
                }
            }
            String commit;
            try (Git gw = open(dataDir)) {
                gw.rm().setCached(true).addFilepattern("projects").call();
                if (ignoreBefore != null) {
                    List<String> lines = new ArrayList<>(ignoreBefore.lines().toList());
                    int start = lines.indexOf(BLOCK_START);
                    int end = lines.indexOf(BLOCK_END);
                    if (start >= 0 && end >= start) {
                        lines.subList(start, end + 1).clear();
                        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
                            lines.remove(lines.size() - 1);
                        }
                        Files.writeString(gitignore, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
                    }
                    gw.add().addFilepattern(".gitignore").call();
                }
                commit = gw.commit().setMessage(PolicyGate.trailer("Separate projects into their own repositories"))
                        .setAuthor(author).setCommitter(author).call().getName();
            }
            RepoLocator.writeLayout(dataDir, RepoLocator.Layout.SEPARATE);
            List<String> names = new ArrayList<>();
            restored.forEach(r -> names.add(dataDir.relativize(r[0].getParent()).toString().replace('\\', '/')));
            return new Result(projects, List.of(), names, commit);
        } catch (Exception e) {
            try (Git gw = open(dataDir)) {
                gw.reset().call();
            } catch (Exception ignored) {
                // best effort
            }
            restoreIgnore(gitignore, ignoreBefore);
            for (Path gitDir : created) {
                deleteRecursively(gitDir);
            }
            for (Path[] r : restored) {
                if (Files.exists(r[0]) && !Files.exists(r[1])) {
                    Files.move(r[0], r[1]);
                }
            }
            throw e;
        }
    }

    /** The newest {@code var/git-archive/<project>-<stamp>.git}, or null. */
    public static Path latestArchive(Path dataDir, String project) throws IOException {
        Path archive = dataDir.resolve(ARCHIVE_DIR);
        if (!Files.isDirectory(archive)) {
            return null;
        }
        Pattern name = Pattern.compile(Pattern.quote(project) + "-\\d{8}-\\d{6}(-\\d+)?\\.git");
        try (Stream<Path> s = Files.list(archive)) {
            return s.filter(p -> name.matcher(p.getFileName().toString()).matches())
                    // Without ".git", so "-2" (a second switch in the same second) sorts after its stamp.
                    .max(Comparator.comparing(p -> p.getFileName().toString().replaceAll("\\.git$", "")))
                    .orElse(null);
        }
    }

    private static void restoreIgnore(Path gitignore, String before) throws IOException {
        if (before == null) {
            Files.deleteIfExists(gitignore);
        } else {
            Files.writeString(gitignore, before, StandardCharsets.UTF_8);
        }
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
}
