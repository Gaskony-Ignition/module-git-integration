package com.operametrix.ignition.git.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Event log: the only place the Versioning page shows what an unattended scheduled sync or
 * runner pull did.
 *
 * <p>Every mutating git operation fires here, successes and failures alike. This is a plain
 * synchronous ring buffer — there is no delivery target to be slow or unreachable any more (the
 * script/message and outbound-trigger paths were removed in 3.0.0), so there is nothing left to
 * decouple a firing git operation from. {@link #fire} still never throws: a logging bug must
 * never be able to fail the git operation that triggered it.
 */
public final class GitEvents {

    private static final Logger logger = LoggerFactory.getLogger(GitEvents.class);

    private static final int RECENT_CAPACITY = 50;

    private static final Deque<GitEvent> recent = new ArrayDeque<>(RECENT_CAPACITY);

    private static final AtomicLong fired = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();

    private GitEvents() {
    }

    /** Records an event. Safe to call from any thread; never throws. */
    public static void fire(GitEvent event) {
        try {
            if (event == null) {
                return;
            }
            fired.incrementAndGet();
            if (event.failed()) {
                failures.incrementAndGet();
            }
            synchronized (recent) {
                if (recent.size() >= RECENT_CAPACITY) {
                    recent.removeLast();
                }
                recent.addFirst(event);
            }
        } catch (Throwable t) {
            // The log itself must never be able to fail the git operation that called it.
            logger.error("Failed to record a git event.", t);
        }
    }

    /** Most recent first. */
    public static List<GitEvent> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /**
     * Empties the log and resets its counts. The page shows the counts beside Clear, so leaving
     * them as a lifetime total made "2 events" sit above an empty table.
     */
    public static void clearLog() {
        synchronized (recent) {
            recent.clear();
            fired.set(0);
            failures.set(0);
        }
    }

    /**
     * The innermost cause's message — JGit and the RPC layer both wrap, and a wrapper's own
     * {@code toString()} names the exception class rather than what went wrong.
     */
    public static String reason(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg.trim();
    }

    public record Stats(long fired, long failures) {}

    public static Stats stats() {
        return new Stats(fired.get(), failures.get());
    }
}
