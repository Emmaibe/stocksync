package com.lordibe.stocksync.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
        @NotBlank String token,
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(min = 12, max = 200) String password
) {
}
