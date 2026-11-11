package com.operametrix.ignition.git.managers;

import com.operametrix.ignition.git.automation.GitEvent;
import com.operametrix.ignition.git.automation.GitEvents;
import com.operametrix.ignition.git.records.GitPolicy;
import com.operametrix.ignition.git.records.GitPolicyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Says when the effective Git policy starts allowing more than it did: a delivered or restored
 * branch, a mode override or a mode switch can all loosen Prod's policy, and anyone who can push
 * to the followed branch can write one. Not prevented (they could change scripts anyway), but a
 * WARN in the gateway log and on the Logs tab names each loosened setting.
 *
 * <p>The last policy seen is kept in {@code .git-module-policy-seen} in the data directory, so a
 * change made while the gateway was down (a mode switch needs a restart) is caught at start-up.
 */
public final class PolicyWatch {

    private static final Logger logger = LoggerFactory.getLogger(PolicyWatch.class);
    private static final String FILE = ".git-module-policy-seen";
    private static final Object LOCK = new Object();

    private PolicyWatch() {
    }

    /** Compares the effective policy with the last one seen; {@code cause} names what changed it. */
    public static void check(String cause) {
        synchronized (LOCK) {
            try {
                Path file = GitManager.getDataFolderPath().resolve(FILE);
                GitPolicy now = GitPolicyRecord.get();
                String json = encode(now);
                if (Files.exists(file)) {
                    String before = Files.readString(file, StandardCharsets.UTF_8);
                    if (before.equals(json)) {
                        return;
                    }
                    GitPolicy was = decode(before);
                    List<String> looser = was == null ? List.of() : now.weakerThan(was);
                    if (!looser.isEmpty()) {
                        String msg = "The Git policy now allows more (" + cause + "): " + String.join(", ", looser)
                                + ". Check the branch this gateway follows and its mode overrides.";
                        logger.warn(msg);
                        GitEvents.fire(GitEvent.of("policy").config().warning(msg));
                    }
                }
                Files.writeString(file, json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                logger.warn("Could not compare the Git policy with the last one seen.", e);
            }
        }
    }

    /** One line per setting; lists comma-separated. */
    static String encode(GitPolicy p) {
        return "push=" + p.push() + "\nforcePush=" + p.forcePush() + "\ncommit=" + p.commit()
                + "\nbranches=" + p.branches() + "\nlayout=" + p.layout()
                + "\ndeliveries=" + String.join(",", p.deliveries()) + "\nhosts=" + String.join(",", p.hosts())
                + "\ninsecure=" + p.insecureRemotes() + "\n";
    }

    static GitPolicy decode(String text) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                m.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        java.util.function.Function<String, List<String>> list = k -> m.getOrDefault(k, "").isEmpty()
                ? List.of() : List.of(m.get(k).split(","));
        return new GitPolicy(Boolean.valueOf(m.get("push")), Boolean.valueOf(m.get("commit")),
                Boolean.valueOf(m.get("branches")), Boolean.valueOf(m.get("layout")), list.apply("deliveries"),
                list.apply("hosts"), Boolean.valueOf(m.get("forcePush")), Boolean.valueOf(m.get("insecure")));
    }
}
