package com.lordibe.stocksync.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API documentation, with the bearer scheme declared so the "Authorize" button in
 * the UI actually works against the real endpoints.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI stocksyncOpenApi(@Value("${stocksync.openapi.version:v1}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("StockSync API")
                        .version(version)
                        .description("""
                                Multi-tenant inventory API.

                                Every request outside /api/v1/auth needs a bearer token; the
                                tenant is taken from the token's tenant_id claim, never from a
                                header or path. Unsafe requests accept an Idempotency-Key
                                header — repeating a key returns the original response instead
                                of performing the work again."""))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
