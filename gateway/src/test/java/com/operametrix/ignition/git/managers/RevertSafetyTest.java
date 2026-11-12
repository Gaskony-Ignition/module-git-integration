package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A failed or refused revert must never discard uncommitted work, and a file Ignition only
 * reformatted (as it does on every import after a pull) must not block a revert.
 */
class RevertSafetyTest {

    @TempDir
    Path data;

    Git git;
    RepoLocator.Repo repo;
    String view;
    String other;

    void write(String rel, String text) throws Exception {
        Path p = repo.root().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    String read(String rel) throws Exception {
        return Files.readString(repo.root().resolve(rel));
    }

    RevCommit commit(String msg) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(msg).setAuthor("t", "t@x").setCommitter("t", "t@x").call();
    }

    void setUp(RepoLocator.Layout layout) throws Exception {
        repo = RepoLocator.forProject(data, "A", layout);
        view = repo.prefix() + "views/V/view.json";
        other = repo.prefix() + "views/W/view.json";
        Files.createDirectories(repo.root());
        git = Git.init().setDirectory(repo.root().toFile()).call();
        write(view, "{\"v\":1}");
        write(other, "{\"o\":1}");
        commit("init");
    }

    @AfterEach
    void tearDown() {
        if (git != null) {
            git.close();
        }
    }

    void aReformattedFileDoesNotBlockTheRevert(RepoLocator.Layout layout) throws Exception {
        setUp(layout);
        write(view, "{\"v\":2}");
        RevCommit c = commit("two");
        write(view, "{\n  \"v\": 2\n}");
        write(other, "{\"o\":9}");

        assertThat(GitManager.revertCommit(repo, c.getName(), "alice", "")).isTrue();

        assertThat(read(view)).isEqualTo("{\"v\":1}");
        assertThat(read(other)).isEqualTo("{\"o\":9}");
    }

    void aConflictKeepsUncommittedWorkElsewhere(RepoLocator.Layout layout) throws Exception {
        setUp(layout);
        write(view, "{\"v\":2}");
        RevCommit c = commit("two");
        write(view, "{\"v\":3}");
        commit("three");
        write(other, "{\"o\":9}");
        String head = git.getRepository().resolve("HEAD").getName();

        assertThatThrownBy(() -> GitManager.revertCommit(repo, c.getName(), "alice", ""))
                .hasMessageContaining("conflict");

        assertThat(read(view)).isEqualTo("{\"v\":3}");
        assertThat(read(other)).isEqualTo("{\"o\":9}");
        assertThat(git.getRepository().resolve("HEAD").getName()).isEqualTo(head);
        assertThat(git.getRepository().readRevertHead()).isNull();
        assertThat(git.status().call().getConflicting()).isEmpty();
    }

    void uncommittedEditsToTheSameFileAreRefusedAndKept(RepoLocator.Layout layout) throws Exception {
        setUp(layout);
        write(view, "{\"v\":2}");
        RevCommit c = commit("two");
        write(view, "{\"v\":7}");
        write(other, "{\"o\":9}");

        assertThatThrownBy(() -> GitManager.revertCommit(repo, c.getName(), "alice", ""))
                .hasMessageContaining("uncommitted");

        assertThat(read(view)).isEqualTo("{\"v\":7}");
        assertThat(read(other)).isEqualTo("{\"o\":9}");
    }

    @Test
    void reformattedSeparate() throws Exception {
        aReformattedFileDoesNotBlockTheRevert(RepoLocator.Layout.SEPARATE);
    }

    @Test
    void reformattedCombined() throws Exception {
        aReformattedFileDoesNotBlockTheRevert(RepoLocator.Layout.COMBINED);
    }

    @Test
    void conflictSeparate() throws Exception {
        aConflictKeepsUncommittedWorkElsewhere(RepoLocator.Layout.SEPARATE);
    }

    @Test
    void conflictCombined() throws Exception {
        aConflictKeepsUncommittedWorkElsewhere(RepoLocator.Layout.COMBINED);
    }

    @Test
    void sameFileSeparate() throws Exception {
        uncommittedEditsToTheSameFileAreRefusedAndKept(RepoLocator.Layout.SEPARATE);
    }

    @Test
    void sameFileCombined() throws Exception {
        uncommittedEditsToTheSameFileAreRefusedAndKept(RepoLocator.Layout.COMBINED);
    }

    /** A conflicted pull in Combined, then Abort: other projects' and config's edits survive. */
    @Test
    void abortMergeTouchesOnlyWhatTheMergeWrote() throws Exception {
        setUp(RepoLocator.Layout.COMBINED);
        String config = "config/x.json";
        write(config, "{\"c\":1}");
        RevCommit base = commit("config");
        write(view, "{\"v\":2}");
        RevCommit theirs = commit("theirs");
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(base.getName()).call();
        write(view, "{\"v\":3}");
        commit("ours");
        write(other, "{\"o\":9}");
        write(config, "{\"c\":9}");

        assertThat(git.merge().include(theirs).call().getMergeStatus())
                .isEqualTo(org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING);
        ProjectScope.abortMerge(git, null, java.util.Set.of());

        assertThat(read(view)).isEqualTo("{\"v\":3}");
        assertThat(read(other)).isEqualTo("{\"o\":9}");
        assertThat(read(config)).isEqualTo("{\"c\":9}");
        assertThat(git.getRepository().readMergeHeads()).isNull();
        assertThat(git.status().call().getConflicting()).isEmpty();
    }

    /** After an import, a reformatted file the next pull changes no longer refuses the merge. */
    @Test
    void reformattedFilesDoNotBlockTheNextMerge() throws Exception {
        setUp(RepoLocator.Layout.COMBINED);
        RevCommit base = git.log().call().iterator().next();
        write(view, "{\"v\":2}");
        RevCommit theirs = commit("theirs");
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(base.getName()).call();
        write(view, "{\n  \"v\": 1\n}");

        ProjectScope.restoreReformatted(git, theirs);

        assertThat(git.merge().include(theirs).call().getMergeStatus().isSuccessful()).isTrue();
        assertThat(read(view)).isEqualTo("{\"v\":2}");
    }
}
