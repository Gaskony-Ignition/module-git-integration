package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** New repositories start on main; an old one on master is renamed in place, history unchanged. */
class MasterRenameTest {

    @TempDir
    Path tmp;

    private Git repoOnMaster(Path dir) throws Exception {
        Git g = Git.init().setDirectory(dir.toFile()).setInitialBranch("master").call();
        Files.writeString(dir.resolve("a.txt"), "a");
        g.add().addFilepattern(".").call();
        g.commit().setMessage("a").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
        return g;
    }

    @Test
    void newRepositoryStartsOnTheConfiguredBranchOrMain() throws Exception {
        try (Git g = Git.init().setDirectory(tmp.resolve("n").toFile())
                .setInitialBranch(MasterRename.initialBranch(null)).call()) {
            assertThat(g.getRepository().getBranch()).isEqualTo("main");
        }
        try (Git g = Git.init().setDirectory(tmp.resolve("r").toFile())
                .setInitialBranch(MasterRename.initialBranch(" release ")).call()) {
            assertThat(g.getRepository().getBranch()).isEqualTo("release");
        }
    }

    @Test
    void offeredOnlyForALoneMasterAndAnotherName() throws Exception {
        try (Git g = repoOnMaster(tmp.resolve("w"))) {
            assertThat(MasterRename.target(g.getRepository(), "main")).isEqualTo("main");
            assertThat(MasterRename.target(g.getRepository(), "master")).isNull();
            assertThat(MasterRename.target(g.getRepository(), "")).isNull();
            g.branchCreate().setName("feature").call();
            assertThat(MasterRename.target(g.getRepository(), "main")).isNull();
        }
    }

    @Test
    void renameKeepsHistoryAndTracksTheRemoteBranch() throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();
        try (Git g = repoOnMaster(tmp.resolve("w"))) {
            g.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(bare.toUri().toString()))
                    .call();
            // The 4.1.0 push mapping: local master pushed to the remote's main.
            g.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/master:refs/heads/main")).call();
            g.fetch().setRemote("origin").call();
            ObjectId before = g.getRepository().resolve("HEAD");

            MasterRename.rename(g, "main", "origin");

            assertThat(g.getRepository().getBranch()).isEqualTo("main");
            assertThat(g.getRepository().resolve("HEAD")).isEqualTo(before);
            assertThat(g.getRepository().exactRef("refs/heads/master")).isNull();
            assertThat(g.getRepository().getConfig().getString("branch", "main", "merge"))
                    .isEqualTo("refs/heads/main");
            assertThat(PullGuard.goneUpstream(g.getRepository())).isNull();
        }
    }

    @Test
    void renameRefusedWhenNotALoneMaster() throws Exception {
        try (Git g = repoOnMaster(tmp.resolve("w"))) {
            g.branchCreate().setName("x").call();
            assertThatThrownBy(() -> MasterRename.rename(g, "main", "origin"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** 4.5.3: a lock left in .git used to reach the page as "Unexpected rename result LOCK_FAILURE". */
    @Test
    void lockedBranchFailsInPlainWordsAndRenamesNothing() throws Exception {
        try (Git g = repoOnMaster(tmp.resolve("w"))) {
            ObjectId before = g.getRepository().resolve("HEAD");
            Files.writeString(g.getRepository().getDirectory().toPath().resolve("refs/heads/main.lock"), "");

            assertThatThrownBy(() -> MasterRename.rename(g, "main", "origin"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("The branch is locked")
                    .hasMessageContaining(PullGuard.DETAILS)
                    .satisfies(e -> assertThat(e.getMessage().substring(0,
                            e.getMessage().indexOf(PullGuard.DETAILS))).doesNotContain("LOCK_FAILURE"));

            assertThat(g.getRepository().getBranch()).isEqualTo("master");
            assertThat(g.getRepository().resolve("refs/heads/master")).isEqualTo(before);
            assertThat(g.getRepository().exactRef("refs/heads/main")).isNull();

            // The stale lock removed, the same rename goes through.
            Files.delete(g.getRepository().getDirectory().toPath().resolve("refs/heads/main.lock"));
            MasterRename.rename(g, "main", "origin");
            assertThat(g.getRepository().getBranch()).isEqualTo("main");
            assertThat(g.getRepository().resolve("HEAD")).isEqualTo(before);
        }
    }

    @Test
    void everyJGitRenameFailureHasAPlainMessage() {
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.RefAlreadyExistsException("Ref main already exists"), "main"))
                .startsWith("A branch named main already exists");
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.InvalidRefNameException("Branch name m..n is not allowed"), "m..n"))
                .startsWith("m..n is not a valid branch name");
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.RefNotFoundException("Ref master cannot be resolved"), "main"))
                .startsWith("There is no local master branch");
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.DetachedHeadException(), "main"))
                .startsWith("The repository has a commit checked out");
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.JGitInternalException(
                "Unexpected rename result LOCK_FAILURE"), "main")).startsWith("The branch is locked");
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.JGitInternalException(
                "Cannot lock /x/.git/HEAD", new org.eclipse.jgit.errors.LockFailedException(new java.io.File("HEAD"))),
                "main")).startsWith("The branch is locked");
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.JGitInternalException(
                "Unexpected rename result IO_FAILURE"), "main")).startsWith("The gateway could not write");
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.JGitInternalException(
                "Unexpected rename result REJECTED_OTHER_REASON"), "main"))
                .startsWith("Git refused to rename master to main (REJECTED_OTHER_REASON)");
        assertThat(MasterRename.plain(new org.eclipse.jgit.api.errors.JGitInternalException(
                "Rename failed with unknown reason"), "main")).startsWith("Could not rename master to main.");
    }
}
