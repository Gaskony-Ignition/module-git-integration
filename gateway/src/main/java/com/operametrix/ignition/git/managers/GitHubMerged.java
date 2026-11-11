package com.operametrix.ignition.git.managers;

import com.operametrix.ignition.git.records.GitPolicyRecord;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The policy option "Only deliver commits merged on GitHub" (4.4.0): a delivery installs a branch
 * tip only when GitHub's web-flow key signed it, which GitHub does for the commits it makes itself
 * — a pull request merged with its merge button, and edits made in its web editor.
 *
 * <p>The keys are bundled, never fetched at run time: {@code github-web-flow.asc} next to this
 * class is {@code https://github.com/web-flow.gpg} as fetched on 11/11/2026, holding
 * {@code 5DE3E0509C47EA3CF04A42D34AEE18F83AFDEB23} (rsa2048, expired 16/01/2024, still valid for
 * signatures made before then) and {@code 968479A1AFF927E37D1A566BB5690EEEBB952194} (rsa4096, from
 * 16/01/2024, no expiry). When GitHub rotates the key, replace the file with that URL's content,
 * check the fingerprints against GitHub's "About commit signature verification" page, and
 * release.
 *
 * <p>The JVM property {@code gitmodule.trustedSigningKeys=<file>} replaces the bundled keys with
 * the armored keys in that file. It exists for the regression suite; setting it needs the
 * gateway's {@code ignition.conf} and a restart, and start-up logs a warning when it is set.
 */
public final class GitHubMerged {

    private static final Logger logger = LoggerFactory.getLogger(GitHubMerged.class);
    public static final String KEYS_PROPERTY = "gitmodule.trustedSigningKeys";
    private static final String BUNDLED = "github-web-flow.asc";

    private static volatile List<WebFlowSignature.TrustedKey> keys;

    /** The last tip refused per repository, so a poll does not log the same refusal again. */
    private static final Map<String, String> refusedTip = new ConcurrentHashMap<>();

    private GitHubMerged() {
    }

    static List<WebFlowSignature.TrustedKey> keys() {
        List<WebFlowSignature.TrustedKey> k = keys;
        if (k == null) {
            synchronized (GitHubMerged.class) {
                if (keys == null) {
                    keys = load();
                }
                k = keys;
            }
        }
        return k;
    }

    private static List<WebFlowSignature.TrustedKey> load() {
        try {
            String override = System.getProperty(KEYS_PROPERTY);
            String text;
            if (override != null && !override.isBlank()) {
                text = Files.readString(Path.of(override.trim()), StandardCharsets.US_ASCII);
                logger.warn("Only deliver commits merged on GitHub: trusting the signing keys in {} ({}), not"
                        + " GitHub's web-flow keys. Remove -D{} from ignition.conf unless this is a test gateway.",
                        override, KEYS_PROPERTY, KEYS_PROPERTY);
            } else {
                try (InputStream in = GitHubMerged.class.getResourceAsStream(BUNDLED)) {
                    if (in == null) {
                        throw new IllegalStateException(BUNDLED + " is missing from the module");
                    }
                    text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
                }
            }
            return List.copyOf(WebFlowSignature.parseKeys(text));
        } catch (Exception e) {
            // No keys verifies nothing, so the option refuses every delivery: fail closed.
            logger.error("Could not load the signing keys for 'Only deliver commits merged on GitHub'; every"
                    + " delivery is refused while it is on.", e);
            return List.of();
        }
    }

    /** Called at start-up so the override warning appears when the gateway starts, not later. */
    public static void loadNow() {
        keys();
    }

    /**
     * Why the policy refuses to install {@code tip}, or null when the option is off or the tip is
     * signed by a trusted key.
     */
    public static String refusal(Repository repo, ObjectId tip) {
        if (!GitPolicyRecord.get().githubMerged()) {
            return null;
        }
        return problem(repo, tip, keys(), Instant.now());
    }

    /** As {@link #refusal}, without the policy (unit tests). */
    static String problem(Repository repo, ObjectId tip, List<WebFlowSignature.TrustedKey> trusted, Instant now) {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit c = walk.parseCommit(tip);
            String p = WebFlowSignature.commitProblem(c.getRawBuffer(), trusted, now);
            return p == null ? null : "Not merged through GitHub: " + tip.abbreviate(7).name() + " — " + p + ".";
        } catch (Exception e) {
            return "Not merged through GitHub: " + tip.abbreviate(7).name() + " could not be read.";
        }
    }

    /**
     * True the first time {@code tip} is refused for {@code repoKey}; false for the same tip again,
     * so a poll every few minutes does not fill the Logs tab with one refusal.
     */
    public static boolean firstRefusal(String repoKey, ObjectId tip) {
        return !tip.getName().equals(refusedTip.put(repoKey, tip.getName()));
    }
}
