package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A remote branch laid out another way, or with unrelated history, is refused with the fix. */
class LayoutCheckTest {

    @TempDir
    Path tmp;

    private static ObjectId commit(Git g, Path dir, String file) throws Exception {
        Path f = dir.resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, file + System.nanoTime());
        g.add().addFilepattern(".").call();
        return g.commit().setMessage(file).setAuthor("t", "t@example.com")
                .setCommitter("t", "t@example.com").call();
    }

    @Test
    void otherLayoutRefusedWithTheRestructureSteps() throws Exception {
        Path d = tmp.resolve("a");
        try (Git g = Git.init().setDirectory(d.toFile()).setInitialBranch("main").call()) {
            ObjectId tip = commit(g, d, "gateway/config/resources/core/x.json");
            commit(g, d, "project/project.json");
            tip = g.getRepository().resolve("HEAD");
            String p = LayoutCheck.problem(g.getRepository(), tip, null, "origin/main", true);
            assertThat(p).contains("no config/ folder").contains("gateway/").contains("project/")
                    .contains("git mv gateway/config config").contains("projects/<ProjectName>")
                    .contains(LayoutCheck.DOC);
        }
    }

    @Test
    void sameLayoutAndSharedHistoryPasses() throws Exception {
        Path d = tmp.resolve("b");
        try (Git g = Git.init().setDirectory(d.toFile()).setInitialBranch("main").call()) {
            ObjectId base = commit(g, d, "config/resources/core/x.json");
            ObjectId tip = commit(g, d, "projects/P/project.json");
            assertThat(LayoutCheck.problem(g.getRepository(), tip, base, "origin/main", true)).isNull();
            assertThat(LayoutCheck.problem(g.getRepository(), null, base, "origin/main", true)).isNull();
        }
    }

    @Test
    void unrelatedHistoryRefused() throws Exception {
        Path d = tmp.resolve("c");
        try (Git g = Git.init().setDirectory(d.toFile()).setInitialBranch("main").call()) {
            ObjectId local = commit(g, d, "config/a.json");
            g.checkout().setOrphan(true).setName("other").call();
            g.rm().addFilepattern("config/a.json").call();
            ObjectId remote = commit(g, d, "config/b.json");
            assertThat(LayoutCheck.problem(g.getRepository(), remote, local, "origin/main", false))
                    .contains("unrelated");
            // A replace resets to the branch by design: layout only.
            assertThat(LayoutCheck.problem(g.getRepository(), remote, null, "origin/main", false)).isNull();
        }
    }
}
