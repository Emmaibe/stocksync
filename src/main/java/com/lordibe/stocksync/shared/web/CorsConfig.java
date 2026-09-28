package com.lordibe.stocksync.shared.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Cross-origin rules for browser clients.
 *
 * <p>Tokens travel in an {@code Authorization} header rather than a cookie, so
 * credentials are not allowed here — there is nothing for the browser to attach
 * automatically, and allowing them would only widen what a hostile page can do.
 *
 * <p>{@code Idempotency-Key} has to be named explicitly: a browser will not send
 * a custom request header that the preflight response does not list, so omitting
 * it silently disables retry safety for exactly the flaky-network clients it was
 * built for.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "Accept"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        log.info("CORS allows origins: {}", properties.allowedOrigins());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
