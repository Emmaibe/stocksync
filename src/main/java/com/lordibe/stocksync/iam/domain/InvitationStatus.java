package com.lordibe.stocksync.iam.domain;

public enum InvitationStatus {
    /** Issued and redeemable until it expires. */
    PENDING,
    ACCEPTED,
    /** Withdrawn by an admin before it was used. */
    REVOKED,
    /** Passed its expiry without being used; swept by the housekeeping job. */
    EXPIRED
}
