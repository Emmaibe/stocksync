package com.lordibe.stocksync.inventory.domain;

/** The requested movement is self-contradictory, before any stock is consulted. */
public class InvalidMovementException extends RuntimeException {

    public InvalidMovementException(String message) {
        super(message);
    }
}
