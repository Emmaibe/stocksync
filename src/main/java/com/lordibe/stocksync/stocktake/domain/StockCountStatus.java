package com.lordibe.stocksync.stocktake.domain;

public enum StockCountStatus {
    /** Being counted. Lines can be entered and changed. */
    OPEN,
    /** Corrections have been written to the ledger. Nothing can change. */
    POSTED,
    /** Abandoned without correcting anything. Kept, because the attempt is a fact. */
    CANCELLED
}
