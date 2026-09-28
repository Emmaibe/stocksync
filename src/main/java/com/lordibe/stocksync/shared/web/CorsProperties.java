package com.lordibe.stocksync.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param allowedOrigins exact origins permitted to call this API from a browser.
 *                       Listed explicitly rather than wildcarded: a wildcard with
 *                       credentials is rejected by browsers anyway, and "allow
 *                       everyone" is how a public API becomes a confused deputy
 *                       for someone else's page.
 */
@ConfigurationProperties(prefix = "stocksync.web.cors")
public record CorsProperties(
        @DefaultValue("http://localhost:3000") List<String> allowedOrigins
) {
}
