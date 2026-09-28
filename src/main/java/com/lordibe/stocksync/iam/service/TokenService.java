package com.lordibe.stocksync.iam.service;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.shared.security.JwtClaims;
import com.lordibe.stocksync.shared.security.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issues the tokens that carry tenancy.
 *
 * <p>The {@code tenant_id} claim is the whole point: it is signed, so a caller
 * cannot choose which tenant's data they see by editing a header, and every
 * request derives {@code TenantContext} from it rather than from anything the
 * client sends separately.
 *
 * <p>Refresh tokens carry only the subject and the tenant. Role and status are
 * re-read from the database on refresh, so a demotion or a disabled account takes
 * effect at the next refresh instead of persisting for the token's lifetime.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public TokenPair issueFor(AppUser user, String tenantSlug) {
        return new TokenPair(
                accessToken(user, tenantSlug),
                refreshToken(user),
                properties.accessTokenTtl());
    }

    public String accessToken(AppUser user, String tenantSlug) {
        Instant now = Instant.now();
        JwtClaimsSet claims = baseClaims(user, now, properties.accessTokenTtl())
                .claim(JwtClaims.TENANT_SLUG, tenantSlug)
                .claim(JwtClaims.ROLE, user.getRole().name())
                // Resolved at issue time, so a role's meaning can change without
                // reissuing anything already in flight.
                .claim(JwtClaims.PERMISSIONS, List.copyOf(user.getRole().permissionCodes()))
                .claim(JwtClaims.TOKEN_TYPE, JwtClaims.TYPE_ACCESS)
                .build();
        return encode(claims);
    }

    public String refreshToken(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = baseClaims(user, now, properties.refreshTokenTtl())
                .claim(JwtClaims.TOKEN_TYPE, JwtClaims.TYPE_REFRESH)
                .build();
        return encode(claims);
    }

    /** True when {@code jwt} is a refresh token rather than an access token. */
    public boolean isRefreshToken(Jwt jwt) {
        return JwtClaims.TYPE_REFRESH.equals(jwt.getClaimAsString(JwtClaims.TOKEN_TYPE));
    }

    public UUID subjectOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public UUID tenantOf(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(JwtClaims.TENANT_ID));
    }

    /** The token's version stamp, or -1 when absent (a token issued before this existed). */
    public long tokenVersionOf(Jwt jwt) {
        Object claim = jwt.getClaim(JwtClaims.TOKEN_VERSION);
        return claim instanceof Number number ? number.longValue() : -1L;
    }

    private JwtClaimsSet.Builder baseClaims(AppUser user, Instant now, Duration ttl) {
        return JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(user.getId().toString())
                .claim(JwtClaims.TENANT_ID, user.getTenantId().toString())
                .claim(JwtClaims.TOKEN_VERSION, user.getTokenVersion());
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(ALGORITHM).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
