package com.lordibe.stocksync.inventory.web.dto;

import com.lordibe.stocksync.inventory.domain.MovementType;
import com.lordibe.stocksync.inventory.domain.StockMovement;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One ledger entry as returned to clients.
 *
 * <p>{@code tenantId} is deliberately absent: the caller's token already fixes it,
 * and echoing it back invites clients to treat it as something they can vary.
 */
public record StockMovementResponse(
        UUID id,
        UUID itemId,
        UUID locationId,
        MovementType movementType,
        BigDecimal quantity,
        BigDecimal unitCost,
        String reason,
        String reference,
        UUID transferGroupId,
        OffsetDateTime occurredAt,
        OffsetDateTime recordedAt,
        UUID createdBy
) {

    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(
                movement.getId(),
                movement.getItemId(),
                movement.getLocationId(),
                movement.getMovementType(),
                movement.getQuantity(),
                movement.getUnitCost(),
                movement.getReason(),
                movement.getReference(),
                movement.getTransferGroupId(),
                movement.getOccurredAt(),
                movement.getRecordedAt(),
                movement.getCreatedBy());
    }
}
