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
                        List<String> allowedRemoteHosts, Boolean forcePushAllowed) {

    public static final List<String> DELIVERY_MODES = List.of("off", "pull", "replace", "runner");

    /** Today's behaviour: everything allowed, every host, force push as the Designer offers it. */
    public static final GitPolicy DEFAULT = new GitPolicy(true, true, true, true, DELIVERY_MODES, List.of(), true);

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

    public List<String> deliveries() {
        return deliveryModes == null ? DELIVERY_MODES : deliveryModes;
    }

    public List<String> hosts() {
        return allowedRemoteHosts == null ? List.of() : allowedRemoteHosts;
    }
}
