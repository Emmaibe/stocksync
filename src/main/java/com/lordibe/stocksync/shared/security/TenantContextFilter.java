package com.lordibe.stocksync.shared.security;

import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.tenancy.TenancyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Binds the authenticated caller's tenant to the request thread.
 *
 * <p>The tenant comes from the signed {@code tenant_id} claim and nowhere else —
 * not a header, not a path segment, not a query parameter — so a caller cannot
 * reach another tenant's data by changing the request.
 *
 * <p>Runs after authentication and always clears the binding in a {@code finally}.
 * That matters more than usual here: with virtual threads enabled a leaked
 * {@link ThreadLocal} could surface a stale tenant on an unrelated request.
 */
@Slf4j
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenancyProperties tenancyProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID tenantId = tenantFromAuthentication();
        if (tenantId != null) {
            TenantContext.set(tenantId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID tenantFromAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return null;
        }
        Jwt jwt = token.getToken();
        String claim = jwt.getClaimAsString(JwtClaims.TENANT_ID);
        if (claim == null) {
            // A signed token with no tenant should not exist; treat it as a bug
            // rather than quietly running unscoped.
            if (tenancyProperties.requireTenantContext()) {
                throw new IllegalStateException(
                        "Authenticated token carries no " + JwtClaims.TENANT_ID + " claim");
            }
            log.warn("Authenticated token carries no {} claim", JwtClaims.TENANT_ID);
            return null;
        }
        return UUID.fromString(claim);
    }
}
