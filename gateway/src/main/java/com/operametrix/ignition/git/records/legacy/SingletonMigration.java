package com.operametrix.ignition.git.records.legacy;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 4.5.0: the Git policy, the gateway repository's remote and the runner settings became platform
 * singletons, so the gateway's own Resource Definitions panel (Create Override, Move Definition,
 * Remove Override) can work on them. Before, each was a named resource with one fixed name:
 * {@code <collection>/com.operametrix.ignition.git/git-policy/policy/}. A singleton lives at the
 * type's folder itself, {@code .../git-policy/}, and the platform's singleton route reads nothing
 * else.
 *
 * <p>This moves each one's files up a level, in core and in every mode, before the module reads
 * them; the caller then runs a config scan so the running gateway sees the moves. It works on
 * files, not through the resource API, so each mode's copy moves as it is (signature and all)
 * and the config repository sees a rename rather than a delete and a new file. Moving
 * again finds nothing, so it needs no marker. Where both exist (a hand copy), the singleton is
 * kept and the old folder left for someone to look at: it is invisible to the module either way.
 */
public final class SingletonMigration {

    public static final String MODULE_ID = "com.operametrix.ignition.git";

    /** Each converted type and the one name it was stored under. */
    public static final Map<String, String> LEGACY = legacy();

    private static Map<String, String> legacy() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("git-policy", "policy");
        m.put("git-config-remote", "1");
        m.put("git-runner", "runner");
        return m;
    }

    /** What one run did: folders moved, and folders left because a singleton was already there. */
    public record Result(List<String> moved, List<String> kept) {
        public boolean changed() {
            return !moved.isEmpty();
        }
    }

    private SingletonMigration() {
    }

    /**
     * @param resources the gateway's {@code data/config/resources} folder
     */
    public static Result migrate(Path resources) throws IOException {
        List<String> moved = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        if (!Files.isDirectory(resources)) {
            return new Result(moved, kept);
        }
        try (DirectoryStream<Path> collections = Files.newDirectoryStream(resources, Files::isDirectory)) {
            for (Path collection : collections) {
                Path module = collection.resolve(MODULE_ID);
                for (Map.Entry<String, String> e : LEGACY.entrySet()) {
                    Path type = module.resolve(e.getKey());
                    Path old = type.resolve(e.getValue());
                    if (!Files.isDirectory(old)) {
                        continue;
                    }
                    String label = resources.relativize(old).toString().replace('\\', '/');
                    if (Files.exists(type.resolve("resource.json")) || Files.exists(type.resolve("config.json"))) {
                        kept.add(label);
                        continue;
                    }
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(old)) {
                        for (Path f : files) {
                            Files.move(f, type.resolve(f.getFileName().toString()));
                        }
                    }
                    Files.delete(old);
                    moved.add(label);
                }
            }
        }
        return new Result(moved, kept);
    }
}
