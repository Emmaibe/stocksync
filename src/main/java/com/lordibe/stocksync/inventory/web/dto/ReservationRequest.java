package com.lordibe.stocksync.inventory.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** Quantity is always positive; reserving and releasing are separate endpoints. */
public record ReservationRequest(
        @NotNull UUID itemId,
        @NotNull UUID locationId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4)
        BigDecimal quantity
) {
}
