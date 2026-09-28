package com.lordibe.stocksync.inventory.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A request to append one entry to the ledger.
 *
 * @param quantity        signed, and the sign must match {@code movementType}
 * @param occurredAt      when it happened in the real world; may predate now for
 *                        movements synced from an offline client
 * @param idempotencyKey  optional per-tenant key; a repeat returns the movement
 *                        already recorded instead of adding another
 */
public record RecordMovementCommand(
        UUID itemId,
        UUID locationId,
        MovementType movementType,
        BigDecimal quantity,
        BigDecimal unitCost,
        String reason,
        String reference,
        OffsetDateTime occurredAt,
        String idempotencyKey,
        UUID createdBy
) {

    public RecordMovementCommand {
        if (itemId == null || locationId == null || movementType == null) {
            throw new InvalidMovementException("itemId, locationId and movementType are required");
        }
        if (movementType.isTransferLeg()) {
            throw new InvalidMovementException(
                    "Transfer legs are recorded as a pair; use StockMovementService.transfer(...)");
        }
        if (!movementType.permits(quantity)) {
            throw new InvalidMovementException(
                    "Quantity " + quantity + " has the wrong sign for " + movementType);
        }
        if (unitCost != null && unitCost.signum() < 0) {
            throw new InvalidMovementException("unitCost cannot be negative");
        }
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }
}
