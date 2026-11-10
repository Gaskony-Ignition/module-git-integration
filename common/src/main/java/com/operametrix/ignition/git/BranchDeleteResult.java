package com.operametrix.ignition.git;

/**
 * The text a delete returns over RPC: empty when the branch went, else "kind|count|message".
 * A string rather than an exception so the Designer can tell a refusal it can offer to override
 * (unmerged, unpushed) from one it cannot (protected).
 */
public record BranchDeleteResult(String kind, int count, String message) {

    public static String encode(String kind, int count, String message) {
        return kind + "|" + count + "|" + message;
    }

    /** @return null when the text means the branch was deleted */
    public static BranchDeleteResult parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] p = text.split("\\|", 3);
        if (p.length < 3) {
            return new BranchDeleteResult("error", 0, text);
        }
        int n;
        try {
            n = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            n = 0;
        }
        return new BranchDeleteResult(p[0], n, p[2]);
    }
}
