package com.lordibe.stocksync.location.domain;

public enum LocationType {
    WAREHOUSE,
    STORE,
    /** Stock in transit with a driver or sales rep. */
    VEHICLE,
    /** Non-physical bucket: damaged, quarantine, shrinkage. */
    VIRTUAL
}
