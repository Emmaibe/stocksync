package com.lordibe.stocksync.iam.service;

import java.time.Duration;

/** A freshly issued access/refresh pair. */
public record TokenPair(String accessToken, String refreshToken, Duration accessTokenTtl) {
}
