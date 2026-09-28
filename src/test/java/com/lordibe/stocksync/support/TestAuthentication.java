package com.lordibe.stocksync.support;

import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.shared.security.JwtClaims;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.UUID;

/**
 * Puts an authenticated caller in the security context for tests that exercise
 * {@code @PreAuthorize} on services.
 *
 * <p>The authentication is per thread, exactly as in production — which is why a
 * worker thread in the concurrency test has to establish its own.
 */
public final class TestAuthentication {

    private TestAuthentication() {
    }

    public static void as(UserRole role, UUID tenantId) {
        List<String> permissions = List.copyOf(role.permissionCodes());
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim(JwtClaims.TENANT_ID, tenantId.toString())
                .claim(JwtClaims.ROLE, role.name())
                .claim(JwtClaims.PERMISSIONS, permissions)
                .build();
        // Authorities are the permission codes, exactly as the production
        // converter derives them — no ROLE_ prefix.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, permissions.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
