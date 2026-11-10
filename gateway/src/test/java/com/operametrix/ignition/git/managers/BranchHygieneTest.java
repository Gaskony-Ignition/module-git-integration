package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
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

/** Branch classification and the safe-delete rules, against a real clone of a bare remote. */
class BranchHygieneTest {

    @TempDir
    Path tmp;

    Git remote;
    Git work;

    @BeforeEach
    void setUp() throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();

        // Seed the remote from a scratch clone so the working clone has an origin/main to track.
        Path seed = tmp.resolve("seed");
        try (Git s = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(seed.toFile()).call()) {
            commit(s, seed, "a.txt", "one");
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
        Files.writeString(dir.resolve(file), text + System.nanoTime());
        g.add().addFilepattern(".").call();
        g.commit().setMessage(text).setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
    }

    private Path dir() {
        return work.getRepository().getWorkTree().toPath();
    }

    /** Creates a branch with one commit, optionally pushes it with tracking, then returns to main. */
    private void branchWithCommit(String name, boolean push) throws Exception {
        work.checkout().setCreateBranch(true).setName(name).call();
        commit(work, dir(), name + ".txt", name);
        if (push) {
            work.push().setRemote("origin").setRefSpecs(new RefSpec(name + ":" + name)).call();
            StoredConfig cfg = work.getRepository().getConfig();
            cfg.setString("branch", name, "remote", "origin");
            cfg.setString("branch", name, "merge", "refs/heads/" + name);
            cfg.save();
        }
        work.checkout().setName("main").call();
    }

    private BranchHygiene.Info info(String name) throws Exception {
        return BranchHygiene.classify(work, Map.of("sync-branch", "sync")).stream()
                .filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    private void deleteOnRemote(String name) throws Exception {
        remote.branchDelete().setBranchNames(name).setForce(true).call();
        work.fetch().setRemote("origin").setRemoveDeletedRefs(true).call();
    }

    @Test
    void currentBranchIsProtected() throws Exception {
        BranchHygiene.Info i = info("main");
        assertThat(i.protectedReason()).isEqualTo("current");
        assertThat(i.upstream()).isEqualTo("origin/main");
        assertThat(i.gone()).isFalse();
        assertThat(i.ahead()).isZero();
    }

    @Test
    void mergedAndPushedBranchDeletesWithoutForce() throws Exception {
        branchWithCommit("done", true);
        work.merge().include(work.getRepository().findRef("done")).call();
        work.push().setRemote("origin").setRefSpecs(new RefSpec("main")).call();

        BranchHygiene.Info i = info("done");
        assertThat(i.merged()).isTrue();
        assertThat(i.ahead()).isZero();

        BranchHygiene.Result r = BranchHygiene.delete(work, "done", false, Map.of());
        assertThat(r.ok()).isTrue();
        assertThat(info("main")).isNotNull();
        assertThat(work.branchList().call()).noneMatch(b -> b.getName().endsWith("/done"));
    }

    @Test
    void unpushedBranchIsRefusedWithCountThenForced() throws Exception {
        work.checkout().setCreateBranch(true).setName("wip").call();
        commit(work, dir(), "w1.txt", "w1");
        commit(work, dir(), "w2.txt", "w2");
        work.checkout().setName("main").call();

        BranchHygiene.Info i = info("wip");
        assertThat(i.upstream()).isNull();
        assertThat(i.ahead()).isEqualTo(2);
        assertThat(i.merged()).isFalse();

        BranchHygiene.Result refused = BranchHygiene.delete(work, "wip", false, Map.of());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.kind()).isEqualTo(BranchHygiene.UNPUSHED);
        assertThat(refused.count()).isEqualTo(2);
        assertThat(work.getRepository().findRef("wip")).isNotNull();

        assertThat(BranchHygiene.delete(work, "wip", true, Map.of()).ok()).isTrue();
        assertThat(work.getRepository().findRef("wip")).isNull();
    }

