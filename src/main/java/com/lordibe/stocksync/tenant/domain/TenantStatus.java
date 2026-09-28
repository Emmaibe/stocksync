package com.lordibe.stocksync.tenant.domain;

public enum TenantStatus {
    /** Normal operation. */
    ACTIVE,
    /** Read-only: billing lapsed or under review. Writes are rejected. */
    SUSPENDED,
    /** Terminated; retained only for the data-retention window. */
    CLOSED
}
