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
     * Appended after the root rules ({@code /*}), since the last matching rule wins. The config
     * globs are scoped to {@code /config/} when this block goes in ({@link IgnoreRules#scopeToConfig}),
     * so re-including {@code projects/} is enough and rules written under it work. Then the few
     * per-gateway runtime files go back out.
     */
    public static final List<String> BLOCK = List.of(
            BLOCK_START,
            "!/projects/",
            "/projects/**/*-wal",
            "/projects/**/*-shm",
            "/projects/**/.resources/",
            BLOCK_END);

    /**
     * A project's per-gateway properties: excluded by default, but an ordinary line after the block,
     * so the Git ignore tab can tick it in to share it.
     */
    public static final String DATA_BIN = "/projects/*/ignition/global-props/data.bin";

    /** The 4.0.0–4.3.x block's catch-all re-include, which overrode every rule under projects/. */
    static final String OLD_REINCLUDE = "!/projects/**";

    /** The Combined rules for {@code lines} (no block yet): globs scoped, block and data.bin added. */
    static List<String> withCombinedRules(List<String> lines) {
        List<String> out = new ArrayList<>(IgnoreRules.scopeToConfig(lines));
        if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
            out.add("");
        }
        out.addAll(BLOCK);
        out.add(DATA_BIN);
        return out;
    }

    /**
     * Rewrites a 4.0.0–4.3.x Combined {@code .gitignore} once: scopes the managed config globs, drops
     * the catch-all re-include and moves data.bin out of the block. Only those managed lines change.
     *
     * @return the new lines, or null when there is nothing to change
     */
    public static List<String> migrateCombinedRules(List<String> lines) {
        int start = lines.indexOf(BLOCK_START);
        int end = lines.indexOf(BLOCK_END);
        if (start < 0 || end < start || !lines.subList(start, end).contains(OLD_REINCLUDE)) {
            return null;
        }
        List<String> out = new ArrayList<>(IgnoreRules.scopeToConfig(lines));
        List<String> block = out.subList(start, end + 1);
        boolean hadDataBin = block.remove(DATA_BIN);
        block.remove(OLD_REINCLUDE);
        int newEnd = out.indexOf(BLOCK_END);
        if (hadDataBin) {
            out.add(newEnd + 1, DATA_BIN);
        }
        return out;
    }

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
                lines = withCombinedRules(lines);
                // The deny block stays last: git's last match wins.
                Files.writeString(gitignore, IgnoreRules.withDenyBlock(String.join("\n", lines)), StandardCharsets.UTF_8);
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
        return toSeparate(dataDir, author, stamp, restoreHistory, false, dirty);
    }

    /**
     * Why a project folder must not get a repository of its own here, or null: it leads outside the
     * data directory (a symbolic link), sits on another file system (a mount), or is inside another
     * git work tree below the data directory. A {@code .git} planted there would land in someone
     * else's checkout.
     */
    static String foreignFolder(Path dataDir, String project) throws IOException {
        Path dir = dataDir.resolve("projects").resolve(project);
        Path realData = dataDir.toRealPath();
        Path real = dir.toRealPath();
        String name = "projects/" + project;
        if (Files.isSymbolicLink(dir) || !real.startsWith(realData)) {
            return name + " is a link to " + real + ", outside the data directory";
        }
        if (!Files.getFileStore(real).equals(Files.getFileStore(realData))) {
            return name + " is a separate mount (" + Files.getFileStore(real).name() + ")";
        }
        for (Path p = real.getParent(); p != null && !p.equals(realData); p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return name + " is inside another git work tree (" + realData.relativize(p) + ")";
            }
        }
        return null;
    }

    /**
     * Combined to Separate. A {@code projects/<name>/.git} left over from before blocks the switch
     * unless {@code archiveLeftovers}, which moves it to {@code var/git-archive} as the switch to
     * Combined does. A folder that leads outside the data directory is always refused.
     */
    public static Result toSeparate(Path dataDir, PersonIdent author, String stamp, boolean restoreHistory,
                                    boolean archiveLeftovers, DirtyCheck dirty) throws Exception {
        if (RepoLocator.readLayout(dataDir) != RepoLocator.Layout.COMBINED) {
            throw new IllegalStateException("The layout is already Separate.");
        }
        List<String> projects = projectNames(dataDir);
        List<String> foreign = new ArrayList<>();
        for (String p : projects) {
            String why = foreignFolder(dataDir, p);
            if (why != null) {
                foreign.add(why);
            }
        }
        if (!foreign.isEmpty()) {
            throw new IllegalStateException("Nothing was changed: " + String.join("; ", foreign)
                    + ". A project's own repository would be created there; move the project into the data"
                    + " directory, or remove the link or mount, first.");
        }
        List<String> problems = new ArrayList<>();
        List<String> leftovers = nestedRepos(dataDir);
        // Archived before the clean check: a nested .git makes its folder's tracked files read as
        // changed in the gateway repository. Put back if the switch is refused.
        List<Path[]> parked = new ArrayList<>();
        if (archiveLeftovers) {
            Path archive = dataDir.resolve(ARCHIVE_DIR);
            Files.createDirectories(archive);
            for (String p : leftovers) {
                Path to = archive.resolve(p + "-leftover-" + stamp + ".git");
                for (int i = 2; Files.exists(to); i++) {
                    to = archive.resolve(p + "-leftover-" + stamp + "-" + i + ".git");
                }
                Path from = dataDir.resolve("projects").resolve(p).resolve(".git");
                Files.move(from, to);
                parked.add(new Path[] {from, to});
            }
        } else {
            for (String p : leftovers) {
                problems.add("projects/" + p + " already has its own .git folder (tick Archive leftover .git"
                        + " folders to move it to " + ARCHIVE_DIR + ")");
            }
        }
        try (Git gw = open(dataDir)) {
            requireClean(gw, "The gateway repository", dirty, problems);
        }
        if (!problems.isEmpty()) {
            for (Path[] m : parked) {
                Files.move(m[1], m[0]);
            }
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
                        RepoHardening.harden(g.getRepository());
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
                        lines.remove(DATA_BIN);
                        lines = new ArrayList<>(IgnoreRules.unscope(lines));
                        // The blank lines that set the block apart no longer separate anything.
                        for (int i = lines.size() - 1; i > 0; i--) {
                            if (lines.get(i).isBlank() && lines.get(i - 1).isBlank()) {
                                lines.remove(i);
                            }
                        }
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
            for (Path[] m : parked) {
                if (Files.exists(m[1]) && !Files.exists(m[0])) {
                    Files.move(m[1], m[0]);
                }
            }
            throw e;
        }
    }

    /** The newest {@code var/git-archive/<project>-<stamp>.git}, or null (never a leftover). */
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
