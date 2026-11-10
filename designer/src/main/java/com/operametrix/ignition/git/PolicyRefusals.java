package com.operametrix.ignition.git;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the gateway's Git policy refuses in its current deployment mode, refreshed with the Commit
 * panel's poll. Controls read it to show an action disabled with the reason ("off in deployment
 * mode Prod") instead of hiding it; the gateway refuses the action itself either way.
 */
public final class PolicyRefusals {

    private static final Map<String, String> REFUSED = new ConcurrentHashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private PolicyRefusals() {
    }

    /** Asks the gateway; call off the EDT. Listeners run on the EDT when anything changed. */
    public static void refresh() {
        Map<String, String> next = new java.util.HashMap<>();
        try {
            for (String line : DesignerHook.rpc.getPolicyRefusals()) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    next.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (Exception e) {
            return;
        }
        if (!next.equals(REFUSED)) {
            REFUSED.clear();
            REFUSED.putAll(next);
            SwingUtilities.invokeLater(() -> LISTENERS.forEach(Runnable::run));
        }
    }

    /** The short reason {@code action} is refused (PUSH, FORCE_PUSH, DESIGNER_COMMIT, BRANCH_SWITCH), or null. */
    public static String reason(String action) {
        return REFUSED.get(action);
    }

    /** {@code label}, or {@code "label (reason)"} when the policy refuses {@code action}. */
    public static String label(String label, String action) {
        String r = reason(action);
        return r == null ? label : label + " (" + r + ")";
    }

    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }
}
