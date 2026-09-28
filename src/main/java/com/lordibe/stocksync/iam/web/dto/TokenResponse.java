package com.lordibe.stocksync.iam.web.dto;

import com.lordibe.stocksync.iam.service.TokenPair;

/**
 * @param expiresIn access token lifetime in seconds, so a client can refresh
 *                  before expiry instead of waiting for a 401
 */
public record TokenResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        long expiresIn
) {

    public static TokenResponse from(TokenPair tokens) {
        return new TokenResponse("Bearer", tokens.accessToken(), tokens.refreshToken(),
                tokens.accessTokenTtl().toSeconds());
    }
}
