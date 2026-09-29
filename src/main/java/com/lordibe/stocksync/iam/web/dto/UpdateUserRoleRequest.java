package com.lordibe.stocksync.iam.web.dto;

import com.lordibe.stocksync.iam.domain.UserRole;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(@NotNull UserRole role) {
}
