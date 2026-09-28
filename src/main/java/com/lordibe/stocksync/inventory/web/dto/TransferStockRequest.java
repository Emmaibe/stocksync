package com.lordibe.stocksync.inventory.web.dto;

import com.lordibe.stocksync.inventory.domain.TransferStockCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Moves stock between two of the tenant's own locations.
 *
 * <p>{@code quantity} is positive here — the service derives each leg's sign —
 * which is why this cannot be expressed as two independent movement requests
 * without letting a client leave a transfer half-recorded.
 */
public record TransferStockRequest(
        @NotNull UUID itemId,
        @NotNull UUID sourceLocationId,
        @NotNull UUID destinationLocationId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4)
        BigDecimal quantity,
        @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
        @Size(max = 255) String reason,
        @Size(max = 120) String reference,
        OffsetDateTime occurredAt
) {

    public TransferStockCommand toCommand(String idempotencyKey, UUID createdBy) {
        return new TransferStockCommand(itemId, sourceLocationId, destinationLocationId, quantity,
                unitCost, reason, reference, occurredAt, idempotencyKey, createdBy);
    }
}
