package com.lordibe.stocksync.iam.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param tenantSlug which business the caller is signing in to. Required because
 *                   the same email may hold an account in several tenants.
 */
public record LoginRequest(
        @NotBlank @Size(max = 80) String tenantSlug,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 200) String password
) {
}
