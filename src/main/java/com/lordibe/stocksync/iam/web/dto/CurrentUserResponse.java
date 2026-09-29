package com.lordibe.stocksync.iam.web.dto;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;

import java.util.Set;
import java.util.UUID;

/**
 * The caller's own record, with the permission codes their role grants.
 *
 * <p>The permissions are here so a client can hide actions the user cannot take.
 * They are a convenience for the interface, never the enforcement: every rule is
 * checked again on the server, because a client can claim anything.
 */
public record CurrentUserResponse(
        UUID id,
        String email,
        String fullName,
        UserRole role,
        UserStatus status,
        Set<String> permissions
) {

    public static CurrentUserResponse from(AppUser user) {
        return new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getStatus(),
                user.getRole().permissionCodes());
    }
}
