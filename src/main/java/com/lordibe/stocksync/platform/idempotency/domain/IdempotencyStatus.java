package com.lordibe.stocksync.platform.idempotency.domain;

public enum IdempotencyStatus {
    /** Claimed by a request still running; a concurrent retry must wait or 409. */
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
