package com.lordibe.stocksync.platform.observability;

/**
 * The three numbers that say whether email is actually leaving the building.
 *
 * <p>An interface rather than a class so the health indicator can be tested
 * without a database — the thresholds are the part worth testing, and they do
 * not need Postgres to exercise.
 */
public interface OutboxStats {

    /** Messages waiting to be sent, including ones waiting out a backoff. */
    long pending();

    /** Messages that exhausted their attempts. Nothing retries these. */
    long failed();

    /**
     * How long the oldest waiting message has been waiting, in minutes, or
     * {@code 0} when nothing is waiting.
     */
    long oldestPendingMinutes();
}
