package com.lordibe.stocksync.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompletePasswordResetRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 12, max = 200) String newPassword
) {
}
