package com.lordibe.stocksync.iam.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Asks for a reset link.
 *
 * <p>The tenant slug is required: the same email may hold accounts in several
 * workspaces, and resetting "the account" would be ambiguous.
 */
public record PasswordResetRequest(
        @NotBlank @Size(max = 80) String tenantSlug,
        @NotBlank @Email @Size(max = 255) String email
) {
}
