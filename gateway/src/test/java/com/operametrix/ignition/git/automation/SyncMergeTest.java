package com.operametrix.ignition.git.automation;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A sync's conflicted merge is undone without taking an edit made since its clean-tree check. */
class SyncMergeTest {

    @TempDir
    Path dir;

    Git git;

    @AfterEach
    void tearDown() {
        if (git != null) {
            git.close();
        }
    }

    void write(String rel, String text) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    RevCommit commit(String msg) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(msg).setAuthor("t", "t@x").setCommitter("t", "t@x").call();
    }

    @Test
    void aConflictIsUndoneButAnEditElsewhereSurvives() throws Exception {
        git = Git.init().setDirectory(dir.toFile()).call();
        write("views/V/view.json", "{\"v\":1}");
        write("views/W/view.json", "{\"w\":1}");
        RevCommit base = commit("init");
        write("views/V/view.json", "{\"v\":2}");
        RevCommit theirs = commit("theirs");
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base.getName()).call();
        write("views/V/view.json", "{\"v\":3}");
        RevCommit ours = commit("ours");
        // A Designer save landing after the sync checked the tree was clean.
        write("views/W/view.json", "{\"w\":9}");

        MergeResult merge = SyncScheduler.mergeOrUndo(git, theirs, "Merge");

        assertThat(merge.getMergeStatus()).isEqualTo(MergeResult.MergeStatus.CONFLICTING);
        assertThat(Files.readString(dir.resolve("views/V/view.json"))).isEqualTo("{\"v\":3}");
        assertThat(Files.readString(dir.resolve("views/W/view.json"))).isEqualTo("{\"w\":9}");
        assertThat(git.getRepository().resolve("HEAD")).isEqualTo(ours.getId());
        assertThat(git.getRepository().readMergeHeads()).isNull();
        assertThat(git.status().call().getConflicting()).isEmpty();
    }
}
