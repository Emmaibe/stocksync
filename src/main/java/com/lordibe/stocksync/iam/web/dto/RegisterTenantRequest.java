package com.lordibe.stocksync.iam.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creates a business and its first user in one call.
 *
 * @param tenantSlug becomes part of every login for this tenant, so it is
 *                   validated to the same shape the database enforces
 * @param password   12 characters minimum. Length does more for a password than
 *                   character-class rules, which mostly produce "Password1!"
 */
public record RegisterTenantRequest(
        @NotBlank @Size(max = 150) String tenantName,
        @NotBlank @Size(min = 3, max = 80)
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
                message = "must be lowercase letters, digits and hyphens")
        String tenantSlug,
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 12, max = 200) String password
) {
}
