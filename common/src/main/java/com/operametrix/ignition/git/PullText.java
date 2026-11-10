package com.operametrix.ignition.git;

/**
 * Markers in the text of an exception the gateway sends the Designer, which has no other way to
 * tell one failure from another over RPC.
 */
public final class PullText {

    /** A pull refused because the branch's upstream was deleted: branch, remote, default branch, message (tab-separated). */
    public static final String GONE_PREFIX = "UPSTREAM_GONE:";

    /** Separates a plain message from the original exception, which the Designer shows under Details. */
    public static final String DETAILS = "\n\nDetails: ";

    private PullText() {
    }
}
