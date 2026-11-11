package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A project as a prefix of the Combined gateway repository. */
class ProjectScopeTest {

    @TempDir
    Path data;

    Git git;
    RepoLocator.Repo a;

    static void write(Path root, String rel, String text) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @BeforeEach
    void setUp() throws Exception {
        List<String> ignore = LayoutSwitch.withCombinedRules(List.of("**/db/*", "**/logs", "*.log", "**/var", "/*",
                "!/config/", "!/.gitignore"));
        write(data, ".gitignore", String.join("\n", ignore) + "\n");
        write(data, "config/a.json", "{\"a\":1}");
        write(data, "modules.json", "{}");
        write(data, "projects/A/project.json", "{\"title\":\"A\"}");
        write(data, "projects/A/ignition/script-python/db/code.py", "x = 1");
        write(data, "projects/A/ignition/global-props/data.bin", "per-gateway");
        write(data, "projects/A/com.inductiveautomation.perspective/views/V/view.json", "{\"v\":1}");
        write(data, "projects/B/project.json", "{\"title\":\"B\"}");
        git = Git.init().setDirectory(data.toFile()).call();
        git.add().addFilepattern(".").call();
        git.commit().setMessage("init").setAuthor("t", "t@x").setCommitter("t", "t@x").call();
        a = RepoLocator.forProject(data, "A", RepoLocator.Layout.COMBINED);
    }

    @AfterEach
    void tearDown() {
        git.close();
    }

    private List<String> filesIn(RevCommit c) throws Exception {
        return ProjectScope.changedFiles(git.getRepository(), c);
    }

    @Test
    void ignoreBlockVersionsEveryProjectFileButThePerGatewayOnes() throws Exception {
        Status s = git.status().call();
        assertThat(s.isClean()).isTrue();
        RevCommit head = git.log().setMaxCount(1).call().iterator().next();
        assertThat(filesIn(head)).contains("projects/A/ignition/script-python/db/code.py", "config/a.json")
                .doesNotContain("projects/A/ignition/global-props/data.bin", "modules.json");
    }

    @Test
    void statusIsLimitedToTheProjectAndStripped() throws Exception {
        write(data, "config/a.json", "{\"a\":2}");
        write(data, "projects/A/project.json", "{\"title\":\"A2\"}");
        write(data, "projects/A/ignition/new/resource.json", "{}");
        write(data, "projects/B/project.json", "{\"title\":\"B2\"}");
        Status s = ProjectScope.status(git, a);
        assertThat(a.inProject(s.getModified())).containsExactly("project.json");
        assertThat(a.inProject(s.getUntracked())).containsExactly("ignition/new/resource.json");
    }

    @Test
    void commitHoldsOnlyTheProjectsPathsEvenWithConfigStaged() throws Exception {
        write(data, "config/a.json", "{\"a\":3}");
        git.add().addFilepattern("config/a.json").call();
        write(data, "projects/A/com.inductiveautomation.perspective/views/V/view.json", "{\"v\":2}");
        write(data, "projects/A/com.inductiveautomation.perspective/views/W/view.json", "{\"w\":1}");
        Files.delete(data.resolve("projects/A/ignition/script-python/db/code.py"));
        write(data, "projects/B/project.json", "{\"title\":\"B3\"}");

        RevCommit c = ProjectScope.commit(git, a, List.of(
                "com.inductiveautomation.perspective/views/V",
                "com.inductiveautomation.perspective/views/W",
                "ignition/script-python/db"), "views", false, "eng", "eng@x");

        assertThat(filesIn(c)).containsExactlyInAnyOrder(
                "projects/A/com.inductiveautomation.perspective/views/V/view.json",
                "projects/A/com.inductiveautomation.perspective/views/W/view.json",
                "projects/A/ignition/script-python/db/code.py");
        Status after = git.status().call();
        assertThat(after.getChanged()).containsExactly("config/a.json");
        assertThat(after.getModified()).containsExactly("projects/B/project.json");
        assertThat(ProjectScope.status(git, a).isClean()).isTrue();
    }

