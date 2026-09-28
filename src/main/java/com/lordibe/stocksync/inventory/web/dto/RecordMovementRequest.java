package com.lordibe.stocksync.inventory.web.dto;

import com.lordibe.stocksync.inventory.domain.MovementType;
import com.lordibe.stocksync.inventory.domain.RecordMovementCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A receipt, sale or adjustment.
 *
 * <p>{@code quantity} is signed and its sign must match the movement type; the
 * command object enforces that and the database re-checks it. Transfers are not
 * accepted here — they are two ledger entries and have their own endpoint.
 *
 * @param occurredAt when it happened in the real world. Optional: an offline
 *                   client sends the original timestamp when it finally syncs,
 *                   and omitting it means "now".
 */
public record RecordMovementRequest(
        @NotNull UUID itemId,
        @NotNull UUID locationId,
        @NotNull MovementType movementType,
        @NotNull @Digits(integer = 15, fraction = 4) BigDecimal quantity,
        @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
        @Size(max = 255) String reason,
        @Size(max = 120) String reference,
        OffsetDateTime occurredAt
) {

    public RecordMovementCommand toCommand(String idempotencyKey, UUID createdBy) {
        return new RecordMovementCommand(itemId, locationId, movementType, quantity, unitCost,
                reason, reference, occurredAt, idempotencyKey, createdBy);
    }
}
