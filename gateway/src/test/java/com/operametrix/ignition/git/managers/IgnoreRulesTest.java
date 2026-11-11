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
 * The Git ignore tab's tick logic with the template plus the Combined block. Checks the tree BELOW
 * the root, where 3.9.12/3.10.0 showed everything excluded (the anchored "/*" basename trap).
 */
class IgnoreRulesTest {

    @TempDir
    Path data;

    List<FastIgnoreRule> rules;

    @BeforeEach
    void setUp() throws Exception {
        List<String> lines = LayoutSwitch.withCombinedRules(IgnoreRules.TEMPLATE);
        Path gitignore = data.resolve(".gitignore");
        Files.writeString(gitignore, IgnoreRules.withDenyBlock(String.join("\n", lines)));
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

    @Test
    void onlyTheGitPolicyOfThisModuleIsVersioned() {
        String base = "config/resources/core/com.operametrix.ignition.git";
        assertThat(excluded(base, true)).isFalse();
        assertThat(excluded(base + "/git-policy", true)).isFalse();
        assertThat(excluded(base + "/git-policy/policy/config.json", false)).isFalse();
        assertThat(excluded("config/resources/Prod/com.operametrix.ignition.git/git-policy/policy/config.json", false))
                .isFalse();
        assertThat(excluded(base + "/git-config-remote", true)).isTrue();
        assertThat(excluded(base + "/git-user-https-credential/1/config.json", false)).isTrue();
        assertThat(excluded("config/resources/Prod/com.operametrix.ignition.git/git-runner/runner/config.json", false))
                .isTrue();
        // Other modules' records are untouched by the carve-out.
        assertThat(excluded("config/resources/core/ignition/git-policy/x.json", false)).isFalse();
    }

    @Test
    void theMigrationRewritesOnlyTheManagedLineAndIsIdempotent() {
        List<String> old = List.of("# head", "**/db/*", "**/com.operametrix.ignition.git/", "", "/*",
                "# --- managed by the Versioning page below this line ---", "/mine.txt");
        List<String> once = IgnoreRules.versionPolicy(old);
        assertThat(once).containsExactly("# head", "**/db/*", "**/com.operametrix.ignition.git/*",
                "!**/com.operametrix.ignition.git/git-policy/", "", "/*",
                "# --- managed by the Versioning page below this line ---", "/mine.txt");
        assertThat(IgnoreRules.versionPolicy(once)).isNull();
        // A user who already took the line out keeps their file as it is.
        assertThat(IgnoreRules.versionPolicy(List.of("**/db/*", "/mine.txt"))).isNull();
        // A new repository's template is already in the new form.
        assertThat(IgnoreRules.versionPolicy(IgnoreRules.TEMPLATE)).isNull();
    }

    @Test
    void aMigratedFileDecidesTheTreeLikeTheTemplate() throws Exception {
        List<String> old = new ArrayList<>(IgnoreRules.TEMPLATE);
        int at = old.indexOf(IgnoreRules.RECORDS_RULES.get(0));
        old.remove(at + 1);
        old.set(at, IgnoreRules.OLD_RECORDS_RULE);
        Path gitignore = data.resolve(".gitignore");
        Files.writeString(gitignore, String.join("\n", IgnoreRules.versionPolicy(old)) + "\n");
        rules = IgnoreRules.parse(gitignore);
        assertThat(excluded("config/resources/core/com.operametrix.ignition.git/git-policy/policy/config.json", false))
                .isFalse();
        assertThat(excluded("config/resources/core/com.operametrix.ignition.git/git-sync/1/config.json", false))
                .isTrue();
        assertThat(excluded("config/resources/core/ignition/security-levels/config.json", false)).isFalse();
    }

    @Test
    void dataBinIsAnOrdinaryLineAndRulesUnderProjectsWork() throws Exception {
        List<String> lines = new ArrayList<>(LayoutSwitch.withCombinedRules(IgnoreRules.TEMPLATE));
        assertThat(lines.indexOf(LayoutSwitch.DATA_BIN)).isGreaterThan(lines.indexOf(LayoutSwitch.BLOCK_END));
        // An admin's own rule under projects/ now applies; the old catch-all re-include overrode it.
        lines.add("/projects/A/tmp/");
        Path gitignore = data.resolve(".gitignore");
        Files.writeString(gitignore, IgnoreRules.withDenyBlock(String.join("\n", lines)));
        rules = IgnoreRules.parse(gitignore);
        assertThat(excluded("projects/A/tmp/x.json", false)).isTrue();
        assertThat(excluded("projects/A/ignition/script-python/db/code.py", false)).isFalse();
        assertThat(IgnoreRules.isOwnLine(IgnoreRules.decide(rules, "projects/A/ignition/global-props/data.bin", false)
                .rule(), "projects/A/ignition/global-props/data.bin", false)).isFalse();
    }

    @Test
    void theOldCombinedBlockIsMigratedOnceToTheSameDecisions() throws Exception {
        List<String> old = new ArrayList<>(IgnoreRules.TEMPLATE);
        old.add("");
        old.addAll(List.of(LayoutSwitch.BLOCK_START, "!/projects/", "!/projects/**", LayoutSwitch.DATA_BIN,
                "/projects/**/*-wal", "/projects/**/*-shm", "/projects/**/.resources/", LayoutSwitch.BLOCK_END,
                "/mine.txt"));
        List<String> once = LayoutSwitch.migrateCombinedRules(old);
        assertThat(once).doesNotContain("!/projects/**", "**/db/*").contains("/config/**/db/*", "/mine.txt");
        assertThat(once.indexOf(LayoutSwitch.DATA_BIN)).isEqualTo(once.indexOf(LayoutSwitch.BLOCK_END) + 1);
        assertThat(LayoutSwitch.migrateCombinedRules(once)).isNull();
        assertThat(LayoutSwitch.migrateCombinedRules(IgnoreRules.TEMPLATE)).isNull();
        Path gitignore = data.resolve(".gitignore");
        Files.writeString(gitignore, IgnoreRules.withDenyBlock(String.join("\n", once)));
        rules = IgnoreRules.parse(gitignore);
        assertThat(excluded("projects/A/ignition/script-python/db/code.py", false)).isFalse();
        assertThat(excluded("projects/A/ignition/global-props/data.bin", false)).isTrue();
        assertThat(excluded("config/resources/core/x/db/a.json", false)).isTrue();
        assertThat(excluded("config/x/app.log", false)).isTrue();
    }

    @Test
    void theSwitchBackUnscopesTheGlobs() {
        assertThat(IgnoreRules.unscope(IgnoreRules.scopeToConfig(IgnoreRules.TEMPLATE))).isEqualTo(IgnoreRules.TEMPLATE);
    }
}
