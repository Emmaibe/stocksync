package com.lordibe.stocksync.inventory.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/** A movement would leave a slot below zero and negative stock is not allowed. */
@Getter
public class InsufficientStockException extends RuntimeException {

    private final UUID itemId;
    private final UUID locationId;
    private final BigDecimal onHand;
    private final BigDecimal requested;

    public InsufficientStockException(UUID itemId, UUID locationId, BigDecimal onHand, BigDecimal requested) {
        super("Item %s at location %s has %s on hand; cannot apply %s"
                .formatted(itemId, locationId, onHand, requested));
        this.itemId = itemId;
        this.locationId = locationId;
        this.onHand = onHand;
        this.requested = requested;
    }
}
