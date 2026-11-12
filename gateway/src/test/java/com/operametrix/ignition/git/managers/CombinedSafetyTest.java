package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * In Combined one repository holds config and every project: a Designer's checkout must not put
 * other people's work aside, a config commit must not complete a Designer's open merge, and a
 * pull must not be blocked by another project's reformatted files.
 */
class CombinedSafetyTest {

    @TempDir
    Path data;

    Git git;
    RepoLocator.Repo repo;
    final String view = "projects/A/views/V/view.json";
    final String otherProject = "projects/B/views/W/view.json";
    final String config = "config/resources/core/x/config.json";

    void write(String rel, String text) throws Exception {
        Path p = data.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    String read(String rel) throws Exception {
        return Files.readString(data.resolve(rel));
    }

    RevCommit commit(String msg) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(msg).setAuthor("t", "t@x").setCommitter("t", "t@x").call();
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = RepoLocator.forProject(data, "A", RepoLocator.Layout.COMBINED);
        git = Git.init().setDirectory(data.toFile()).setInitialBranch("main").call();
        write(view, "{\"v\":1}");
        write(otherProject, "{\"o\":1}");
        write(config, "{\"c\":1}");
        commit("init");
        git.branchCreate().setName("feature").call();
    }

    @AfterEach
    void tearDown() {
        git.close();
    }

    @Test
    void checkoutRefusesAnotherProjectsWorkAndStashesNothing() throws Exception {
        write(otherProject, "{\"o\":9}");

        assertThatThrownBy(() -> GitManager.checkoutBranch(repo, "feature"))
                .hasMessageContaining(otherProject);

        assertThat(git.getRepository().getBranch()).isEqualTo("main");
        assertThat(read(otherProject)).isEqualTo("{\"o\":9}");
        assertThat(git.stashList().call()).isEmpty();
    }

    @Test
    void checkoutRefusesConfigAndThisProjectsWork() throws Exception {
        write(config, "{\"c\":9}");
        write(view, "{\"v\":9}");

        assertThatThrownBy(() -> GitManager.checkoutCommit(repo, "feature"))
                .hasMessageContaining(config)
                .hasMessageContaining("views/V/view.json");

        assertThat(read(config)).isEqualTo("{\"c\":9}");
        assertThat(read(view)).isEqualTo("{\"v\":9}");
        assertThat(git.stashList().call()).isEmpty();
    }

    @Test
    void checkoutGoesAheadOverReformattedFiles() throws Exception {
        write(otherProject, "{\n  \"o\": 1\n}");

        assertThat(GitManager.checkoutBranch(repo, "feature")).isTrue();

        assertThat(git.getRepository().getBranch()).isEqualTo("feature");
        assertThat(git.stashList().call()).isEmpty();
    }

    /** A Designer's pull stopped on a conflict: config's auto-commit must leave the merge open. */
    @Test
    void configCommitDoesNotCompleteAnOpenMerge() throws Exception {
        RevCommit base = git.log().call().iterator().next();
        write(view, "{\"v\":2}");
        RevCommit theirs = commit("theirs");
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base.getName()).call();
        write(view, "{\"v\":3}");
        RevCommit ours = commit("ours");
        assertThat(git.merge().include(theirs).call().getMergeStatus())
                .isEqualTo(MergeResult.MergeStatus.CONFLICTING);
        write(config, "{\"c\":2}");

        assertThat(DataDirGitManager.commitConfig(git, List.of(config), "auto", "gw", true)).isNull();

        assertThat(git.getRepository().resolve("HEAD")).isEqualTo(ours.getId());
        assertThat(git.getRepository().getRepositoryState()).isEqualTo(RepositoryState.MERGING);
        assertThat(git.status().call().getConflicting()).containsExactly(view);
        assertThat(DataDirGitManager.midOperation(git.getRepository())).contains("merge");

        // Resolved but not yet committed is still the Designer's merge to complete.
        git.add().addFilepattern(view).call();
        assertThat(git.getRepository().getRepositoryState()).isEqualTo(RepositoryState.MERGING_RESOLVED);
        assertThat(DataDirGitManager.commitConfig(git, List.of(config), "auto", "gw", true)).isNull();
        assertThat(git.getRepository().resolve("HEAD")).isEqualTo(ours.getId());
    }

    /** Another project's file Ignition reformatted, which the pull changes, no longer refuses the merge. */
    @Test
    void anotherProjectsReformattedFileDoesNotBlockThePull() throws Exception {
        RevCommit base = git.log().call().iterator().next();
        write(otherProject, "{\"o\":2}");
        RevCommit theirs = commit("theirs");
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base.getName()).call();
        write(otherProject, "{\n  \"o\": 1\n}");

        ProjectScope.restoreReformatted(git, theirs);

        assertThat(git.merge().include(theirs).call().getMergeStatus().isSuccessful()).isTrue();
        assertThat(read(otherProject)).isEqualTo("{\"o\":2}");
    }
}
