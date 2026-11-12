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
        // The pre-4.5.0 block, which had no end marker, is replaced the same way.
        assertThat(IgnoreRules.withDenyBlock("a\n" + IgnoreRules.OLD_DENY_MARKER + "\n*.jks\n!/x\n")).isEqualTo(once);
        assertThat(IgnoreRules.withDenyBlock(once)).isEqualTo(once);
        assertThat(once.indexOf(IgnoreRules.DENY_MARKER)).isEqualTo(once.lastIndexOf(IgnoreRules.DENY_MARKER));
        assertThat(once).startsWith("a\n!/x\n\n" + IgnoreRules.DENY_MARKER);
        assertThat(once).endsWith("*.keystore\n" + IgnoreRules.DENY_END + "\n");
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

    @Test
    void internalUsersPasswordHashesAreNeverVersionedInAnyCollection() throws Exception {
        assertThat(IgnoreRules.denied("config/resources/core/ignition/user-source/default/users.json")).isTrue();
        assertThat(IgnoreRules.denied("config/resources/Prod/ignition/user-source/plant/users.json")).isTrue();
        assertThat(IgnoreRules.denied("config/resources/core/ignition/user-source/default/config.json")).isFalse();
        String reinclude = "!**/ignition/user-source/*/users.json\n!/config/resources/core/ignition/user-source/default/users.json";
        assertThat(excluded(reinclude, "config/resources/core/ignition/user-source/default/users.json", false)).isTrue();
    }
}
