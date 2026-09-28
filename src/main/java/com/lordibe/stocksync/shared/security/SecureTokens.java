package com.lordibe.stocksync.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Single-use secrets that are stored only as a digest.
 *
 * <p>Used for invitation tokens, and for password reset when that arrives. The
 * pattern matters: the raw value is handed out once and only its SHA-256 is kept,
 * so a stolen database yields nothing redeemable. Plain SHA-256 is right here —
 * unlike a password, a 256-bit random token has no guessable structure, so the
 * slow hashing that protects passwords buys nothing.
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private SecureTokens() {
    }

    /** A URL-safe token with 256 bits of entropy. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
