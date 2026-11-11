package com.operametrix.ignition.git.managers;

import com.operametrix.ignition.git.records.GitPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The policy decisions, the host rules and the commit trailer, with no gateway. */
class PolicyGateTest {

    private static final GitPolicy READ_ONLY =
            new GitPolicy(false, false, false, false, List.of("off", "pull"), List.of("github.com"), false, null);

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
        GitPolicy sparse = new GitPolicy(false, null, null, null, null, null, null, null);
        assertThat(PolicyGate.decide(sparse, null, PolicyGate.Action.DESIGNER_COMMIT)).isNull();
        assertThat(PolicyGate.decideDelivery(sparse, null, "runner-repo")).isNull();
        assertThat(PolicyGate.decide(sparse, null, PolicyGate.Action.PUSH)).isNotNull();
    }

    @Test
    void refusalTextNamesTheModeAndTheAction() {
        PolicyGate.Refused r = PolicyGate.decide(READ_ONLY, "Prod", PolicyGate.Action.PUSH);
        assertThat(r.getMessage()).isEqualTo(
                "Pushing is off in Prod (Git policy).");
        assertThat(r.shortReason()).isEqualTo("off in Prod");
        assertThat(PolicyGate.decide(READ_ONLY, null, PolicyGate.Action.LAYOUT_SWITCH).getMessage())
                .isEqualTo("Layout switches are off on this gateway (Git policy).");
        assertThat(PolicyGate.decide(READ_ONLY, "Prod", PolicyGate.Action.BRANCH_SWITCH).getMessage())
                .isEqualTo("Branch changes are off in Prod (Git policy).");
    }

    @Test
    void forcePushNeedsPushToo() {
        GitPolicy pushOnly = new GitPolicy(true, true, true, true, null, null, false, null);
        assertThat(PolicyGate.decide(pushOnly, "Dev", PolicyGate.Action.PUSH)).isNull();
        assertThat(PolicyGate.decide(pushOnly, "Dev", PolicyGate.Action.FORCE_PUSH)).isNotNull();
        GitPolicy noPush = new GitPolicy(false, true, true, true, null, null, true, null);
        assertThat(PolicyGate.decide(noPush, "Dev", PolicyGate.Action.FORCE_PUSH)).isNotNull();
    }

    @Test
    void deliveriesMapOntoTheFourKindsAndOffIsAlwaysAllowed() {
        assertThat(PolicyGate.decideDelivery(READ_ONLY, "Prod", "sync-pull")).isNull();
        assertThat(PolicyGate.decideDelivery(READ_ONLY, "Prod", "off")).isNull();
        assertThat(PolicyGate.decideDelivery(READ_ONLY, "Prod", "sync-replace")).isNotNull();
        assertThat(PolicyGate.decideDelivery(READ_ONLY, "Prod", "runner-release").getMessage())
                .isEqualTo("Delivery 'runner' is off in Prod (Git policy).");
        GitPolicy none = new GitPolicy(true, true, true, true, List.of(), null, true, null);
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
        GitPolicy c = new GitPolicy(true, true, true, true, null, List.of("github.com", "*.corp.example"), true, null);
        assertThat(PolicyGate.decideHost(c, "Prod", "git@github.com:o/r.git")).isNull();
        assertThat(PolicyGate.decideHost(c, "Prod", "https://git.corp.example/r.git")).isNull();
        assertThat(PolicyGate.decideHost(c, "Prod", "https://evilgithub.com/r.git")).isNotNull();
        assertThat(PolicyGate.decideHost(c, "Prod", "https://gitlab.com/r.git").getMessage())
                .isEqualTo("gitlab.com is not an allowed remote host in Prod (Git policy).");
        assertThat(PolicyGate.decideHost(c, "Prod", "/srv/repo.git")).isNotNull();
    }

    @Test
    void aCredentialIsNeverSentToAnotherHost() {
        assertThat(PolicyGate.credentialMismatch("github.com", "https://github.com/o/r.git")).isNull();
        assertThat(PolicyGate.credentialMismatch("github.com", "https://gitlab.com/o/r.git"))
                .startsWith("Refused to send the credential for github.com to gitlab.com");
        assertThat(PolicyGate.credentialMismatch("*.corp.example", "https://git.corp.example/r.git")).isNull();
        // 4.3.0: an HTTPS credential with no host, or a label for one, goes nowhere.
        assertThat(PolicyGate.credentialMismatch("My GitHub token", "https://gitlab.com/o/r.git"))
                .contains("it has no host set");
        assertThat(PolicyGate.credentialMismatch("", "https://gitlab.com/o/r.git")).contains("no host set");
        assertThat(PolicyGate.credentialMismatch("gitea", "https://gitea/o/r.git")).isNull();
        assertThat(PolicyGate.credentialMismatch("gitea", "https://evil.example/o/r.git")).isNotNull();
        // An SSH key carries no binding; a local folder sends nothing anywhere.
        assertThat(PolicyGate.credentialMismatch(null, "https://gitlab.com/o/r.git")).isNull();
        assertThat(PolicyGate.credentialMismatch("github.com", "/srv/git/r.git")).isNull();
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

    @Test
    void anUnreadablePolicyIsLockedNotOpen() {
        GitPolicy l = GitPolicy.LOCKED;
        assertThat(PolicyGate.decide(l, null, PolicyGate.Action.PUSH)).isNotNull();
        assertThat(PolicyGate.decide(l, null, PolicyGate.Action.BRANCH_SWITCH)).isNotNull();
        assertThat(PolicyGate.decide(l, null, PolicyGate.Action.LAYOUT_SWITCH)).isNotNull();
        assertThat(PolicyGate.decideDelivery(l, null, "sync-pull")).isNotNull();
        assertThat(PolicyGate.decideDelivery(l, null, "off")).isNull();
        assertThat(PolicyGate.decideUrl(l, null, "https://github.com/o/r.git")).isNotNull();
        assertThat(PolicyGate.decideUrl(l, null, "file:///srv/r.git")).isNotNull();
    }

    @Test
    void aLooserPolicyNamesWhatItNowAllows() {
        GitPolicy prod = new GitPolicy(false, false, false, false, List.of("off", "replace"),
                List.of("github.com"), false, false);
        assertThat(prod.weakerThan(prod)).isEmpty();
        GitPolicy open = new GitPolicy(true, false, false, false, List.of("off", "replace", "runner"),
                List.of(), false, true);
        assertThat(open.weakerThan(prod)).containsExactly("push to remotes", "delivery runner",
                "allowed remote hosts (now any host)", "insecure remotes");
        assertThat(prod.weakerThan(open)).isEmpty();
        assertThat(PolicyWatch.decode(PolicyWatch.encode(prod)).weakerThan(prod)).isEmpty();
        assertThat(prod.weakerThan(PolicyWatch.decode(PolicyWatch.encode(prod)))).isEmpty();
    }

    @Test
    void githubMergedOnlyIsOffByDefaultAndTurningItOffIsLooser() {
        assertThat(GitPolicy.DEFAULT.githubMerged()).isFalse();
        GitPolicy older = new GitPolicy(true, true, true, true, null, null, true, false);
        assertThat(older.githubMerged()).isFalse();
        GitPolicy strict = new GitPolicy(true, true, true, true, null, null, true, false, true);
        assertThat(older.weakerThan(strict)).containsExactly("deliveries not merged on GitHub");
        assertThat(strict.weakerThan(older)).isEmpty();
        assertThat(PolicyWatch.decode(PolicyWatch.encode(strict)).githubMerged()).isTrue();
        assertThat(strict.summary()).endsWith("GitHub-merged only on (github.com)");
    }
}
