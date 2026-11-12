package com.operametrix.ignition.git.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A replace keeps or takes the project properties, and never leaves the project without them. */
class ProjectPropsTest {

    @TempDir
    Path dir;

    static byte[] fixture(String name) throws Exception {
        try (InputStream in = ProjectPropsTest.class.getResourceAsStream("/global-props/" + name)) {
            return in.readAllBytes();
        }
    }

    Path props() {
        return dir.resolve("ignition/global-props/data.bin");
    }

    GitEvent.Builder event() {
        return GitEvent.of(GitEvent.SYNC).project("P");
    }

    @Test
    void readsTheIdentityProviderFromARealFile() throws Exception {
        assertThat(ProjectProps.idp(fixture("idp-default.bin"))).isEqualTo("default");
        assertThat(ProjectProps.idp(fixture("no-idp.bin"))).isEmpty();
        assertThat(ProjectProps.idp(new byte[] {1, 2, 3})).isNull();
    }

    @Test
    void keepingPutsTheGatewaysFileBack() throws Exception {
        byte[] ours = fixture("no-idp.bin");
        Files.createDirectories(props().getParent());
        Files.write(props(), fixture("idp-default.bin"));

        String note = ProjectProps.settle(props(), ours, true, "branch", "P", event());

        assertThat(note).isEqualTo(ProjectProps.KEPT);
        assertThat(Files.readAllBytes(props())).isEqualTo(ours);
    }

    @Test
    void takingLeavesTheIncomingFile() throws Exception {
        byte[] theirs = fixture("idp-default.bin");
        Files.createDirectories(props().getParent());
        Files.write(props(), theirs);

        String note = ProjectProps.settle(props(), fixture("no-idp.bin"), false, "branch", "P", event());

        assertThat(note).isEqualTo("took the project properties from the branch");
        assertThat(Files.readAllBytes(props())).isEqualTo(theirs);
    }

    @Test
    void takingFromABranchWithNoneKeepsTheGatewaysFile() throws Exception {
        byte[] ours = fixture("no-idp.bin");

        String note = ProjectProps.settle(props(), ours, false, "branch", "P", event());

        assertThat(note).isEqualTo(ProjectProps.KEPT);
        assertThat(Files.readAllBytes(props())).isEqualTo(ours);
    }

    @Test
    void warnsOnlyWhenKeepingDropsAnIdentityProvider() throws Exception {
        byte[] none = fixture("no-idp.bin");
        byte[] named = fixture("idp-default.bin");

        assertThat(ProjectProps.idpWarning("Example_A", none, named, "branch"))
                .isEqualTo("Example_A: kept this gateway's project properties, which name no identity "
                        + "provider; the branch's name 'default'. Turn off 'Keep this gateway's project "
                        + "properties' to take them.");
        assertThat(ProjectProps.idpWarning("Example_A", named, none, "branch")).isNull();
        assertThat(ProjectProps.idpWarning("Example_A", named, named, "branch")).isNull();
    }
}
