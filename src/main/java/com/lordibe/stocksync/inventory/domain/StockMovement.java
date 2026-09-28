package com.lordibe.stocksync.inventory.domain;

import com.lordibe.stocksync.shared.domain.TenantScopedEntity;
import com.lordibe.stocksync.shared.tenancy.TenantFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry in the append-only stock ledger, and the only way stock changes.
 *
 * <p>{@link Immutable} is deliberate: a mistake is corrected by writing a
 * compensating movement, never by editing history. There is no setter, no
 * version column and no {@code updated_at} — the row is written once.
 *
 * <p>Cross-module references ({@code itemId}, {@code locationId},
 * {@code createdBy}) are held as ids rather than JPA associations, so the
 * catalogue, location and IAM modules stay separable and the ledger never drags
 * their object graphs into a write. The database still enforces the links with
 * composite foreign keys that include {@code tenant_id}.
 */
@Entity
@Table(name = "stock_movement")
@Immutable
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class StockMovement extends TenantScopedEntity {

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20, updatable = false)
    private MovementType movementType;

    /**
     * Signed delta applied to on-hand at this location. Sign is constrained per
     * movement type, so on-hand is simply the sum of a slot's movements.
     */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quantity;

    /** Cost per unit at the time of the movement; drives valuation later. */
    @Column(name = "unit_cost", precision = 19, scale = 4, updatable = false)
    private BigDecimal unitCost;

    @Column(name = "reason", length = 255, updatable = false)
    private String reason;

    /** External document this came from: invoice, PO or receipt number. */
    @Column(name = "reference", length = 120, updatable = false)
    private String reference;

    /** Pairs the two legs of one transfer. Null for every other type. */
    @Column(name = "transfer_group_id", updatable = false)
    private UUID transferGroupId;

    /**
     * Client-supplied key that makes a retried write a no-op, unique per
     * tenant. Mobile clients set it so a lost response cannot double-count
     * stock.
     */
    @Column(name = "idempotency_key", length = 120, updatable = false)
    private String idempotencyKey;

    /** When the change happened in the real world, as reported by the client. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    /** When the server accepted it. Differs from occurredAt for offline sync. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;

    /** Author, or null for system-generated movements. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** Invariants the database also enforces; checked here to fail earlier. */
    public void validate() {
        if (!movementType.permits(quantity)) {
            throw new IllegalStateException(
                    "Quantity " + quantity + " has the wrong sign for " + movementType);
        }
        if (movementType.isTransferLeg() != (transferGroupId != null)) {
            throw new IllegalStateException(
                    "transferGroupId is required for transfer legs and forbidden otherwise");
        }
    }
}