    @Test
    void pushedButUnmergedBranchIsRefusedAsUnmerged() throws Exception {
        branchWithCommit("feature", true);

        BranchHygiene.Info i = info("feature");
        assertThat(i.ahead()).isZero();
        assertThat(i.merged()).isFalse();

        BranchHygiene.Result r = BranchHygiene.delete(work, "feature", false, Map.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.kind()).isEqualTo(BranchHygiene.UNMERGED);
        assertThat(r.count()).isEqualTo(1);
    }

    @Test
    void branchGoneAfterRemoteDeleteIsFlagged() throws Exception {
        branchWithCommit("topic", true);
        work.merge().include(work.getRepository().findRef("topic")).call();
        work.push().setRemote("origin").setRefSpecs(new RefSpec("main")).call();
        deleteOnRemote("topic");

        BranchHygiene.Info i = info("topic");
        assertThat(i.gone()).isTrue();
        assertThat(i.upstream()).isEqualTo("origin/topic");
        assertThat(i.merged()).isTrue();
        assertThat(i.ahead()).isZero();
        assertThat(BranchHygiene.delete(work, "topic", false, Map.of()).ok()).isTrue();
    }

    @Test
    void pruneRemovesTheRemoteTrackingRef() throws Exception {
        branchWithCommit("topic", true);
        assertThat(work.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call())
                .anyMatch(b -> b.getName().equals("refs/remotes/origin/topic"));
        deleteOnRemote("topic");
        assertThat(work.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call())
                .noneMatch(b -> b.getName().equals("refs/remotes/origin/topic"));
    }

    @Test
    void goneButUnmergedBranchKeepsItsCommitsCountedAsUnpushed() throws Exception {
        branchWithCommit("topic", true);
        deleteOnRemote("topic");

        BranchHygiene.Info i = info("topic");
        assertThat(i.gone()).isTrue();
        assertThat(i.ahead()).isEqualTo(1);
        assertThat(BranchHygiene.delete(work, "topic", false, Map.of()).kind()).isEqualTo(BranchHygiene.UNPUSHED);
    }

    @Test
    void protectedBranchRefusesEvenWithForce() throws Exception {
        work.branchCreate().setName("sync-branch").call();

        BranchHygiene.Info i = info("sync-branch");
        assertThat(i.protectedReason()).isEqualTo("sync");

        Map<String, String> guarded = Map.of("sync-branch", "sync");
        assertThat(BranchHygiene.delete(work, "sync-branch", false, guarded).kind())
                .isEqualTo(BranchHygiene.PROTECTED);
        assertThat(BranchHygiene.delete(work, "sync-branch", true, guarded).kind())
                .isEqualTo(BranchHygiene.PROTECTED);
        assertThat(BranchHygiene.delete(work, "main", true, guarded).kind()).isEqualTo(BranchHygiene.PROTECTED);
        assertThat(work.getRepository().findRef("sync-branch")).isNotNull();
    }

    @Test
    void mergedIsMeasuredAgainstTheCurrentBranchUpstream() throws Exception {
        // "side" is merged into local main but main has not been pushed: against origin/main
        // it is not merged, so it must not be offered for clean-up.
        work.checkout().setCreateBranch(true).setName("side").call();
        commit(work, dir(), "s.txt", "s");
        work.checkout().setName("main").call();
        work.merge().include(work.getRepository().findRef("side")).call();

        assertThat(info("side").merged()).isFalse();

        work.push().setRemote("origin").setRefSpecs(new RefSpec("main")).call();
        work.fetch().setRemote("origin").call();
        assertThat(info("side").merged()).isTrue();
    }

    @Test
    void classifyListsEveryLocalBranchSorted() throws Exception {
        work.branchCreate().setName("b").call();
        work.branchCreate().setName("a").call();
        List<String> names = BranchHygiene.classify(work, Map.of()).stream().map(BranchHygiene.Info::name).toList();
        assertThat(names).containsExactly("a", "b", "main");
    }
}
