package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Git Ignore tab's tick logic with the template plus the Combined block. Checks the tree BELOW
 * the root, where 3.9.12/3.10.0 showed everything excluded (the anchored "/*" basename trap).
 */
class IgnoreRulesTest {

    @TempDir
    Path data;

    List<FastIgnoreRule> rules;

    @BeforeEach
    void setUp() throws Exception {
        List<String> lines = new ArrayList<>(IgnoreRules.TEMPLATE);
        lines.add("");
        lines.addAll(LayoutSwitch.BLOCK);
        Path gitignore = data.resolve(".gitignore");
        Files.writeString(gitignore, String.join("\n", lines) + "\n");
        rules = IgnoreRules.parse(gitignore);
    }

    private boolean excluded(String path, boolean dir) {
        return IgnoreRules.decide(rules, path, dir).excluded();
    }

    @Test
    void projectsAreIncludedDownToTheirFiles() {
        assertThat(excluded("projects", true)).isFalse();
        assertThat(excluded("projects/A", true)).isFalse();
        assertThat(excluded("projects/A/project.json", false)).isFalse();
        assertThat(excluded("projects/A/com.inductiveautomation.perspective/views/Main/view.json", false)).isFalse();
        // Template globs written for config must not reach into a project.
        assertThat(excluded("projects/A/ignition/script-python/db", true)).isFalse();
        assertThat(excluded("projects/A/ignition/script-python/db/code.py", false)).isFalse();
        assertThat(excluded("projects/A/ignition/script-python/logs/code.py", false)).isFalse();
        assertThat(IgnoreRules.decide(rules, "projects/A/ignition/script-python/db/code.py", false)
                .fromAncestor()).isFalse();
    }

    @Test
    void perGatewayProjectFilesStayOut() {
        assertThat(excluded("projects/A/ignition/global-props/data.bin", false)).isTrue();
        assertThat(excluded("projects/A/x.idb-wal", false)).isTrue();
        assertThat(excluded("projects/A/.resources", true)).isTrue();
    }

    @Test
    void configAndTheRootBehaveAsBefore() {
        assertThat(excluded("config", true)).isFalse();
        assertThat(excluded("config/resources/core/ignition/security-levels/config.json", false)).isFalse();
        assertThat(excluded("config/resources/core/x/db/a.json", false)).isTrue();
        assertThat(excluded("logs", true)).isTrue();
        assertThat(excluded("modules.json", false)).isTrue();
        assertThat(excluded("var", true)).isTrue();
        assertThat(excluded(".gitignore", false)).isFalse();
    }

    @Test
    void withoutTheBlockProjectsAreExcludedAsInSeparate() throws Exception {
        Path gitignore = data.resolve(".gitignore");
        Files.writeString(gitignore, String.join("\n", IgnoreRules.TEMPLATE) + "\n");
        rules = IgnoreRules.parse(gitignore);
        assertThat(excluded("projects", true)).isTrue();
        assertThat(IgnoreRules.decide(rules, "projects/A/project.json", false).fromAncestor()).isTrue();
    }
}