    @Test
    void refusesAPathOutsideTheProject() {
        assertThatThrownBy(() -> ProjectScope.commit(git, a, List.of("/config/a.json"), "m", false, "e", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amendOfACommitThatTouchedConfigIsRefused() throws Exception {
        write(data, "config/a.json", "{\"a\":4}");
        git.add().addFilepattern("config/a.json").call();
        git.commit().setMessage("config").setAuthor("gw", "").call();
        write(data, "projects/A/project.json", "{\"title\":\"A4\"}");
        assertThatThrownBy(() -> ProjectScope.commit(git, a, List.of("project.json"), "m", true, "e", ""))
                .hasMessageContaining("config/a.json");
    }

    @Test
    void historyIsTheCommitsThatTouchedTheProject() throws Exception {
        write(data, "config/a.json", "{\"a\":5}");
        git.add().addFilepattern("config").call();
        git.commit().setMessage("config only").setAuthor("gw", "").call();
        write(data, "projects/A/project.json", "{\"title\":\"A5\"}");
        ProjectScope.commit(git, a, List.of("project.json"), "project A", false, "e", "");
        LogCommand log = git.log();
        ProjectScope.limitLog(log, a);
        List<String> messages = new ArrayList<>();
        log.call().forEach(c -> messages.add(c.getShortMessage()));
        assertThat(messages).containsExactly("project A", "init");
    }

    @Test
    void revertOfAProjectCommitLeavesDirtyConfigAlone() throws Exception {
        write(data, "projects/A/project.json", "{\"title\":\"A6\"}");
        RevCommit c = ProjectScope.commit(git, a, List.of("project.json"), "A6", false, "e", "");
        write(data, "config/a.json", "{\"a\":6}");

        ProjectScope.revertScoped(git, a, c.getName(), "e", "");

        assertThat(Files.readString(data.resolve("projects/A/project.json"))).isEqualTo("{\"title\":\"A\"}");
        assertThat(Files.readString(data.resolve("config/a.json"))).isEqualTo("{\"a\":6}");
        assertThat(git.getRepository().resolve(Constants.HEAD)).isNotEqualTo(c.getId());
    }

    @Test
    void combinedRevertIsAuthoredByTheDesignerUserAndTakesNothingElse() throws Exception {
        write(data, "projects/A/project.json", "{\"title\":\"A8\"}");
        RevCommit c = ProjectScope.commit(git, a, List.of("project.json"), "A8", false, "e", "");
        write(data, "projects/B/project.json", "{\"title\":\"B dirty\"}");
        write(data, "config/staged.txt", "s");
        git.add().addFilepattern("config/staged.txt").call();
        assertThat(git.status().call().getAdded()).containsExactly("config/staged.txt");

        ProjectScope.revertScoped(git, a, c.getName(), "alice", "alice@x.com");

        RevCommit head = git.log().setMaxCount(1).call().iterator().next();
        assertThat(head.getAuthorIdent().getName()).isEqualTo("alice");
        assertThat(head.getAuthorIdent().getEmailAddress()).isEqualTo("alice@x.com");
        assertThat(head.getCommitterIdent().getName()).isEqualTo("alice");
        assertThat(head.getShortMessage()).startsWith("Revert");
        assertThat(head.getParent(0).getName()).isEqualTo(c.getName());
        assertThat(filesIn(head)).containsExactly("projects/A/project.json");
        assertThat(Files.readString(data.resolve("projects/B/project.json"))).isEqualTo("{\"title\":\"B dirty\"}");
        assertThat(Files.readString(data.resolve("config/staged.txt"))).isEqualTo("s");
    }

    @Test
    void separateRevertIsAuthoredByTheDesignerUser() throws Exception {
        write(data, "projects/A/project.json", "{\"title\":\"A9\"}");
        git.add().addFilepattern(".").call();
        RevCommit c = git.commit().setMessage("A9").setAuthor("t", "").call();

        git.revert().include(c).call();
        ProjectScope.reauthorHead(git, "bob", "bob@x.com");

        RevCommit head = git.log().setMaxCount(1).call().iterator().next();
        assertThat(head.getAuthorIdent().getName()).isEqualTo("bob");
        assertThat(head.getCommitterIdent().getEmailAddress()).isEqualTo("bob@x.com");
        assertThat(head.getShortMessage()).startsWith("Revert");
        assertThat(Files.readString(data.resolve("projects/A/project.json"))).isEqualTo("{\"title\":\"A\"}");
        assertThat(git.status().call().isClean()).isTrue();
    }

    @Test
    void revertOfACommitThatAlsoChangedConfigIsRefused() throws Exception {
        write(data, "projects/A/project.json", "{\"title\":\"A7\"}");
        write(data, "config/a.json", "{\"a\":7}");
        git.add().addFilepattern(".").call();
        RevCommit both = git.commit().setMessage("both").setAuthor("t", "").call();
        assertThatThrownBy(() -> ProjectScope.revertScoped(git, a, both.getName(), "e", ""))
                .hasMessageContaining("outside this project");
        assertThat(git.getRepository().resolve(Constants.HEAD)).isEqualTo(both.getId());
    }

    @Test
    void aProjectWithItsOwnGitFolderIsRefusedNotHidden() throws Exception {
        Files.createDirectories(data.resolve("projects/A/.git"));
        assertThatThrownBy(() -> ProjectScope.commit(git, a, List.of("project.json"), "m", false, "e", ""))
                .hasMessageContaining("own .git");
    }

    @Test
    void replaceCleanKeepsAProjectThatIsOnlyOnThisGateway() throws Exception {
        write(data, "projects/Local/project.json", "{\"title\":\"Local\"}");
        write(data, "projects/Local/ignition/global-props/data.bin", "props");
        write(data, "config/stray.json", "{}");
        write(data, "projects/A/stray.txt", "x");

        List<String> kept = ProjectScope.removeUntracked(git.getRepository(), true);

        assertThat(kept).containsExactly("projects/Local");
        assertThat(Files.exists(data.resolve("projects/Local/project.json"))).isTrue();
        assertThat(Files.exists(data.resolve("config/stray.json"))).isFalse();
        assertThat(Files.exists(data.resolve("projects/A/stray.txt"))).isFalse();
        // Ignored per-gateway data is never touched.
        assertThat(Files.exists(data.resolve("projects/A/ignition/global-props/data.bin"))).isTrue();
    }

    @Test
    void separateReplaceStillRemovesAnUntrackedFolder() throws Exception {
        write(data, "projects/Local/project.json", "{}");
        assertThat(ProjectScope.removeUntracked(git.getRepository(), false)).isEmpty();
        assertThat(Files.exists(data.resolve("projects/Local/project.json"))).isFalse();
    }

    @Test
    void pullIsBlockedOnlyByDirtyFilesOutsideTheProjectThatItWouldChange() throws Exception {
        org.eclipse.jgit.lib.ObjectId base = git.getRepository().resolve("HEAD");
        write(data, "config/a.json", "{\"a\":9}");
        write(data, "projects/B/project.json", "{\"title\":\"B9\"}");
        write(data, "projects/A/project.json", "{\"title\":\"A9\"}");
        git.add().addFilepattern(".").call();
        RevCommit incoming = git.commit().setMessage("remote").setAuthor("r", "").call();
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(base.getName()).call();

        write(data, "projects/B/project.json", "{\"title\":\"B local\"}");
        write(data, "projects/A/project.json", "{\"title\":\"A local\"}");
        write(data, "modules.json", "{\"ignored\":1}");
        java.util.Set<String> dirty = new java.util.TreeSet<>(git.status().call().getUncommittedChanges());

        assertThat(ProjectScope.blockingChanges(git.getRepository(), a, incoming, dirty))
                .containsExactly("projects/B/project.json");
    }

    @Test
    void pushTargetNeverPutsAnotherBranchOntoTheConfiguredOne() throws Exception {
        org.eclipse.jgit.lib.Repository r = git.getRepository();
        String home = r.getBranch();
        // The only branch, no upstream: the configured branch, as the page has always pushed.
        assertThat(ProjectScope.pushTarget(r, "origin", "main")).isEqualTo("main");
        ProjectScope.setUpstream(r, "origin", "main");
        git.checkout().setCreateBranch(true).setName("feature").call();
        assertThat(ProjectScope.pushTarget(r, "origin", "main")).isEqualTo("feature");
        git.checkout().setName(home).call();
        assertThat(ProjectScope.pushTarget(r, "origin", "main")).isEqualTo("main");
    }
}
