package com.lordibe.stocksync.shared.security;

import com.lordibe.stocksync.platform.idempotency.web.IdempotencyFilter;
import com.lordibe.stocksync.shared.tenancy.TenancyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Stateless bearer-token security.
 *
 * <p>Defining this chain also replaces Boot's default auto-configuration, which is
 * what was printing a generated password at startup.
 *
 * <p>No sessions and no CSRF token: there is no cookie to forge, every request
 * carries its own bearer token, and clients include mobile apps. Unauthenticated
 * and unauthorised requests get RFC 9457 problem bodies rather than a redirect to
 * a login page that does not exist.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    TenancyProperties tenancyProperties,
                                    ProblemAuthenticationEntryPoint entryPoint,
                                    ProblemAccessDeniedHandler accessDeniedHandler,
                                    IdempotencyFilter idempotencyFilter,
                                    CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return http
                // Without this a browser client is blocked before it can send a
                // single request; the allowed origins come from configuration.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Only these two are reachable without a token; /logout
                        // needs one, since it acts on the caller's own account.
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/register", "/api/v1/auth/invitations/accept",
                                "/api/v1/auth/password-reset", "/api/v1/auth/password-reset/complete")
                            .permitAll()
                        // Listed one by one on purpose. "/actuator/health/**" was a
                        // standing invitation to leak the next health group somebody
                        // adds — the ops group names the database and mail server,
                        // and would have been public the moment it existed.
                        .requestMatchers("/actuator/health", "/actuator/health/readiness",
                                "/actuator/health/liveness", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(handling -> handling
                        // Security failures never reach @RestControllerAdvice, so
                        // these render the problem body themselves.
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // After authentication, before authorisation: the tenant is bound
                // once the token is validated and before anything reads data.
                .addFilterBefore(new TenantContextFilter(tenancyProperties), AuthorizationFilter.class)
                // After authorisation: a request that is refused never claims an
                // idempotency key, and the tenant it is scoped to is already bound.
                .addFilterAfter(idempotencyFilter, AuthorizationFilter.class)
                .build();
    }

    /**
     * Turns the token's {@code permissions} claim into authorities, with no
     * {@code ROLE_} prefix, so rules read {@code hasAuthority('stock.record')}.
     *
     * <p>The role claim is deliberately not mapped: authorising on roles would put
     * the bundle's composition into every rule, which is exactly what makes custom
     * roles painful to add later.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("");
        authorities.setAuthoritiesClaimName(JwtClaims.PERMISSIONS);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * BCrypt at strength 12: slow enough to make offline cracking expensive, fast
     * enough that a login stays well under a tenth of a second.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
