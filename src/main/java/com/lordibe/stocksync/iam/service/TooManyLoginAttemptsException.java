package com.lordibe.stocksync.iam.service;

import lombok.Getter;

import java.time.Duration;

/** Raised when a credential has failed too often in the current window. */
@Getter
public class TooManyLoginAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super("Too many failed login attempts; retry in " + retryAfter.toSeconds() + "s");
        this.retryAfter = retryAfter;
    }
}
