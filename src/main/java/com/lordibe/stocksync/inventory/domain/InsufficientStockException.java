package com.lordibe.stocksync.inventory.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A movement would leave a slot below zero, and negative stock is not allowed.
 *
 * <p><b>The message is written for the person who hit it</b>, who is usually
 * standing at a shelf rather than reading a log. It carries no identifiers: the
 * screen already shows which item and which place, and
 * "Item 0a2a00bc-a0f7-1e39 at location 0a2a00bc-a0f7-1e39 has 200.0000 on hand;
 * cannot apply -250" told that person nothing they could act on while implying
 * something had gone badly wrong.
 *
 * <p>The ids are still carried as fields, so logs and any future handler keep
 * everything needed to investigate. They simply do not belong in a sentence
 * shown to a warehouse worker.
 */
@Getter
public class InsufficientStockException extends RuntimeException {

    private final UUID itemId;
    private final UUID locationId;
    private final BigDecimal available;
    private final BigDecimal requested;

    private InsufficientStockException(String message, UUID itemId, UUID locationId,
                                       BigDecimal available, BigDecimal requested) {
        super(message);
        this.itemId = itemId;
        this.locationId = locationId;
        this.available = available;
        this.requested = requested;
    }

    /** Taking stock out would go below zero. {@code delta} is negative. */
    public static InsufficientStockException cannotRemove(UUID itemId, UUID locationId,
                                                          BigDecimal onHand, BigDecimal delta) {
        BigDecimal wanted = delta.abs();
        return new InsufficientStockException(
                "There %s only %s in stock here, so %s cannot be taken out."
                        .formatted(isAre(onHand), plain(onHand), plain(wanted)),
                itemId, locationId, onHand, wanted);
    }

    /** Committing stock to an order would promise more than is free. */
    public static InsufficientStockException cannotReserve(UUID itemId, UUID locationId,
                                                           BigDecimal available, BigDecimal requested) {
        return new InsufficientStockException(
                "Only %s %s free to reserve here — the rest is already committed to something else."
                        .formatted(plain(available), isAre(available)),
                itemId, locationId, available, requested);
    }

    /** Trims the trailing zeros the database stores, so 200.0000 reads as 200. */
    private static String plain(BigDecimal value) {
        BigDecimal trimmed = value.stripTrailingZeros();
        return trimmed.scale() < 0 ? trimmed.setScale(0).toPlainString() : trimmed.toPlainString();
    }

    private static String isAre(BigDecimal value) {
        return value.abs().compareTo(BigDecimal.ONE) == 0 ? "is" : "are";
    }
}
