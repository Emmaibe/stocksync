package com.lordibe.stocksync.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param secret          HMAC signing key. Must be at least 32 bytes for HS256;
 *                        supplied per environment, never defaulted in code.
 * @param issuer          {@code iss} claim, and what the decoder validates against.
 * @param accessTokenTtl  short by design: a revoked user keeps working only until
 *                        their access token expires, so this bounds the damage.
 * @param refreshTokenTtl how long a client can stay signed in without re-entering
 *                        a password.
 */
@ConfigurationProperties(prefix = "stocksync.security.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("stocksync") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl
) {

    /** Shortest key HS256 accepts without weakening the signature. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "stocksync.security.jwt.secret must be set and at least "
                            + MINIMUM_SECRET_BYTES + " bytes long");
        }
    }
}
