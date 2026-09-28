package com.lordibe.stocksync.iam.web.dto;

import com.lordibe.stocksync.iam.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInvitationRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull UserRole role
) {
}
