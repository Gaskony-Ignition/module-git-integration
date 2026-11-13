package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Join remote history against a branch another gateway started (4.5.3, live-tested on a rig): what
 * the confirmation lists, and that the join's add never puts this gateway's unversioned files on
 * the branch in place of the other gateway's.
 */
class JoinHistoryTest {

    @TempDir
    Path tmp;

    private static final String USERS = "config/resources/core/ignition/user-source/default/users.json";

    private List<FastIgnoreRule> rules(Path dir) throws Exception {
        Path gitignore = dir.resolve(".gitignore");
        Files.writeString(gitignore, IgnoreRules.withDenyBlock(String.join("\n", IgnoreRules.TEMPLATE)));
        return IgnoreRules.parse(gitignore);
    }

    private static void write(Path dir, String rel, String text) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    @Test
    void filesThisGatewayDoesNotVersionAreNamed() throws Exception {
        List<FastIgnoreRule> rules = rules(tmp);
        assertThat(DataDirGitManager.unversioned(rules, "ignition.conf")).isTrue();
        assertThat(DataDirGitManager.unversioned(rules, USERS)).isTrue();
        assertThat(DataDirGitManager.unversioned(rules, "db/history/x.idb")).isTrue();
        assertThat(DataDirGitManager.unversioned(rules, "config/resources/core/ignition/holiday/Day/config.json"))
                .isFalse();
    }

    /** The join: soft reset onto the unrelated tip, then add as the gateway does. */
    @Test
    void joinDropsTheOtherGatewaysUnversionedFilesWithoutAddingThisOnes() throws Exception {
        Path other = tmp.resolve("other");
        ObjectId tip;
        try (Git o = Git.init().setDirectory(other.toFile()).setInitialBranch("main").call()) {
            write(other, "ignition.conf", "# other gateway");
            write(other, USERS, "{\"users\":\"OTHER\"}");
            write(other, "config/resources/core/ignition/holiday/Other/config.json", "{}");
            o.add().addFilepattern(".").call();
            tip = o.commit().setMessage("other root").setAuthor("o", "o@example.com")
                    .setCommitter("o", "o@example.com").call();
        }
        Path here = tmp.resolve("here");
        Files.createDirectories(here);
        rules(here);
        write(here, "ignition.conf", "# this gateway");
        write(here, USERS, "{\"users\":\"THIS\"}");
        write(here, "config/resources/core/ignition/holiday/Mine/config.json", "{}");
        try (Git g = Git.init().setDirectory(here.toFile()).setInitialBranch("main").call()) {
            g.add().addFilepattern(".").call();
            g.commit().setMessage("this root").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
            g.fetch().setRemote(other.toUri().toString())
                    .setRefSpecs("+refs/heads/main:refs/remotes/origin/main").call();

            g.reset().setMode(ResetCommand.ResetType.SOFT).setRef(tip.name()).call();
            g.add().addFilepattern(".").call();
            g.add().setUpdate(true).addFilepattern(".").call();
            g.commit().setMessage("join").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();

            var repo = g.getRepository();
            assertThat(repo.resolve("HEAD~1")).isEqualTo(tip);
            assertThat(repo.resolve("HEAD:ignition.conf")).isNull();
            assertThat(repo.resolve("HEAD:" + USERS)).isNull();
            assertThat(repo.resolve("HEAD:config/resources/core/ignition/holiday/Mine/config.json")).isNotNull();
            assertThat(repo.resolve("HEAD:config/resources/core/ignition/holiday/Other/config.json")).isNull();
            assertThat(Files.readString(here.resolve("ignition.conf"))).isEqualTo("# this gateway");
        }
    }
}
