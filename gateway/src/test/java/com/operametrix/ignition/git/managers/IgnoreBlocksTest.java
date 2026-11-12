package com.operametrix.ignition.git.managers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The module's own .gitignore lines sit between markers; every other line is left where it was. */
class IgnoreBlocksTest {

    private static final String FRESH = IgnoreRules.editManaged("", m -> IgnoreRules.TEMPLATE);

    private static List<String> lines(String text) {
        return List.of(text.split("\n"));
    }

    /** The lines outside both blocks, in order. */
    private static List<String> foreign(String text) {
        IgnoreRules.Parts p = IgnoreRules.split(text);
        List<String> out = new ArrayList<>(p.before());
        out.addAll(p.after());
        return out.stream().filter(l -> !l.isBlank()).toList();
    }

    @Test
    void aFreshFileIsTheManagedBlockThenTheDenyBlock() {
        List<String> l = lines(FRESH);
        assertThat(l.get(0)).isEqualTo(IgnoreRules.MANAGED_START);
        assertThat(l.indexOf(IgnoreRules.MANAGED_END)).isEqualTo(IgnoreRules.TEMPLATE.size() + 1);
        assertThat(l.get(l.size() - 1)).isEqualTo(IgnoreRules.DENY_END);
        assertThat(IgnoreRules.split(FRESH).managed()).isEqualTo(IgnoreRules.TEMPLATE);
    }

    @Test
    void wrappingAnOldFileKeepsForeignLinesInOrderAndMovesTheModuleLinesIn() {
        List<String> old = new ArrayList<>();
        old.add("# mine, at the top");
        old.add("/top-of-mine");
        old.addAll(IgnoreRules.TEMPLATE.subList(0, 5));
        old.add("/in-the-middle");
        old.addAll(IgnoreRules.TEMPLATE.subList(5, IgnoreRules.TEMPLATE.size()));
        old.add("");
        old.add(IgnoreRules.OLD_TAB_MARKER);
        old.add("!/gateway.xml");
        old.add("*.mine");
        old.add("/data/ticked/");
        String oldText = IgnoreRules.withDenyBlock(String.join("\n", old))
                .replace(IgnoreRules.DENY_MARKER, IgnoreRules.OLD_DENY_MARKER)
                .replace(IgnoreRules.DENY_END + "\n", "");

        String wrapped = IgnoreRules.editManaged(oldText, m -> m);
        IgnoreRules.Parts p = IgnoreRules.split(wrapped);

        assertThat(p.before()).filteredOn(l -> !l.isBlank()).containsExactly("# mine, at the top", "/top-of-mine");
        assertThat(foreign(wrapped)).containsExactly("# mine, at the top", "/top-of-mine", "/in-the-middle", "*.mine");
        assertThat(p.managed()).containsSubsequence(IgnoreRules.TEMPLATE.stream().filter(s -> !s.isEmpty()).toList());
        assertThat(p.managed()).contains("!/gateway.xml", "/data/ticked/").doesNotContain("*.mine");
        assertThat(wrapped).doesNotContain(IgnoreRules.OLD_DENY_MARKER).endsWith(IgnoreRules.DENY_END + "\n");
        // Idempotent: wrapping again, and the startup pass with nothing to edit, change nothing.
        assertThat(IgnoreRules.editManaged(wrapped, m -> m)).isEqualTo(wrapped);
    }

    @Test
    void aFileWithNoModuleLinesGetsAnEmptyBlockAtItsEnd() {
        String wrapped = IgnoreRules.editManaged("# hand written\n*.mine\n", m -> m);
        assertThat(lines(wrapped)).startsWith("# hand written", "*.mine", "", IgnoreRules.MANAGED_START, IgnoreRules.MANAGED_END);
        assertThat(IgnoreRules.editManaged(wrapped, m -> m)).isEqualTo(wrapped);
    }

    @Test
    void everyModuleWriteLeavesForeignLinesAlone() {
        String mine = FRESH.replace(IgnoreRules.MANAGED_START, "# mine\n*.log\n\n" + IgnoreRules.MANAGED_START)
                .replace(IgnoreRules.MANAGED_END + "\n", IgnoreRules.MANAGED_END + "\n\n!/mine/\n**/db/*\n");
        List<String> expected = List.of("# mine", "*.log", "!/mine/", "**/db/*");
        assertThat(foreign(mine)).isEqualTo(expected);

        String combined = IgnoreRules.editManaged(mine, LayoutSwitch::withCombinedRules);
        // The template's **/db/* is scoped inside the block; the identical line of mine is not.
        assertThat(IgnoreRules.split(combined).managed()).contains("/config/**/db/*").doesNotContain("**/db/*");
        assertThat(foreign(combined)).isEqualTo(expected);
        String policy = IgnoreRules.editManaged(mine.replace(String.join("\n", IgnoreRules.RECORDS_RULES),
                IgnoreRules.OLD_RECORDS_RULE), IgnoreRules::versionPolicy);
        assertThat(foreign(policy)).isEqualTo(expected);
        assertThat(foreign(IgnoreRules.withDenyBlock(mine))).isEqualTo(expected);
    }

    @Test
    void theDenyBlockEndsTheFileAfterANegationAddedBelowIt() {
        String edited = FRESH + "!*.pem\n";
        String after = IgnoreRules.editManaged(edited, m -> m);
        List<String> l = lines(after);
        assertThat(l.get(l.size() - 1)).isEqualTo(IgnoreRules.DENY_END);
        assertThat(l.indexOf("!*.pem")).isLessThan(l.indexOf(IgnoreRules.DENY_MARKER));
        assertThat(after.indexOf(IgnoreRules.DENY_MARKER)).isEqualTo(after.lastIndexOf(IgnoreRules.DENY_MARKER));
    }

    @Test
    void theCombinedSwitchBothWaysRoundTripsAndKeepsForeignLines() {
        String mine = FRESH.replace(IgnoreRules.MANAGED_END + "\n", IgnoreRules.MANAGED_END + "\n\n*.log\n");
        String combined = IgnoreRules.editManaged(mine, LayoutSwitch::withCombinedRules);
        assertThat(IgnoreRules.split(combined).managed()).contains(LayoutSwitch.BLOCK_START, LayoutSwitch.DATA_BIN);
        assertThat(foreign(combined)).containsExactly("*.log");
        assertThat(IgnoreRules.editManaged(combined, LayoutSwitch::withoutCombinedRules)).isEqualTo(mine);
        assertThat(IgnoreRules.editManaged(mine, LayoutSwitch::withoutCombinedRules)).isNull();
    }
}
