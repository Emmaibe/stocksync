package com.lordibe.stocksync.inventory.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A request to move stock between two of a tenant's own locations.
 *
 * @param quantity       positive; the service derives the sign of each leg
 * @param idempotencyKey optional. Because the ledger's key is unique per tenant
 *                       and a transfer writes two rows, the legs are stored as
 *                       {@code <key>:out} and {@code <key>:in}
 */
public record TransferStockCommand(
        UUID itemId,
        UUID sourceLocationId,
        UUID destinationLocationId,
        BigDecimal quantity,
        BigDecimal unitCost,
        String reason,
        String reference,
        OffsetDateTime occurredAt,
        String idempotencyKey,
        UUID createdBy
) {

    public TransferStockCommand {
        if (itemId == null || sourceLocationId == null || destinationLocationId == null) {
            throw new InvalidMovementException("itemId and both locations are required");
        }
        if (sourceLocationId.equals(destinationLocationId)) {
            throw new InvalidMovementException("Source and destination locations must differ");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new InvalidMovementException("Transfer quantity must be positive");
        }
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }

    public String outboundKey() {
        return idempotencyKey == null ? null : idempotencyKey + ":out";
    }

    public String inboundKey() {
        return idempotencyKey == null ? null : idempotencyKey + ":in";
    }
}
