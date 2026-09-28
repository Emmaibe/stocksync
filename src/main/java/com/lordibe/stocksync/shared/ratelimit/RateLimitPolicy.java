package com.lordibe.stocksync.shared.ratelimit;

import java.time.Duration;

/**
 * How many attempts a key gets, and over what window.
 *
 * <p>The named constants are the policy decisions, in one place so they can be
 * compared with each other rather than discovered one endpoint at a time.
 *
 * @param name   identifies the bucket, so the same key under two policies does
 *               not share a counter
 */
public record RateLimitPolicy(String name, int maxAttempts, Duration window) {

    /** Login: generous enough for a bad morning, tight enough to stop spraying. */
    public static final RateLimitPolicy LOGIN =
            new RateLimitPolicy("login", 5, Duration.ofMinutes(15));

    /**
     * Registration, per client address. Creating a tenant is cheap for the caller
     * and permanent for us, so this is the strictest limit here.
     */
    public static final RateLimitPolicy REGISTRATION =
            new RateLimitPolicy("registration", 3, Duration.ofHours(1));

    /**
     * Password-reset requests. Limited per address because the cost is an email
     * sent to someone who did not ask for it — the classic way to weaponise a
     * reset form is to submit it repeatedly against one victim.
     */
    public static final RateLimitPolicy PASSWORD_RESET_REQUEST =
            new RateLimitPolicy("password-reset", 5, Duration.ofHours(1));

    /** Resending an invitation, per invitation. */
    public static final RateLimitPolicy INVITATION_RESEND =
            new RateLimitPolicy("invitation-resend", 3, Duration.ofHours(1));
}
