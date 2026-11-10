package com.operametrix.ignition.git.managers;

import com.operametrix.ignition.git.records.GitPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The policy decisions, the host rules and the commit trailer, with no gateway. */
class PolicyGateTest {

    private static final GitPolicy READ_ONLY =
            new GitPolicy(false, false, false, false, List.of("off", "pull"), List.of("github.com"), false);

    @Test
    void theDefaultAllowsEverythingItAllowedBefore() {
        for (PolicyGate.Action a : PolicyGate.Action.values()) {
            assertThat(PolicyGate.decide(GitPolicy.DEFAULT, "Prod", a)).as(a.name()).isNull();
        }
        for (String d : List.of("off", "sync-pull", "sync-replace", "runner-release", "runner-repo", "replace", "pull", "runner")) {
            assertThat(PolicyGate.decideDelivery(GitPolicy.DEFAULT, "Prod", d)).as(d).isNull();
        }
        assertThat(PolicyGate.decideHost(GitPolicy.DEFAULT, "Prod", "https://anywhere.example/x.git")).isNull();
    }

    @Test
    void aMissingFieldReadsAsTheDefaultNotOff() {
        GitPolicy sparse = new GitPolicy(false, null, null, null, null, null, null);
        assertThat(PolicyGate.decide(sparse, null, PolicyGate.Action.DESIGNER_COMMIT)).isNull();
        assertThat(PolicyGate.decideDelivery(sparse, null, "runner-repo")).isNull();
        assertThat(PolicyGate.decide(sparse, null, PolicyGate.Action.PUSH)).isNotNull();
    }

    @Test
    void refusalTextNamesTheModeAndTheAction() {
        PolicyGate.Refused r = PolicyGate.decide(READ_ONLY, "Prod", PolicyGate.Action.PUSH);
        assertThat(r.getMessage()).isEqualTo(
                "Not allowed in deployment mode Prod: pushing is turned off by the gateway's Git policy.");
        assertThat(r.shortReason()).isEqualTo("off in deployment mode Prod");
        assertThat(PolicyGate.decide(READ_ONLY, null, PolicyGate.Action.LAYOUT_SWITCH).getMessage())
                .startsWith("Not allowed on this gateway: switching the repository layout");
    }

    @Test
    void forcePushNeedsPushToo() {
        GitPolicy pushOnly = new GitPolicy(true, true, true, true, null, null, false);
        assertThat(PolicyGate.decide(pushOnly, "Dev", PolicyGate.Action.PUSH)).isNull();
        assertThat(PolicyGate.decide(pushOnly, "Dev", PolicyGate.Action.FORCE_PUSH)).isNotNull();
        GitPolicy noPush = new GitPolicy(false, true, true, true, null, null, true);
        assertThat(PolicyGate.decide(noPush, "Dev", PolicyGate.Action.FORCE_PUSH)).isNotNull();
    }

    @Test
    void deliveriesMapOntoTheFourKindsAndOffIsAlwaysAllowed() {
        assertThat(PolicyGate.decideDelivery(READ_ONLY, "Prod", "sync-pull")).isNull();
        assertThat(PolicyGate.decideDelivery(READ_ONLY, "Prod", "off")).isNull();
        assertThat(PolicyGate.decideDelivery(READ_ONLY, "Prod", "sync-replace")).isNotNull();
        assertThat(PolicyGate.decideDelivery(READ_ONLY, "Prod", "runner-release").getMessage())
                .contains("delivery 'runner'");
        GitPolicy none = new GitPolicy(true, true, true, true, List.of(), null, true);
        assertThat(PolicyGate.decideDelivery(none, null, "off")).isNull();
        assertThat(PolicyGate.decideDelivery(none, null, "pull")).isNotNull();
    }

    @Test
    void hostsAreReadFromEveryUrlForm() {
        assertThat(PolicyGate.hostOf("https://GitHub.com/org/repo.git")).isEqualTo("github.com");
        assertThat(PolicyGate.hostOf("https://user:tok@git.example.com:8443/r.git")).isEqualTo("git.example.com");
        assertThat(PolicyGate.hostOf("ssh://git@github.com/org/repo.git")).isEqualTo("github.com");
        assertThat(PolicyGate.hostOf("git@github.com:org/repo.git")).isEqualTo("github.com");
        assertThat(PolicyGate.hostOf("file:///srv/repo.git")).isEmpty();
        assertThat(PolicyGate.hostOf("/srv/repo.git")).isEmpty();
        assertThat(PolicyGate.hostOf("C:\\repos\\x.git")).isEmpty();
    }

    @Test
    void allowedHostsAreExactOrSubdomainWildcards() {
        GitPolicy c = new GitPolicy(true, true, true, true, null, List.of("github.com", "*.corp.example"), true);
        assertThat(PolicyGate.decideHost(c, "Prod", "git@github.com:o/r.git")).isNull();
        assertThat(PolicyGate.decideHost(c, "Prod", "https://git.corp.example/r.git")).isNull();
        assertThat(PolicyGate.decideHost(c, "Prod", "https://evilgithub.com/r.git")).isNotNull();
        assertThat(PolicyGate.decideHost(c, "Prod", "https://gitlab.com/r.git").getMessage())
                .isEqualTo("Not allowed in deployment mode Prod: the gateway's Git policy does not list"
                        + " the host gitlab.com in its allowed remote hosts.");
        assertThat(PolicyGate.decideHost(c, "Prod", "/srv/repo.git")).isNotNull();
    }

    @Test
    void aCredentialIsNeverSentToAnotherHost() {
        assertThat(PolicyGate.credentialMismatch("github.com", "https://github.com/o/r.git")).isNull();
        assertThat(PolicyGate.credentialMismatch("github.com", "https://gitlab.com/o/r.git"))
                .startsWith("Refused to send the credential for github.com to gitlab.com");
        assertThat(PolicyGate.credentialMismatch("*.corp.example", "https://git.corp.example/r.git")).isNull();
        // The HTTPS host field was a free-text label before; one that names no host binds nothing.
        assertThat(PolicyGate.credentialMismatch("My GitHub token", "https://gitlab.com/o/r.git")).isNull();
        assertThat(PolicyGate.credentialMismatch("", "https://gitlab.com/o/r.git")).isNull();
    }

    @Test
    void theTrailerIsAddedOnceAndOnlyInAMode() {
        assertThat(PolicyGate.withTrailer("Fix pump view", null)).isEqualTo("Fix pump view");
        String once = PolicyGate.withTrailer("Fix pump view\n", "Dev");
        assertThat(once).isEqualTo("Fix pump view\n\nGateway-Mode: Dev");
        assertThat(PolicyGate.withTrailer(once, "Dev")).isEqualTo(once);
        // No context in a unit test, so the live variant leaves the message alone.
        assertThat(PolicyGate.trailer("x")).isEqualTo("x");
    }
}
