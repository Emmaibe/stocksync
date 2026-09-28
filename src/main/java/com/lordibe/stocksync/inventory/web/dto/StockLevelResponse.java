package com.lordibe.stocksync.inventory.web.dto;

import com.lordibe.stocksync.inventory.domain.StockLevel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * @param available on hand minus reserved — what can actually be promised to a
 *                  customer, computed here so every client does not reimplement it
 */
public record StockLevelResponse(
        UUID itemId,
        UUID locationId,
        BigDecimal onHand,
        BigDecimal reserved,
        BigDecimal available,
        OffsetDateTime lastMovementAt
) {

    public static StockLevelResponse from(StockLevel level) {
        return new StockLevelResponse(
                level.getItemId(),
                level.getLocationId(),
                level.getQuantityOnHand(),
                level.getQuantityReserved(),
                level.availableQuantity(),
                level.getLastMovementAt());
    }
}
