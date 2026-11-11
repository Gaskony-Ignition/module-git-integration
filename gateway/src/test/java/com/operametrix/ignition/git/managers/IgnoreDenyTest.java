package com.operametrix.ignition.git.managers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Keys, certificates and local config stay out of the data-directory repository whatever the edit. */
class IgnoreDenyTest {

    @TempDir
    Path data;

    private boolean excluded(String text, String path, boolean dir) throws Exception {
        Path gitignore = data.resolve(".gitignore");
        Files.writeString(gitignore, IgnoreRules.withDenyBlock(text));
        return IgnoreRules.decide(IgnoreRules.parse(gitignore), path, dir).excluded();
    }

    @Test
    void theBlockEndsTheFileOnceHoweverOftenItIsApplied() {
        String once = IgnoreRules.withDenyBlock("a\n" + IgnoreRules.DENY_MARKER + "\n*.jks\n!/x\n");
        assertThat(IgnoreRules.withDenyBlock(once)).isEqualTo(once);
        assertThat(once.indexOf(IgnoreRules.DENY_MARKER)).isEqualTo(once.lastIndexOf(IgnoreRules.DENY_MARKER));
        assertThat(once).startsWith("a\n!/x\n\n" + IgnoreRules.DENY_MARKER);
        assertThat(once).endsWith("*.keystore\n");
    }

    @Test
    void aNegationCannotReincludeADeniedPath() throws Exception {
        String reinclude = "!/config/local/\n!/config/local/**\n!*.pfx\n!/ignition.conf\n!**/keystore/";
        assertThat(excluded(reinclude, "config/local/ignition/webserver/keystore", true)).isTrue();
        assertThat(excluded(reinclude, "config/resources/local/x/keystore.pfx", false)).isTrue();
        assertThat(excluded(reinclude, "ignition.conf", false)).isTrue();
        assertThat(excluded(reinclude, "config/resources/core/ignition/x/backup.pem", false)).isTrue();
        assertThat(excluded(reinclude, "config/resources/core/ignition/x/config.json", false)).isFalse();
    }

    @Test
    void deniedCoversFoldersAndFileTypes() {
        assertThat(IgnoreRules.denied("config/local/ignition/gateway-network/keystore/metro-keystore.pfx")).isTrue();
        assertThat(IgnoreRules.denied("certificates/supplemental/ca.crt")).isTrue();
        assertThat(IgnoreRules.denied("projects/A/keys/id.key")).isTrue();
        assertThat(IgnoreRules.denied("config/resources/core/ignition/tag-provider/default/config.json")).isFalse();
    }
}
