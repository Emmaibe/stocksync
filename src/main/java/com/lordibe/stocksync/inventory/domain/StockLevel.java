package com.lordibe.stocksync.inventory.domain;

import com.lordibe.stocksync.shared.domain.AuditedTenantEntity;
import com.lordibe.stocksync.shared.tenancy.TenantFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Current quantity of one item at one location: a projection of the ledger kept
 * for fast reads, and always rebuildable by summing {@code stock_movement}.
 *
 * <p><strong>Only the inventory movement module may write this.</strong> Every
 * other module reads it. Mutators are package-private for that reason, and the
 * version column makes concurrent movements on the same slot fail loudly rather
 * than silently lose one.
 */
@Entity
@Table(name = "stock_level")
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class StockLevel extends AuditedTenantEntity {

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Setter(lombok.AccessLevel.PACKAGE)
    @Column(name = "quantity_on_hand", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal quantityOnHand = BigDecimal.ZERO;

    /** Committed to orders but not yet shipped. Never negative. */
    @Setter(lombok.AccessLevel.PACKAGE)
    @Column(name = "quantity_reserved", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal quantityReserved = BigDecimal.ZERO;

    @Setter(lombok.AccessLevel.PACKAGE)
    @Column(name = "last_movement_id")
    private UUID lastMovementId;

    @Setter(lombok.AccessLevel.PACKAGE)
    @Column(name = "last_movement_at")
    private OffsetDateTime lastMovementAt;

    /** What can actually be promised to a customer. */
    public BigDecimal availableQuantity() {
        return quantityOnHand.subtract(quantityReserved);
    }

    /**
     * Applies a ledger entry to this projection. Package-private so the
     * movement module remains the only writer.
     */
    void apply(StockMovement movement) {
        this.quantityOnHand = this.quantityOnHand.add(movement.getQuantity());
        if (movement.getQuantity().signum() < 0) {
            // Stock leaving is assumed to fulfil an outstanding reservation before
            // eating into free stock. Without an order id tying a movement to a
            // particular reservation this is a guess, but the alternative — leaving
            // the reservation standing — withholds stock that has already gone.
            releaseUpTo(movement.getQuantity().negate());
        }
        this.lastMovementId = movement.getId();
        this.lastMovementAt = movement.getOccurredAt();
    }

    /**
     * Replaces the projected quantity with a figure recomputed from the ledger.
     * Used by the rebuild path, which is both a repair tool and the oracle the
     * tests check the incremental path against.
     */
    void rebuildTo(BigDecimal onHand, StockMovement lastMovement) {
        this.quantityOnHand = onHand;
        this.lastMovementId = lastMovement == null ? null : lastMovement.getId();
        this.lastMovementAt = lastMovement == null ? null : lastMovement.getOccurredAt();
    }

    /**
     * Commits stock to an order without moving it.
     *
     * <p>Reserving does not touch on-hand: the goods are still physically here.
     * It reduces what may be promised to the <em>next</em> customer, and the
     * matching {@code SALE} movement is what eventually removes them.
     */
    void reserve(BigDecimal quantity) {
        BigDecimal reserved = this.quantityReserved.add(quantity);
        if (reserved.compareTo(this.quantityOnHand) > 0) {
            throw new IllegalStateException(
                    "Cannot reserve more than is on hand: %s reserved of %s"
                            .formatted(reserved, this.quantityOnHand));
        }
        this.quantityReserved = reserved;
    }

    /** Releases a reservation — the order shipped, or was cancelled. */
    void release(BigDecimal quantity) {
        BigDecimal reserved = this.quantityReserved.subtract(quantity);
        if (reserved.signum() < 0) {
            throw new IllegalStateException(
                    "Cannot release more than is reserved: %s reserved, releasing %s"
                            .formatted(this.quantityReserved, quantity));
        }
        this.quantityReserved = reserved;
    }

    /**
     * Keeps the reservation honest when stock leaves.
     *
     * <p>A sale of goods that were reserved must release the reservation too, or
     * the slot would keep withholding stock that has already gone. Clamped,
     * because stock can also leave that was never reserved.
     */
    void releaseUpTo(BigDecimal quantity) {
        this.quantityReserved = this.quantityReserved.subtract(quantity).max(BigDecimal.ZERO);
    }

    /** A zeroed slot, created the first time an item moves at a location. */
    static StockLevel emptySlot(UUID tenantId, UUID itemId, UUID locationId) {
        StockLevel level = StockLevel.builder()
                .itemId(itemId)
                .locationId(locationId)
                .quantityOnHand(BigDecimal.ZERO)
                .quantityReserved(BigDecimal.ZERO)
                .build();
        level.setTenantId(tenantId);
        return level;
    }
}
