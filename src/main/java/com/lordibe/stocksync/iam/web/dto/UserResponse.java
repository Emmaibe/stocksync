package com.lordibe.stocksync.iam.web.dto;

import com.lordibe.stocksync.iam.domain.AppUser;
import com.lordibe.stocksync.iam.domain.UserRole;
import com.lordibe.stocksync.iam.domain.UserStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A user as the API shows them.
 *
 * <p>Built by hand rather than serialising {@code AppUser}, so the password hash
 * and the token version cannot be exposed by someone adding a field to the
 * entity later.
 */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        UserRole role,
        UserStatus status,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt
) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getStatus(),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }
}
