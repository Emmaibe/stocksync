package com.lordibe.stocksync.iam.service;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.Permission;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;
import com.lordibe.stocksync.iam.repository.AppUserRepository;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.shared.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Managing the people already in a tenant: who is there, what they may do, and
 * cutting off the ones who leave.
 *
 * <p>Onboarding lives in {@link OnboardingService} and is not duplicated here.
 * There is still no "create user with a password" operation, for the reason
 * given there: an administrator should never know a colleague's credential.
 *
 * <p><b>Nobody is deleted.</b> Every stock movement carries {@code created_by},
 * under a composite foreign key that includes the tenant, so removing a user
 * would either orphan the ledger or force the history to be rewritten — and the
 * ledger is the one thing in this system that must stay true. Leavers are
 * disabled instead, which ends their access just as completely while keeping
 * "who recorded this" answerable years later. That is also why
 * {@code user.delete} guards nothing here.
 *
 * <p>Two rules run through all of it. A caller may not act on someone more
 * privileged than themselves, and may not act on their own role or status — the
 * first stops sideways escalation, the second stops both self-promotion and an
 * administrator locking themselves out of their own workspace.
 *
 * <p>Ending access is split across two permissions rather than one.
 * {@code user.update} is enough to disable a manager or a staff member, because
 * offboarding is an administrator's job in practice and making it owner-only
 * means nobody can cut off a dismissed employee while the owner is away —
 * failing in exactly the situation the restriction exists for. Disabling another
 * <em>administrator</em> additionally needs {@code user.delete}, which only an
 * owner holds: that is the act that could lock a business out of its own
 * workspace, and it is rare enough to wait for the owner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserDirectoryService {

    private final AppUserRepository users;

    /**
     * The tenant's people, newest activity first.
     *
     * @param status restrict to one status, or null for all
     * @param search matched against name and email, case-insensitively; null for all
     */
    @PreAuthorize("hasAuthority('user.read')")
    public Page<AppUser> list(UserStatus status, String search, Pageable pageable) {
        Collection<UserStatus> statuses = status == null
                ? EnumSet.allOf(UserStatus.class)
                : EnumSet.of(status);
        return users.search(statuses, likePattern(search), pageable);
    }

    @PreAuthorize("hasAuthority('user.read')")
    public AppUser byId(UUID userId) {
        return require(userId);
    }

    /**
     * The caller's own record. No permission check: STAFF has no {@code user.read}
     * and must still be able to see who they are signed in as.
     */
    public AppUser current(UUID callerId) {
        return require(callerId);
    }

    /** Changing your own display name needs no permission — it is your name. */
    @Transactional
    public AppUser updateOwnProfile(UUID callerId, String fullName) {
        AppUser caller = require(callerId);
        caller.setFullName(fullName);
        return caller;
    }

    /**
     * Moves someone to a different role.
     *
     * <p>Tokens are revoked when the role actually changes, and this is the whole
     * point rather than a precaution. Permissions are claims inside the JWT, so a
     * demoted user keeps every permission they had until their access token
     * expires — up to fifteen minutes of authority they are no longer supposed to
     * have, at the exact moment someone decided to take it away.
     */
    @PreAuthorize("hasAuthority('user.update')")
    @Transactional
    public AppUser changeRole(UUID userId, UserRole newRole, UserRole callerRole, UUID callerId) {
        AppUser target = require(userId);

        if (userId.equals(callerId)) {
            // Without this, an ADMIN with user.update simply makes themselves OWNER.
            throw new AccessDeniedException("You cannot change your own role");
        }
        // Lower ordinal means more privileged: OWNER is 0.
        if (newRole.ordinal() < callerRole.ordinal()) {
            throw new AccessDeniedException("You cannot grant more access than you have");
        }
        if (target.getRole().ordinal() < callerRole.ordinal()) {
            throw new AccessDeniedException("You cannot change the role of someone with more access than you");
        }
        if (target.getRole() == newRole) {
            // No change, so no revocation: signing someone out for a no-op edit
            // would be a confusing way to find out nothing happened.
            return target;
        }
        if (target.getRole() == UserRole.OWNER && isLastActiveOwner(target)) {
            throw new ConflictException("This is the only owner; promote someone else to owner first");
        }

        UserRole previous = target.getRole();
        target.setRole(newRole);
        target.revokeTokens();

        log.info("User {} moved from {} to {} by {}", userId, previous, newRole, callerId);
        return target;
    }

    /**
     * Ends someone's access. The account and its history stay.
     *
     * <p>Tokens are revoked here too, for the same reason: without it a dismissed
     * employee keeps working access for the life of their current token, which is
     * precisely the window that matters.
     */
    @PreAuthorize("hasAuthority('user.update')")
    @Transactional
    public AppUser deactivate(UUID userId, UserRole callerRole, UUID callerId) {
        AppUser target = require(userId);

        if (userId.equals(callerId)) {
            throw new AccessDeniedException("You cannot deactivate your own account");
        }
        if (target.getRole().ordinal() < callerRole.ordinal()) {
            // An ADMIN holds user.update, so without this they could disable the
            // OWNER and take the workspace.
            throw new AccessDeniedException("You cannot deactivate someone with more access than you");
        }
        requireOwnerToTouchAnAdmin(target, "deactivate");
        if (target.getStatus() == UserStatus.DISABLED) {
            return target;
        }
        if (target.getStatus() == UserStatus.INVITED) {
            // There is no account to disable yet. Revoking the invitation is the
            // operation they want, and it is on the invitation endpoint.
            throw new ConflictException("That person has not accepted their invitation yet; revoke the invitation instead");
        }
        if (target.getRole() == UserRole.OWNER && isLastActiveOwner(target)) {
            throw new ConflictException("This is the only owner; a workspace cannot be left without one");
        }

        target.setStatus(UserStatus.DISABLED);
        target.revokeTokens();

        log.info("User {} deactivated by {}", userId, callerId);
        return target;
    }

    /** Restores access for someone who was disabled. Their password still works. */
    @PreAuthorize("hasAuthority('user.update')")
    @Transactional
    public AppUser reactivate(UUID userId, UserRole callerRole) {
        AppUser target = require(userId);

        if (target.getRole().ordinal() < callerRole.ordinal()) {
            throw new AccessDeniedException("You cannot reactivate someone with more access than you");
        }
        // Symmetrical with deactivation on purpose: restoring an administrator's
        // access is granting access at administrator level, so it belongs to
        // whoever is allowed to take it away.
        requireOwnerToTouchAnAdmin(target, "reactivate");

        if (target.getStatus() == UserStatus.ACTIVE) {
            return target;
        }
        if (target.getStatus() != UserStatus.DISABLED) {
            throw new ConflictException("Only a disabled account can be reactivated");
        }

        target.setStatus(UserStatus.ACTIVE);
        log.info("User {} reactivated", userId);
        return target;
    }

    /**
     * Ending or restoring an administrator's access needs {@code user.delete},
     * which only an owner holds.
     *
     * <p>Checked against the caller's granted permissions rather than their role,
     * like every other rule here, so per-tenant custom roles later change only
     * which permissions a role carries and not this code.
     *
     * <p>An owner target is already refused above for any caller below owner, and
     * the last-owner rule covers an owner acting on an owner; including owner in
     * this check simply means the reasoning does not depend on that ordering.
     */
    private void requireOwnerToTouchAnAdmin(AppUser target, String verb) {
        if (target.getRole().ordinal() <= UserRole.ADMIN.ordinal()
                && !callerHas(Permission.USER_DELETE)) {
            throw new AccessDeniedException("Only an owner can " + verb + " an administrator");
        }
    }

    private boolean callerHas(Permission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission.code()::equals);
    }

    /**
     * Whether this owner is the last one who can still sign in.
     *
     * <p>Counted rather than assumed, because the alternative — a workspace whose
     * only owner is disabled — cannot be repaired from inside the application.
     */
    private boolean isLastActiveOwner(AppUser owner) {
        return owner.getStatus() == UserStatus.ACTIVE
                && users.countByRoleAndStatus(UserRole.OWNER, UserStatus.ACTIVE) <= 1;
    }

    private AppUser require(UUID userId) {
        // The tenant filter applies to load-by-id, so another tenant's user is
        // not found rather than forbidden — which also refuses to confirm that
        // the id exists at all.
        return users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No such user"));
    }

    /** Escapes the wildcards, so a search for "%" does not match everyone. */
    private String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
