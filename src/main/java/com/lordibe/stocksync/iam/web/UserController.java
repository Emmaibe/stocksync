package com.lordibe.stocksync.iam.web;

import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;
import com.lordibe.stocksync.iam.service.UserDirectoryService;
import com.lordibe.stocksync.iam.web.dto.CurrentUserResponse;
import com.lordibe.stocksync.iam.web.dto.UpdateProfileRequest;
import com.lordibe.stocksync.iam.web.dto.UpdateUserRoleRequest;
import com.lordibe.stocksync.iam.web.dto.UserResponse;
import com.lordibe.stocksync.shared.security.JwtClaims;
import com.lordibe.stocksync.shared.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The people already in a tenant.
 *
 * <p>Adding someone is an invitation, not a POST here, so that the person sets
 * their own password. Removing someone is a deactivation rather than a delete,
 * because the stock ledger records who made every movement and that has to stay
 * answerable — {@link UserDirectoryService} explains it in full.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserDirectoryService directory;

    @GetMapping
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 25, sort = "fullName", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return PageResponse.from(directory.list(status, search, pageable), UserResponse::from);
    }

    /**
     * Whoever is holding this token.
     *
     * <p>Declared before {@code /{userId}} so "me" is not parsed as a UUID, and
     * open to every signed-in caller: STAFF has no {@code user.read} and must
     * still be able to load their own name and permissions.
     */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt caller) {
        return CurrentUserResponse.from(directory.current(callerId(caller)));
    }

    @PatchMapping("/me")
    public CurrentUserResponse updateMe(@Valid @RequestBody UpdateProfileRequest request,
                                        @AuthenticationPrincipal Jwt caller) {
        return CurrentUserResponse.from(
                directory.updateOwnProfile(callerId(caller), request.fullName()));
    }

    @GetMapping("/{userId}")
    public UserResponse byId(@PathVariable UUID userId) {
        return UserResponse.from(directory.byId(userId));
    }

    /**
     * Moves someone to a different role.
     *
     * <p>This signs them out. Permissions travel inside the access token, so
     * without revoking it a demotion would not take effect until the token
     * expired — fifteen minutes of access somebody has just decided to remove.
     */
    @PatchMapping("/{userId}/role")
    public UserResponse changeRole(@PathVariable UUID userId,
                                   @Valid @RequestBody UpdateUserRoleRequest request,
                                   @AuthenticationPrincipal Jwt caller) {
        return UserResponse.from(directory.changeRole(
                userId, request.role(), callerRole(caller), callerId(caller)));
    }

    /** Ends access, keeps the account and its history. Also signs them out. */
    @PostMapping("/{userId}/deactivate")
    public UserResponse deactivate(@PathVariable UUID userId,
                                   @AuthenticationPrincipal Jwt caller) {
        return UserResponse.from(directory.deactivate(
                userId, callerRole(caller), callerId(caller)));
    }

    @PostMapping("/{userId}/reactivate")
    public UserResponse reactivate(@PathVariable UUID userId,
                                   @AuthenticationPrincipal Jwt caller) {
        return UserResponse.from(directory.reactivate(userId, callerRole(caller)));
    }

    private UUID callerId(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }

    private UserRole callerRole(Jwt caller) {
        return UserRole.valueOf(caller.getClaimAsString(JwtClaims.ROLE));
    }
}
