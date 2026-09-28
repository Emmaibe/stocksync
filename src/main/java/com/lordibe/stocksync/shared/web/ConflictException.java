package com.lordibe.stocksync.shared.web;

/** The request contradicts existing state — a duplicate slug, SKU or code. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
