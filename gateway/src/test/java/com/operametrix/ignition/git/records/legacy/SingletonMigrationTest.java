package com.operametrix.ignition.git.records.legacy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SingletonMigrationTest {

    @TempDir
    Path resources;

    private Path write(String collection, String rel, String body) throws Exception {
        Path f = resources.resolve(collection).resolve(SingletonMigration.MODULE_ID).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
        return f;
    }

    private Path type(String collection, String type) {
        return resources.resolve(collection).resolve(SingletonMigration.MODULE_ID).resolve(type);
    }

    @Test
    void movesEachNamedRecordUpToItsTypeInCoreAndEveryMode() throws Exception {
        write("core", "git-policy/policy/config.json", "{\"push\":true}");
        write("core", "git-policy/policy/resource.json", "{\"scope\":\"A\"}");
        write("Staging", "git-policy/policy/config.json", "{\"push\":false}");
        write("Staging", "git-policy/policy/resource.json", "{\"scope\":\"A\"}");
        write("core", "git-config-remote/1/config.json", "{\"uri\":\"https://h/r.git\"}");
        write("Prod", "git-runner/runner/config.json", "{\"enabled\":true}");
        // Named records of other types are not singletons and stay where they are.
        write("core", "git-sync/3/config.json", "{}");

        SingletonMigration.Result r = SingletonMigration.migrate(resources);

        assertThat(r.moved()).containsExactlyInAnyOrder(
                "core/com.operametrix.ignition.git/git-policy/policy",
                "Staging/com.operametrix.ignition.git/git-policy/policy",
                "core/com.operametrix.ignition.git/git-config-remote/1",
                "Prod/com.operametrix.ignition.git/git-runner/runner");
        assertThat(r.kept()).isEmpty();
        assertThat(Files.readString(type("core", "git-policy").resolve("config.json"))).isEqualTo("{\"push\":true}");
        assertThat(type("core", "git-policy").resolve("resource.json")).exists();
        assertThat(Files.readString(type("Staging", "git-policy").resolve("config.json")))
                .isEqualTo("{\"push\":false}");
        assertThat(type("core", "git-policy").resolve("policy")).doesNotExist();
        assertThat(type("Staging", "git-policy").resolve("policy")).doesNotExist();
        assertThat(type("core", "git-config-remote").resolve("config.json")).exists();
        assertThat(type("Prod", "git-runner").resolve("config.json")).exists();
        assertThat(type("core", "git-sync").resolve("3/config.json")).exists();
    }

    @Test
    void runningAgainChangesNothing() throws Exception {
        write("core", "git-policy/policy/config.json", "{\"push\":true}");
        assertThat(SingletonMigration.migrate(resources).changed()).isTrue();

        SingletonMigration.Result again = SingletonMigration.migrate(resources);

        assertThat(again.changed()).isFalse();
        assertThat(again.kept()).isEmpty();
        assertThat(Files.readString(type("core", "git-policy").resolve("config.json"))).isEqualTo("{\"push\":true}");
    }

    @Test
    void aSingletonAlreadyThereIsKeptAndTheOldFolderLeft() throws Exception {
        write("core", "git-policy/config.json", "{\"push\":false}");
        write("core", "git-policy/policy/config.json", "{\"push\":true}");

        SingletonMigration.Result r = SingletonMigration.migrate(resources);

        assertThat(r.moved()).isEmpty();
        assertThat(r.kept()).containsExactly("core/com.operametrix.ignition.git/git-policy/policy");
        assertThat(Files.readString(type("core", "git-policy").resolve("config.json"))).isEqualTo("{\"push\":false}");
        assertThat(type("core", "git-policy").resolve("policy/config.json")).exists();
    }

    @Test
    void aGatewayWithNoRecordsOrNoResourcesFolderIsLeftAlone() throws Exception {
        assertThat(SingletonMigration.migrate(resources.resolve("missing")).changed()).isFalse();
        Files.createDirectories(resources.resolve("core/ignition/system-properties"));
        assertThat(SingletonMigration.migrate(resources).changed()).isFalse();
    }
}
