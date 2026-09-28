package com.lordibe.stocksync.iam;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.iam.service.AuthenticationService;
import com.lordibe.stocksync.iam.service.TokenPair;
import com.lordibe.stocksync.iam.service.TooManyLoginAttemptsException;
import com.lordibe.stocksync.iam.web.dto.LoginRequest;
import com.lordibe.stocksync.shared.security.JwtClaims;
import com.lordibe.stocksync.shared.security.TenantContextFilter;
import com.lordibe.stocksync.shared.tenancy.TenancyProperties;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import com.lordibe.stocksync.tenant.domain.Tenant;
import com.lordibe.stocksync.tenant.domain.TenantPlan;
import com.lordibe.stocksync.tenant.domain.TenantStatus;
import com.lordibe.stocksync.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Covers what the tokens are for: proving who the caller is, and which tenant's
 * data they may touch.
 */
class AuthenticationIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired AuthenticationService authentication;
    @Autowired AppUserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired TenancyProperties tenancyProperties;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seed() {
        slug = "acme-" + UUID.randomUUID();
        Tenant tenant = tenants.save(Tenant.builder()
                .slug(slug).name("Acme").status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        tenantId = tenant.getId();
        users.save(newUser("Ann@acme.io", UserStatus.ACTIVE));
        users.save(newUser("disabled@acme.io", UserStatus.DISABLED));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a valid login issues a token carrying the tenant and role")
    void loginIssuesTenantScopedToken() {
        TokenPair tokens = authentication.login(new LoginRequest(slug, "ann@acme.io", PASSWORD));

        Jwt accessToken = jwtDecoder.decode(tokens.accessToken());
        assertThat(accessToken.getClaimAsString(JwtClaims.TENANT_ID)).isEqualTo(tenantId.toString());
        assertThat(accessToken.getClaimAsString(JwtClaims.ROLE)).isEqualTo(UserRole.MANAGER.name());
        // Rules authorise on these, so this is the claim that actually matters.
        assertThat(accessToken.getClaimAsStringList(JwtClaims.PERMISSIONS))
                .containsExactlyInAnyOrderElementsOf(UserRole.MANAGER.permissionCodes())
                .contains("stock.record", "stock.transfer")
                .doesNotContain("stock.rebuild");
        assertThat(accessToken.getClaimAsString(JwtClaims.TOKEN_TYPE)).isEqualTo(JwtClaims.TYPE_ACCESS);
        // Email is matched case-insensitively, as the unique index is.
        assertThat(accessToken.getSubject()).isNotBlank();
    }

    @Test
    @DisplayName("wrong password, unknown email, unknown tenant and disabled account all fail alike")
    void everyFailureLooksTheSame() {
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> authentication.login(new LoginRequest(slug, "ann@acme.io", "wrong")));
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> authentication.login(new LoginRequest(slug, "nobody@acme.io", PASSWORD)));
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> authentication.login(new LoginRequest("no-such-tenant", "ann@acme.io", PASSWORD)));
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> authentication.login(new LoginRequest(slug, "disabled@acme.io", PASSWORD)));
    }

    @Test
    @DisplayName("a refresh token buys a new pair, but an access token does not")
    void refreshRequiresARefreshToken() {
        TokenPair original = authentication.login(new LoginRequest(slug, "ann@acme.io", PASSWORD));

        TokenPair refreshed = authentication.refresh(original.refreshToken());
        assertThat(jwtDecoder.decode(refreshed.accessToken()).getClaimAsString(JwtClaims.TENANT_ID))
                .isEqualTo(tenantId.toString());

        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> authentication.refresh(original.accessToken()));
        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> authentication.refresh("not-a-token"));
    }

    @Test
    @DisplayName("logging out invalidates outstanding refresh tokens")
    void logoutRevokesRefreshTokens() {
        TokenPair tokens = authentication.login(new LoginRequest(slug, "ann@acme.io", PASSWORD));
        UUID userId = UUID.fromString(jwtDecoder.decode(tokens.accessToken()).getSubject());

        authentication.logout(userId);

        assertThatExceptionOfType(BadCredentialsException.class)
                .isThrownBy(() -> authentication.refresh(tokens.refreshToken()));

        // A fresh login still works, and its token carries the bumped version.
        TokenPair after = authentication.login(new LoginRequest(slug, "ann@acme.io", PASSWORD));
        assertThat(authentication.refresh(after.refreshToken())).isNotNull();
    }

    @Test
    @DisplayName("repeated failures lock the credential out, and a correct password does not help")
    void repeatedFailuresAreThrottled() {
        LoginRequest wrong = new LoginRequest(slug, "throttled@acme.io", "wrong");
        users.save(newUser("throttled@acme.io", UserStatus.ACTIVE));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatExceptionOfType(BadCredentialsException.class)
                    .isThrownBy(() -> authentication.login(wrong));
        }

        // The sixth is refused before the password is even checked, so knowing the
        // real password does not get an attacker past the lockout.
        assertThatExceptionOfType(TooManyLoginAttemptsException.class)
                .isThrownBy(() -> authentication.login(
                        new LoginRequest(slug, "throttled@acme.io", PASSWORD)));
    }

    @Test
    @DisplayName("a successful login clears the failure count")
    void successResetsTheCounter() {
        users.save(newUser("forgetful@acme.io", UserStatus.ACTIVE));
        LoginRequest wrong = new LoginRequest(slug, "forgetful@acme.io", "wrong");

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatExceptionOfType(BadCredentialsException.class)
                    .isThrownBy(() -> authentication.login(wrong));
        }
        assertThat(authentication.login(
                new LoginRequest(slug, "forgetful@acme.io", PASSWORD))).isNotNull();

        // Four more failures would trip the limit if the count had survived.
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatExceptionOfType(BadCredentialsException.class)
                    .isThrownBy(() -> authentication.login(wrong));
        }
    }

    @Test
    @DisplayName("the filter binds the token's tenant for the request and clears it afterwards")
    void filterBindsAndClearsTenant() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim(JwtClaims.TENANT_ID, tenantId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AtomicReference<UUID> boundDuringRequest = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response) {
                boundDuringRequest.set(TenantContext.current().orElse(null));
            }
        };

        new TenantContextFilter(tenancyProperties)
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(boundDuringRequest.get()).isEqualTo(tenantId);
        // Cleared, so a pooled or virtual thread cannot inherit a stale tenant.
        assertThat(TenantContext.current()).isEmpty();
    }

    private AppUser newUser(String email, UserStatus status) {
        return AppUser.builder()
                .tenantId(tenantId)
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Test User")
                .role(UserRole.MANAGER)
                .status(status)
                .build();
    }
}
