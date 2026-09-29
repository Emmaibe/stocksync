package com.lordibe.stocksync.iam;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.iam.service.OnboardingService;
import com.lordibe.stocksync.iam.service.TokenPair;
import com.lordibe.stocksync.iam.service.UserDirectoryService;
import com.lordibe.stocksync.iam.web.dto.RegisterTenantRequest;
import com.lordibe.stocksync.shared.security.JwtClaims;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import com.lordibe.stocksync.support.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Managing the people in a workspace: seeing them, moving them between roles,
 * and cutting off the ones who leave.
 *
 * <p>Most of these tests are about what the API refuses. The happy paths are
 * simple field changes; the value is in the edits that would hand someone more
 * access than they should have, or lock a workspace out of its own account.
 */
class UserDirectoryIT extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired UserDirectoryService directory;
    @Autowired OnboardingService onboarding;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtDecoder jwtDecoder;

    private UUID tenantId;
    private UUID ownerId;

    @AfterEach
    void clear() {
        TenantContext.clear();
        TestAuthentication.clear();
    }

    @Test
    @DisplayName("the directory lists the workspace's people")
    void listsUsers() {
        asOwner();
        addUser("mo@acme.io", "Mo Manager", UserRole.MANAGER, UserStatus.ACTIVE);
        addUser("sam@acme.io", "Sam Staff", UserRole.STAFF, UserStatus.ACTIVE);

        var page = directory.list(null, null, PageRequest.of(0, 25));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(AppUser::getEmail)
                .containsExactlyInAnyOrder("ann@acme.io", "mo@acme.io", "sam@acme.io");
    }

    @Test
    @DisplayName("the search matches name and email, and wildcards in it are literal")
    void searchIsScopedAndEscaped() {
        asOwner();
        addUser("mo@acme.io", "Mo Manager", UserRole.MANAGER, UserStatus.ACTIVE);

        assertThat(directory.list(null, "mo@", PageRequest.of(0, 25)).getContent()).hasSize(1);
        assertThat(directory.list(null, "manager", PageRequest.of(0, 25)).getContent()).hasSize(1);
        // A bare % must not behave as "everyone": that would turn an empty search
        // box into a full export, and later into a way to page through a tenant.
        assertThat(directory.list(null, "%", PageRequest.of(0, 25)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("one tenant cannot see another's users")
    void listIsTenantScoped() {
        asOwner();
        UUID firstTenant = tenantId;
        addUser("mo@acme.io", "Mo Manager", UserRole.MANAGER, UserStatus.ACTIVE);

        asOwner();
        assertThat(tenantId).isNotEqualTo(firstTenant);

        assertThat(directory.list(null, null, PageRequest.of(0, 25)).getContent())
                .extracting(AppUser::getEmail)
                .containsExactly("ann@acme.io")
                .doesNotContain("mo@acme.io");
    }

    @Test
    @DisplayName("a user can read their own record without user.read")
    void ownRecordNeedsNoPermission() {
        asOwner();
        AppUser sam = addUser("sam@acme.io", "Sam Staff", UserRole.STAFF, UserStatus.ACTIVE);
        // STAFF has no user.read at all; without this being open, a staff member
        // could not load their own name after signing in.
        TestAuthentication.as(UserRole.STAFF, tenantId, sam.getId());

        assertThat(directory.current(sam.getId()).getFullName()).isEqualTo("Sam Staff");
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                directory.list(null, null, PageRequest.of(0, 25)));
    }

    @Test
    @DisplayName("changing a role signs that user out")
    void roleChangeRevokesTokens() {
        asOwner();
        AppUser mo = addUser("mo@acme.io", "Mo", UserRole.MANAGER, UserStatus.ACTIVE);
        long before = mo.getTokenVersion();

        directory.changeRole(mo.getId(), UserRole.STAFF, UserRole.OWNER, ownerId);

        AppUser reloaded = users.findById(mo.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(UserRole.STAFF);
        // The point of the whole operation: permissions live in the token, so a
        // demotion that leaves the old token valid does nothing for 15 minutes.
        assertThat(reloaded.getTokenVersion()).isGreaterThan(before);
    }

    @Test
    @DisplayName("a role change that changes nothing does not sign anybody out")
    void unchangedRoleDoesNotRevoke() {
        asOwner();
        AppUser mo = addUser("mo@acme.io", "Mo", UserRole.MANAGER, UserStatus.ACTIVE);

        directory.changeRole(mo.getId(), UserRole.MANAGER, UserRole.OWNER, ownerId);

        assertThat(users.findById(mo.getId()).orElseThrow().getTokenVersion())
                .isEqualTo(mo.getTokenVersion());
    }

    @Test
    @DisplayName("nobody can change their own role")
    void selfPromotionIsRefused() {
        asOwner();
        AppUser admin = addUser("al@acme.io", "Al Admin", UserRole.ADMIN, UserStatus.ACTIVE);
        TestAuthentication.as(UserRole.ADMIN, tenantId, admin.getId());

        // An ADMIN holds user.update. Without this rule the role system is
        // decorative: they simply make themselves OWNER.
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                directory.changeRole(admin.getId(), UserRole.OWNER, UserRole.ADMIN, admin.getId()));
    }

    @Test
    @DisplayName("an admin cannot promote someone above their own level")
    void grantingMoreAccessThanYouHaveIsRefused() {
        asOwner();
        AppUser admin = addUser("al@acme.io", "Al Admin", UserRole.ADMIN, UserStatus.ACTIVE);
        AppUser mo = addUser("mo@acme.io", "Mo", UserRole.MANAGER, UserStatus.ACTIVE);
        TestAuthentication.as(UserRole.ADMIN, tenantId, admin.getId());

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                directory.changeRole(mo.getId(), UserRole.OWNER, UserRole.ADMIN, admin.getId()));
    }

    @Test
    @DisplayName("an admin cannot demote the owner")
    void actingOnSomeoneMorePrivilegedIsRefused() {
        asOwner();
        AppUser admin = addUser("al@acme.io", "Al Admin", UserRole.ADMIN, UserStatus.ACTIVE);
        TestAuthentication.as(UserRole.ADMIN, tenantId, admin.getId());

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                directory.changeRole(ownerId, UserRole.STAFF, UserRole.ADMIN, admin.getId()));
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                directory.deactivate(ownerId, UserRole.ADMIN, admin.getId()));
    }

    @Test
    @DisplayName("the last owner cannot be demoted or disabled")
    void theWorkspaceKeepsAnOwner() {
        asOwner();
        AppUser second = addUser("bo@acme.io", "Bo", UserRole.OWNER, UserStatus.ACTIVE);

        // With two owners the edit is allowed.
        directory.changeRole(second.getId(), UserRole.ADMIN, UserRole.OWNER, ownerId);

        // With one left it is not, from either direction. A workspace whose only
        // owner is disabled cannot be repaired from inside the application.
        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                directory.changeRole(ownerId, UserRole.ADMIN, UserRole.OWNER, second.getId()));
        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                directory.deactivate(ownerId, UserRole.OWNER, second.getId()));
    }

    @Test
    @DisplayName("deactivating ends access immediately, and the account survives")
    void deactivationRevokesAndKeepsTheRecord() {
        asOwner();
        AppUser mo = addUser("mo@acme.io", "Mo", UserRole.MANAGER, UserStatus.ACTIVE);
        long before = mo.getTokenVersion();

        directory.deactivate(mo.getId(), UserRole.OWNER, ownerId);

        AppUser reloaded = users.findById(mo.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(reloaded.canAuthenticate()).isFalse();
        // Not deleted: stock_movement.created_by still has to resolve to a name.
        assertThat(users.findById(mo.getId())).isPresent();
        assertThat(reloaded.getTokenVersion()).isGreaterThan(before);
    }

    @Test
    @DisplayName("nobody can deactivate themselves")
    void selfDeactivationIsRefused() {
        asOwner();

        // Otherwise the sole administrator of a workspace can lock everybody out
        // with one request, including themselves.
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                directory.deactivate(ownerId, UserRole.OWNER, ownerId));
    }

    @Test
    @DisplayName("someone who has not accepted their invitation is not deactivated here")
    void invitedUsersAreRevokedNotDeactivated() {
        asOwner();
        AppUser pending = addUser("new@acme.io", "New Person", UserRole.STAFF, UserStatus.INVITED);

        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                directory.deactivate(pending.getId(), UserRole.OWNER, ownerId));
    }

    @Test
    @DisplayName("a disabled account can be brought back, an invited one cannot")
    void reactivation() {
        asOwner();
        AppUser mo = addUser("mo@acme.io", "Mo", UserRole.MANAGER, UserStatus.ACTIVE);
        directory.deactivate(mo.getId(), UserRole.OWNER, ownerId);

        AppUser restored = directory.reactivate(mo.getId(), UserRole.OWNER);
        assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(restored.canAuthenticate()).isTrue();

        AppUser pending = addUser("new@acme.io", "New", UserRole.STAFF, UserStatus.INVITED);
        assertThatExceptionOfType(ConflictException.class).isThrownBy(() ->
                directory.reactivate(pending.getId(), UserRole.OWNER));
    }

    @Test
    @DisplayName("a manager can read the directory but cannot change anyone")
    void readingAndWritingAreSeparatePermissions() {
        asOwner();
        AppUser mo = addUser("mo@acme.io", "Mo", UserRole.MANAGER, UserStatus.ACTIVE);
        AppUser sam = addUser("sam@acme.io", "Sam", UserRole.STAFF, UserStatus.ACTIVE);
        TestAuthentication.as(UserRole.MANAGER, tenantId, mo.getId());

        // MANAGER has user.read so a rota can be built, but not user.update.
        assertThat(directory.list(null, null, PageRequest.of(0, 25)).getContent()).isNotEmpty();
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                directory.changeRole(sam.getId(), UserRole.ADMIN, UserRole.MANAGER, mo.getId()));
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() ->
                directory.deactivate(sam.getId(), UserRole.MANAGER, mo.getId()));
    }

    @Test
    @DisplayName("a user can rename themselves")
    void ownProfileIsEditable() {
        asOwner();

        directory.updateOwnProfile(ownerId, "Ann Owner-Smith");

        assertThat(users.findById(ownerId).orElseThrow().getFullName()).isEqualTo("Ann Owner-Smith");
    }

    /** Registers a fresh tenant, binds it, and signs in as its owner. */
    private void asOwner() {
        TokenPair tokens = onboarding.registerTenant(new RegisterTenantRequest(
                "Acme", "acme-" + UUID.randomUUID(), "Ann", "ann@acme.io", PASSWORD));
        var token = jwtDecoder.decode(tokens.accessToken());
        tenantId = UUID.fromString(token.getClaimAsString(JwtClaims.TENANT_ID));
        ownerId = UUID.fromString(token.getSubject());
        TenantContext.set(tenantId);
        TestAuthentication.as(UserRole.OWNER, tenantId, ownerId);
    }

    private AppUser addUser(String email, String name, UserRole role, UserStatus status) {
        return users.save(AppUser.builder()
                .tenantId(tenantId)
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName(name)
                .role(role)
                .status(status)
                .build());
    }
}
