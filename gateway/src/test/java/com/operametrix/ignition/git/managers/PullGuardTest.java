package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A pull of a branch merged and deleted on the remote: the gone upstream is found after a pruning
 * fetch (before JGit's RefNotAdvertisedException), and the branch to go back to is chosen.
 */
class PullGuardTest {

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
            commit(s, seed, "a.txt");
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

    private static void commit(Git g, Path dir, String file) throws Exception {
        Files.writeString(dir.resolve(file), file + System.nanoTime());
        g.add().addFilepattern(".").call();
        g.commit().setMessage(file).setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
    }

    /** feature-1 pushed with tracking, merged into main on the remote, then deleted there. */
    private void featureMergedAndDeleted() throws Exception {
        Path dir = work.getRepository().getWorkTree().toPath();
        work.checkout().setCreateBranch(true).setName("feature-1").call();
        commit(work, dir, "f.txt");
        work.push().setRemote("origin").setRefSpecs(new RefSpec("feature-1:feature-1")).call();
        StoredConfig cfg = work.getRepository().getConfig();
        cfg.setString("branch", "feature-1", "remote", "origin");
        cfg.setString("branch", "feature-1", "merge", "refs/heads/feature-1");
        cfg.save();
        // "Merge" on the remote: fast-forward main to the feature, then delete the feature.
        RefUpdate main = remote.getRepository().updateRef("refs/heads/main");
        main.setNewObjectId(remote.getRepository().resolve("refs/heads/feature-1"));
        main.setForceUpdate(true);
        main.update();
        RefUpdate del = remote.getRepository().updateRef("refs/heads/feature-1");
        del.setForceUpdate(true);
        del.delete();
    }

    private void prune() throws Exception {
        work.fetch().setRemote("origin").setRemoveDeletedRefs(true).call();
    }

    @Test
    void pullOfDeletedBranchIsWhatJGitRefuses() throws Exception {
        featureMergedAndDeleted();
        assertThatThrownBy(() -> work.pull().setRemote("origin").call())
                .isInstanceOf(RefNotAdvertisedException.class);
    }

    @Test
    void goneUpstreamFoundAfterPruningFetch() throws Exception {
        featureMergedAndDeleted();
        assertThat(PullGuard.goneUpstream(work.getRepository())).isNull();
        prune();
        PullGuard.Gone g = PullGuard.goneUpstream(work.getRepository());
        assertThat(g).isEqualTo(new PullGuard.Gone("feature-1", "origin", "feature-1"));
        assertThat(g.message()).isEqualTo("feature-1's branch on origin was deleted — usually because it was merged.");
    }

    @Test
    void noGoneUpstreamWhileTheBranchExists() throws Exception {
        prune();
        assertThat(PullGuard.goneUpstream(work.getRepository())).isNull();
    }

    @Test
    void noGoneUpstreamForALocalOnlyBranch() throws Exception {
        work.checkout().setCreateBranch(true).setName("local").call();
        prune();
        assertThat(PullGuard.goneUpstream(work.getRepository())).isNull();
    }

    @Test
    void defaultBranchIsOriginHeadWhenSet() throws Exception {
        // A clone records origin/HEAD -> origin/main only on some servers; set it as one would.
        remote.branchCreate().setName("develop").setStartPoint("main").call();
        prune();
        RefUpdate u = work.getRepository().updateRef("refs/remotes/origin/HEAD");
        u.link("refs/remotes/origin/develop");
        assertThat(PullGuard.defaultBranch(work.getRepository(), "origin", null, "feature-1"))
                .isEqualTo("develop");
    }

    @Test
    void defaultBranchFallsBackToConfiguredThenMainThenMaster() throws Exception {
        remote.branchCreate().setName("release").setStartPoint("main").call();
        remote.branchCreate().setName("master").setStartPoint("main").call();
        prune();
        var repo = work.getRepository();
        assertThat(PullGuard.defaultBranch(repo, "origin", "release", "x")).isEqualTo("release");
        assertThat(PullGuard.defaultBranch(repo, "origin", "nope", "x")).isEqualTo("main");
        assertThat(PullGuard.defaultBranch(repo, "origin", null, "main")).isEqualTo("master");
        assertThat(PullGuard.defaultBranch(repo, "origin", null, null)).isEqualTo("main");
    }

    @Test
    void defaultBranchNullWhenRemoteHasNone() throws Exception {
        remote.branchCreate().setName("trunk").setStartPoint("main").call();
        assertThat(PullGuard.defaultBranch(work.getRepository(), "origin", null, "main")).isNull();
    }

    @Test
    void encodeCarriesBranchRemoteDefaultAndMessage() {
        String s = PullGuard.encode(new PullGuard.Gone("feature-1", "origin", "feature-1"), "main");
        assertThat(s).startsWith("UPSTREAM_GONE:feature-1\torigin\tmain\tfeature-1's branch");
    }

    @Test
    void plainMessagesForJGitFailures() {
        assertThat(PullGuard.plain(new RefNotAdvertisedException("Remote origin did not advertise Ref")))
                .startsWith("That branch is not on the remote");
        assertThat(PullGuard.plain(new org.eclipse.jgit.api.errors.TransportException(
                "x", new org.eclipse.jgit.errors.TransportException("https://h/r.git: not authorized"))))
                .startsWith("The remote refused the credential");
        RuntimeException e = PullGuard.error(new RefNotAdvertisedException("raw"));
        assertThat(e.getMessage()).contains(PullGuard.DETAILS).contains("raw");
    }
}
