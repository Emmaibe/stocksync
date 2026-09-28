package com.lordibe.stocksync.platform.idempotency.service;

/** What should happen to a request carrying an {@code Idempotency-Key}. */
public sealed interface IdempotencyOutcome {

    /** First time this key has been seen: run the request. */
    record Proceed() implements IdempotencyOutcome {
    }

    /** Seen before and finished: return exactly what was returned then. */
    record Replay(int status, String body) implements IdempotencyOutcome {
    }

    /**
     * Same key, different request body. Almost always a client bug — a key was
     * reused for a different operation — and honouring either interpretation
     * risks losing a write, so it is refused.
     */
    record BodyMismatch() implements IdempotencyOutcome {
    }

    /** An identical request is still running; the client retried too early. */
    record InFlight() implements IdempotencyOutcome {
    }
}
