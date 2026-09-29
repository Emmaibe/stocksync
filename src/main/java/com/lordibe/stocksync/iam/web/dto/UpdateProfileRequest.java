package com.lordibe.stocksync.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What a user may change about themselves. Email is identity and is not here. */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 150) String fullName
) {
}
