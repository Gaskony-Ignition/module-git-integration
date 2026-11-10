package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The graph against a real clone: main with a merged feature branch, an open branch, a pushed
 * branch whose remote copy is then deleted (gone), and a project folder for the path filter.
 */
class BranchGraphTest {

    @TempDir
    Path tmp;

    Git remote;
    Git work;

    @BeforeEach
    void setUp() throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();
        Path seed = tmp.resolve("seed");
        try (Git s = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(seed.toFile()).call()) {
            commit(s, seed, "config/a.txt", "base");
            s.branchRename().setNewName("main").call();
            s.push().setRemote("origin").setRefSpecs(new RefSpec("main")).call();
        }
        remote = Git.open(bare.toFile());
        work = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(tmp.resolve("work").toFile())
                .setBranch("main").call();
    }

    @AfterEach
    void tearDown() {
        work.close();
        remote.close();
    }

    private void commit(Git g, Path dir, String file, String text) throws Exception {
        Path f = dir.resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text + System.nanoTime());
        g.add().addFilepattern(".").call();
        g.commit().setMessage(text).setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
    }

    private Path dir() {
        return work.getRepository().getWorkTree().toPath();
    }

    /** main: base, m1; feature (merged): f1; open: o1 (not merged); gone: pushed then deleted remotely. */
    private void build() throws Exception {
        work.checkout().setCreateBranch(true).setName("feature").call();
        commit(work, dir(), "projects/Alpha/f.txt", "f1");
        work.checkout().setName("main").call();
        commit(work, dir(), "config/b.txt", "m1");
        work.merge().include(work.getRepository().resolve("feature")).setCommit(true)
                .setFastForward(MergeCommand.FastForwardMode.NO_FF).setMessage("merge feature").call();

        work.checkout().setCreateBranch(true).setName("open").call();
        commit(work, dir(), "projects/Beta/o.txt", "o1");
        work.checkout().setName("main").call();

        work.checkout().setCreateBranch(true).setName("gone").call();
        commit(work, dir(), "config/g.txt", "g1");
        work.push().setRemote("origin").setRefSpecs(new RefSpec("gone:gone")).call();
        StoredConfig cfg = work.getRepository().getConfig();
        cfg.setString("branch", "gone", "remote", "origin");
        cfg.setString("branch", "gone", "merge", "refs/heads/gone");
        cfg.save();
        work.checkout().setName("main").call();
        remote.branchDelete().setBranchNames("gone").setForce(true).call();
        work.fetch().setRemote("origin").setRemoveDeletedRefs(true).call();
    }

    private BranchGraph.Result graph(String prefix) throws Exception {
        return BranchGraph.build(work, 0, prefix, Map.of("main", "delivery"));
    }

    @Test
    void parentsRefsAndBranchInfo() throws Exception {
        build();
        BranchGraph.Result r = graph(null);

        assertThat(r.commits()).extracting(BranchGraph.Commit::subject)
                .contains("base", "f1", "m1", "merge feature", "o1", "g1");
        BranchGraph.Commit merge = r.commits().stream()
                .filter(c -> c.subject().equals("merge feature")).findFirst().orElseThrow();
        assertThat(merge.parents()).hasSize(2);

        // Topological: every parent comes after its child.
        for (BranchGraph.Edge e : r.edges()) {
            assertThat(e.to()).isGreaterThan(e.from());
        }

        assertThat(r.branch()).isEqualTo("main");
        assertThat(r.refs()).anyMatch(x -> x.type().equals("local") && x.name().equals("main") && x.head());
        assertThat(r.refs()).anyMatch(x -> x.type().equals("remote") && x.name().equals("origin/main"));
        assertThat(r.refs()).noneMatch(x -> x.name().equals("origin/gone"));

        BranchGraph.Branch gone = branch(r, "gone");
        assertThat(gone.info().gone()).isTrue();
        // No upstream left, so ahead counts everything on no remote: g1 plus main's unpushed three.
        assertThat(gone.info().ahead()).isEqualTo(4);
        BranchGraph.Branch feature = branch(r, "feature");
        // Merged is measured against origin/main, which has not got the merge yet.
        assertThat(feature.info().merged()).isFalse();
        assertThat(branch(r, "open").info().merged()).isFalse();
        assertThat(branch(r, "main").info().protectedReason()).isEqualTo("current");
        // main is ahead of origin/main by the merge and m1.
        assertThat(branch(r, "main").info().ahead()).isEqualTo(3);
        assertThat(branch(r, "main").behind()).isZero();
    }

    @Test
    void lanesDoNotCollide() throws Exception {
        build();
        BranchGraph.Result r = graph(null);
        List<BranchGraph.Commit> cs = r.commits();

        // A line passing a row never runs through that row's own dot.
        for (BranchGraph.Edge e : r.edges()) {
            for (int i = e.from() + 1; i < e.to(); i++) {
                assertThat(cs.get(i).lane()).as("row %d against edge %d->%d", i, e.from(), e.to())
                        .isNotEqualTo(e.lane());
            }
        }
        // Edges end on their parents' dots and start on their children's.
        for (BranchGraph.Edge e : r.edges()) {
            assertThat(e.fromLane()).isEqualTo(cs.get(e.from()).lane());
            assertThat(e.toLane()).isEqualTo(cs.get(e.to()).lane());
        }
        // o1 and g1 are both tips off the merge, alive at once, so they cannot share a column;
        // g1 sits in the merge's own column because its line runs straight into it.
        assertThat(laneOf(r, "o1")).isNotEqualTo(laneOf(r, "g1"));
        assertThat(laneOf(r, "g1")).isEqualTo(laneOf(r, "merge feature"));
        assertThat(r.lanes()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void pathFilterFlagsCommitsOutsideTheProject() throws Exception {
        build();
        BranchGraph.Result r = graph("projects/Alpha/");
        // Same commits either way; only the flag differs.
        assertThat(r.commits()).hasSameSizeAs(graph(null).commits());
        assertThat(touches(r, "f1")).isTrue();
        assertThat(touches(r, "m1")).isFalse();
        assertThat(touches(r, "g1")).isFalse();
        assertThat(touches(r, "o1")).isFalse();
        assertThat(touches(r, "base")).isFalse();
        // The merge brings Alpha's file into main, so against its first parent it touches Alpha.
        assertThat(touches(r, "merge feature")).isTrue();
        assertThat(graph(null).commits()).allMatch(BranchGraph.Commit::touches);
    }

    @Test
    void limitTruncates() throws Exception {
        build();
        BranchGraph.Result r = BranchGraph.build(work, 2, null, Map.of());
        assertThat(r.commits()).hasSize(2);
        assertThat(r.truncated()).isTrue();
        assertThat(r.edges()).allMatch(e -> e.to() < 2);
    }

    private static BranchGraph.Branch branch(BranchGraph.Result r, String name) {
        return r.branches().stream().filter(b -> b.info().name().equals(name)).findFirst().orElseThrow();
    }

    private static int laneOf(BranchGraph.Result r, String subject) {
        return r.commits().stream().filter(c -> c.subject().equals(subject)).findFirst().orElseThrow().lane();
    }

    private static boolean touches(BranchGraph.Result r, String subject) {
        return r.commits().stream().filter(c -> c.subject().equals(subject)).findFirst().orElseThrow().touches();
    }
}
