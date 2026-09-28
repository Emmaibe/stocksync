package com.lordibe.stocksync.platform.email.domain;

public enum EmailStatus {
    /** Waiting for the dispatcher, or waiting out a backoff after a failure. */
    PENDING,
    SENT,
    /** Gave up after the attempt limit. Kept for inspection, never retried. */
    FAILED
}
