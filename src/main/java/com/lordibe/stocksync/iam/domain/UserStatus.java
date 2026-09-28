package com.lordibe.stocksync.iam.domain;

public enum UserStatus {
    ACTIVE,
    /** Invited but has not set a password yet. Cannot authenticate. */
    INVITED,
    DISABLED
}
