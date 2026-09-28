package com.lordibe.stocksync.shared.ratelimit;

import com.lordibe.stocksync.iam.service.TooManyLoginAttemptsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed-window counter per (policy, key).
 *
 * <p><strong>In memory, so per instance.</strong> With N replicas an attacker
 * effectively gets N times the limit. That is a large improvement over nothing at
 * no infrastructure cost, and the honest upgrade path is Redis — not a cleverer
 * data structure here.
 *
 * <p>A fixed window, not a sliding one: at the boundary a caller can get up to
 * twice the allowance in quick succession. For "slow down a script" that is
 * immaterial; it would matter if this were metering a paid quota.
 */
@Slf4j
@Component
public class RateLimiter {

    /** Bound on distinct keys held, so a spray cannot exhaust the heap. */
    private static final int MAX_TRACKED_KEYS = 100_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(int attempts, Instant startedAt) {

        boolean isExpired(Instant now, Duration length) {
            return startedAt.plus(length).isBefore(now);
        }
    }

    /**
     * Counts one attempt against the key and refuses when the allowance is spent.
     *
     * @throws TooManyLoginAttemptsException carrying how long to wait
     */
    public void checkAndRecord(RateLimitPolicy policy, String key) {
        String bucketKey = policy.name() + "|" + key.toLowerCase(Locale.ROOT);
        Instant now = Instant.now();

        Window window = windows.compute(bucketKey, (ignored, current) ->
                current == null || current.isExpired(now, policy.window())
                        ? new Window(1, now)
                        : new Window(current.attempts() + 1, current.startedAt()));

        if (window.attempts() > policy.maxAttempts()) {
            Duration retryAfter = Duration.between(now, window.startedAt().plus(policy.window()));
            log.debug("Rate limit hit on {} for key ending {}", policy.name(), tail(key));
            throw new TooManyLoginAttemptsException(
                    retryAfter.isNegative() ? Duration.ZERO : retryAfter);
        }
        pruneIfCrowded(now);
    }

    /** Forgets a key's attempts — called when the operation finally succeeds. */
    public void reset(RateLimitPolicy policy, String key) {
        windows.remove(policy.name() + "|" + key.toLowerCase(Locale.ROOT));
    }

    private void pruneIfCrowded(Instant now) {
        if (windows.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        // The longest window in use bounds what can still be relevant.
        windows.values().removeIf(window -> window.isExpired(now, Duration.ofHours(1)));
    }

    /** Keys can be email addresses or IPs; logs get the last few characters only. */
    private String tail(String key) {
        return key.length() <= 4 ? "****" : "…" + key.substring(key.length() - 4);
    }
}
