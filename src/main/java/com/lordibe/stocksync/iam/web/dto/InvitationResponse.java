package com.lordibe.stocksync.iam.web.dto;

import com.lordibe.stocksync.iam.domain.Invitation;
import com.lordibe.stocksync.iam.domain.InvitationStatus;
import com.lordibe.stocksync.iam.domain.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An invitation as seen by administrators.
 *
 * <p>No token field, deliberately. The raw token exists only in the email sent to
 * the invitee, so it travels one channel and an admin cannot read, forward or leak
 * a credential for someone else's account.
 */
public record InvitationResponse(
        UUID id,
        String email,
        UserRole role,
        InvitationStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt
) {

    public static InvitationResponse from(Invitation invitation) {
        return new InvitationResponse(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getRole(),
                invitation.getStatus(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt());
    }
}
