package com.operametrix.ignition.git.records;

import java.util.List;

/**
 * The gateway's Git policy, as stored in the {@code git-policy} resource. Kept apart from
 * {@link GitPolicyRecord} so the decisions on it are unit tested without the SDK.
 *
 * <p>Every field is boxed: a field left out of a hand-written override reads as the default, never
 * as "off".
 */
public record GitPolicy(Boolean pushAllowed, Boolean designerCommit, Boolean branchSwitch,
                        Boolean layoutSwitch, List<String> deliveryModes,
                        List<String> allowedRemoteHosts, Boolean forcePushAllowed,
                        Boolean allowInsecureRemotes, Boolean githubMergedOnly, List<String> githubHosts) {

    /** A policy saved before 4.4.0: GitHub-merged-only reads as off. */
    public GitPolicy(Boolean pushAllowed, Boolean designerCommit, Boolean branchSwitch, Boolean layoutSwitch,
                     List<String> deliveryModes, List<String> allowedRemoteHosts, Boolean forcePushAllowed,
                     Boolean allowInsecureRemotes) {
        this(pushAllowed, designerCommit, branchSwitch, layoutSwitch, deliveryModes, allowedRemoteHosts,
                forcePushAllowed, allowInsecureRemotes, null, null);
    }

    public GitPolicy(Boolean pushAllowed, Boolean designerCommit, Boolean branchSwitch, Boolean layoutSwitch,
                     List<String> deliveryModes, List<String> allowedRemoteHosts, Boolean forcePushAllowed,
                     Boolean allowInsecureRemotes, Boolean githubMergedOnly) {
        this(pushAllowed, designerCommit, branchSwitch, layoutSwitch, deliveryModes, allowedRemoteHosts,
                forcePushAllowed, allowInsecureRemotes, githubMergedOnly, null);
    }

    /** github.com unless set: the hosts whose web-flow signature "merged on GitHub" trusts (GitHub Enterprise). */
    public List<String> gitHubHosts() {
        return githubHosts == null || githubHosts.isEmpty() ? List.of("github.com") : githubHosts;
    }

    public static final List<String> DELIVERY_MODES = List.of("off", "pull", "replace", "runner");

    /**
     * Everything allowed, every host, force push as the Designer offers it; remotes over HTTPS or
     * SSH only (4.3.0).
     */
    public static final GitPolicy DEFAULT =
            new GitPolicy(true, true, true, true, DELIVERY_MODES, List.of(), true, false, false, null);

    public boolean push() {
        return pushAllowed == null || pushAllowed;
    }

    public boolean commit() {
        return designerCommit == null || designerCommit;
    }

    public boolean branches() {
        return branchSwitch == null || branchSwitch;
    }

    public boolean layout() {
        return layoutSwitch == null || layoutSwitch;
    }

    public boolean forcePush() {
        return forcePushAllowed == null || forcePushAllowed;
    }

    /**
     * What applies when the saved policy cannot be read: no push, branch or layout switch, no
     * delivery but off, no remote host at all ("-" is no host name), no insecure remotes.
     */
    public static final GitPolicy LOCKED =
            new GitPolicy(false, true, false, false, List.of("off"), List.of("-"), false, false, true, null);

    /** Local folders, file://, git://, http:// and local-network targets. Off unless set. */
    public boolean insecureRemotes() {
        return allowInsecureRemotes != null && allowInsecureRemotes;
    }

    /**
     * Deliveries install a branch tip only when GitHub's web-flow key signed it (a pull request
     * merged in GitHub's UI). Off unless set (4.4.0).
     */
    public boolean githubMerged() {
        return githubMergedOnly != null && githubMergedOnly;
    }

    public List<String> deliveries() {
        return deliveryModes == null ? DELIVERY_MODES : deliveryModes;
    }

    /**
     * The settings that allow more here than in {@code before}: an action turned on, a delivery
     * kind or remote host added (or the host list emptied, which allows any), insecure remotes on.
     */
    public List<String> weakerThan(GitPolicy before) {
        List<String> out = new java.util.ArrayList<>();
        if (push() && !before.push()) {
            out.add("push to remotes");
        }
        if (forcePush() && push() && !(before.forcePush() && before.push())) {
            out.add("force push");
        }
        if (commit() && !before.commit()) {
            out.add("commit from the Designer");
        }
        if (branches() && !before.branches()) {
            out.add("branches");
        }
        if (layout() && !before.layout()) {
            out.add("layout switch");
        }
        for (String d : deliveries()) {
            if (!before.deliveries().contains(d)) {
                out.add("delivery " + d);
            }
        }
        if (!before.hosts().isEmpty()) {
            if (hosts().isEmpty()) {
                out.add("allowed remote hosts (now any host)");
            } else {
                for (String h : hosts()) {
                    if (!before.hosts().contains(h)) {
                        out.add("allowed remote host " + h);
                    }
                }
            }
        }
        if (insecureRemotes() && !before.insecureRemotes()) {
            out.add("insecure remotes");
        }
        if (!githubMerged() && before.githubMerged()) {
            out.add("deliveries not merged on GitHub");
        }
        if (githubMerged() && before.githubMerged()) {
            for (String h : gitHubHosts()) {
                if (!before.gitHubHosts().contains(h)) {
                    out.add("GitHub host " + h);
                }
            }
        }
        return out;
    }

    /** Every setting in one line, for an audit row. */
    public String summary() {
        return "push " + onOff(push()) + ", force push " + onOff(forcePush()) + ", Designer commit "
                + onOff(commit()) + ", branches " + onOff(branches()) + ", layout " + onOff(layout())
                + ", deliveries " + String.join("/", deliveries()) + ", hosts "
                + (hosts().isEmpty() ? "any" : String.join(" ", hosts())) + ", insecure remotes "
                + onOff(insecureRemotes()) + ", GitHub-merged only " + onOff(githubMerged())
                + (githubMerged() ? " (" + String.join(" ", gitHubHosts()) + ")" : "");
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    public List<String> hosts() {
        return allowedRemoteHosts == null ? List.of() : allowedRemoteHosts;
    }
}
