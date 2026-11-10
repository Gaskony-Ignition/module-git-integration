package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BranchSwitchTest {

    @TempDir
    Path tmp;

    Git remote;
    Git work;

    /** Every uncommitted or untracked path counts, as the gateway's check does bar reformatting. */
    static final BranchSwitch.Dirty DIRTY = new BranchSwitch.Dirty() {
        public Set<String> real(Repository repo, Status s) {
            Set<String> out = new HashSet<>(s.getUncommittedChanges());
            out.addAll(s.getUntracked());
            return out;
        }

        public Set<String> reformatted(Repository repo, Status s) {
            return Set.of();
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();
        Path seed = tmp.resolve("seed");
        try (Git s = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(seed.toFile()).call()) {
            commit(s, seed, "config/a.txt", "base");
            commit(s, seed, "projects/Alpha/p.txt", "alpha");
            s.branchRename().setNewName("main").call();
            s.push().setRemote("origin").setRefSpecs(new RefSpec("main")).call();
            s.checkout().setCreateBranch(true).setName("release").call();
            commit(s, seed, "projects/Alpha/r.txt", "release");
            s.push().setRemote("origin").setRefSpecs(new RefSpec("release")).call();
        }
        remote = Git.open(bare.toFile());
        work = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(tmp.resolve("work").toFile())
                .setBranch("main").call();
        work.checkout().setCreateBranch(true).setName("local").call();
        work.checkout().setName("main").call();
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

    @Test
    void refusesWhenDirtyAndListsTheFiles() throws Exception {
        Files.writeString(dir().resolve("projects/Alpha/p.txt"), "edited");
        BranchSwitch.Result r = BranchSwitch.switchTo(work, "local", null, "", false, DIRTY, null);
        assertThat(r.refused()).isEqualTo(BranchSwitch.DIRTY);
        assertThat(r.files()).containsExactly("projects/Alpha/p.txt");
        assertThat(work.getRepository().getBranch()).isEqualTo("main");
    }

    @Test
    void refusesAgainstTheBranchADeliveryFollows() throws Exception {
        BranchSwitch.Result r = BranchSwitch.switchTo(work, "local", "main", "Projects > Edit > Delivery",
                false, DIRTY, null);
        assertThat(r.refused()).isEqualTo(BranchSwitch.FOLLOWED);
        assertThat(r.message()).contains("'main'").contains("Projects > Edit > Delivery");
        assertThat(work.getRepository().getBranch()).isEqualTo("main");
        // The followed branch itself is fine.
        assertThat(BranchSwitch.switchTo(work, "local", "local", "", false, DIRTY, null).ok()).isTrue();
    }

    @Test
    void switchesAndLeavesACleanTree() throws Exception {
        BranchSwitch.Result r = BranchSwitch.switchTo(work, "local", null, "", false, DIRTY, null);
        assertThat(r.ok()).isTrue();
        assertThat(work.getRepository().getBranch()).isEqualTo("local");
        assertThat(work.status().call().isClean()).isTrue();
    }

    @Test
    void aRemoteBranchGetsALocalTrackingBranch() throws Exception {
        BranchSwitch.Result r = BranchSwitch.switchTo(work, "origin/release", null, "", false, DIRTY, null);
        assertThat(r.ok()).isTrue();
        assertThat(r.created()).isTrue();
        assertThat(r.branch()).isEqualTo("release");
        assertThat(work.getRepository().getBranch()).isEqualTo("release");
        assertThat(Files.exists(dir().resolve("projects/Alpha/r.txt"))).isTrue();
        assertThat(work.getRepository().getConfig().getString("branch", "release", "merge"))
                .isEqualTo("refs/heads/release");
        assertThat(work.status().call().isClean()).isTrue();
    }

    @Test
    void anUnknownBranchIsRefused() throws Exception {
        assertThat(BranchSwitch.switchTo(work, "nope", null, "", false, DIRTY, null).refused())
                .isEqualTo(BranchSwitch.MISSING);
    }

    @Test
    void combinedCommitsConfigFirstButStillRefusesProjectEdits() throws Exception {
        Files.writeString(dir().resolve("config/a.txt"), "changed config");
        Runnable commitConfig = () -> {
            try {
                work.add().addFilepattern("config").call();
                work.commit().setMessage("auto").setAuthor("t", "t@example.com")
                        .setCommitter("t", "t@example.com").call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        BranchSwitch.Result ok = BranchSwitch.switchTo(work, "local", null, "", true, DIRTY, commitConfig);
        assertThat(ok.ok()).isTrue();
        // The auto-commit landed on the branch we left, and the tree is clean after.
        assertThat(work.log().add(work.getRepository().resolve("main")).call().iterator().next().getShortMessage())
                .isEqualTo("auto");
        assertThat(work.status().call().isClean()).isTrue();

        work.checkout().setName("main").call();
        Files.writeString(dir().resolve("projects/Alpha/p.txt"), "edited");
        Files.writeString(dir().resolve("config/a.txt"), "more config");
        BranchSwitch.Result refused = BranchSwitch.switchTo(work, "local", null, "",
                true, DIRTY, commitConfig);
        assertThat(refused.refused()).isEqualTo(BranchSwitch.DIRTY);
        assertThat(refused.files()).containsExactly("projects/Alpha/p.txt");
        assertThat(work.getRepository().getBranch()).isEqualTo("main");
    }
}
