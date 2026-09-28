package com.lordibe.stocksync.inventory.domain;

import com.lordibe.stocksync.inventory.repository.StockLevelRepository;
import com.lordibe.stocksync.inventory.repository.StockMovementRepository;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only writer of stock.
 *
 * <p>Every quantity change in the system goes through here: it appends to the
 * append-only ledger and updates the {@code stock_level} projection in the same
 * transaction. Nothing else may touch {@code stock_level} — which is enforced,
 * not merely documented, by {@link StockLevel}'s mutators being package-private
 * to this package.
 *
 * <p>Authorisation sits on the service, not only on a controller, so any future
 * caller — a scheduled job, a message consumer, a second controller — is covered
 * by the same rule. Rules name permissions rather than roles, so a tenant-defined
 * role can be introduced later without touching them: recording stock is floor
 * work, moving it between locations is a manager's call, and rebuilding a
 * projection is an administrative repair.
 *
 * <p>The ordering inside {@link #record} is load-bearing: <em>lock the slot
 * first</em>, then check for a replay, then append. Locking first serialises
 * concurrent writes to the same item/location, so a second transaction carrying
 * a repeated idempotency key waits, re-reads under READ COMMITTED, sees the
 * committed movement and returns it instead of double-counting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMovementService {

    private final StockMovementRepository movements;
    private final StockLevelRepository stockLevels;
    private final StockSlotProvisioner slotProvisioner;
    private final InventoryProperties properties;

    /**
     * Appends a receipt, sale or adjustment and updates the projection.
     *
     * @return the movement recorded, or the one already stored under the same
     *         idempotency key
     */
    @PreAuthorize("hasAuthority('stock.record')")
    @Transactional
    public StockMovement record(RecordMovementCommand command) {
        UUID tenantId = TenantContext.require();

        StockLevel level = lockSlot(tenantId, command.itemId(), command.locationId());

        Optional<StockMovement> replay = findReplay(tenantId, command.idempotencyKey());
        if (replay.isPresent()) {
            log.debug("Idempotency key {} already recorded as movement {}",
                    command.idempotencyKey(), replay.get().getId());
            return replay.get();
        }

        StockMovement movement = StockMovement.builder()
                .itemId(command.itemId())
                .locationId(command.locationId())
                .movementType(command.movementType())
                .quantity(command.quantity())
                .unitCost(command.unitCost())
                .reason(command.reason())
                .reference(command.reference())
                .idempotencyKey(command.idempotencyKey())
                .occurredAt(command.occurredAt())
                .recordedAt(OffsetDateTime.now())
                .createdBy(command.createdBy())
                .build();
        movement.setTenantId(tenantId);

        return append(movement, level);
    }

    /**
     * Moves stock between two locations as one transaction and two ledger legs
     * sharing a {@code transfer_group_id}.
     *
     * <p>The legs are applied in a fixed order — locations sorted by id — so two
     * concurrent transfers between the same pair of locations take their row locks
     * in the same sequence and cannot deadlock against each other.
     *
     * @return both legs, outbound first
     */
    @PreAuthorize("hasAuthority('stock.transfer')")
    @Transactional
    public List<StockMovement> transfer(TransferStockCommand command) {
        UUID tenantId = TenantContext.require();
        UUID transferGroupId = UUID.randomUUID();

        Optional<StockMovement> replay = findReplay(tenantId, command.outboundKey());
        if (replay.isPresent()) {
            return movements.findByTenantIdAndTransferGroupId(tenantId, replay.get().getTransferGroupId());
        }

        record Leg(UUID locationId, MovementType type, BigDecimal quantity, String key) {
        }

        List<Leg> legs = List.of(
                new Leg(command.sourceLocationId(), MovementType.TRANSFER_OUT,
                        command.quantity().negate(), command.outboundKey()),
                new Leg(command.destinationLocationId(), MovementType.TRANSFER_IN,
                        command.quantity(), command.inboundKey()));

        // Deterministic lock order, independent of which end is the source.
        List<Leg> inLockOrder = legs.stream()
                .sorted(Comparator.comparing(Leg::locationId))
                .toList();

        List<StockMovement> recorded = new java.util.ArrayList<>(2);
        for (Leg leg : inLockOrder) {
            StockLevel level = lockSlot(tenantId, command.itemId(), leg.locationId());
            StockMovement movement = StockMovement.builder()
                    .itemId(command.itemId())
                    .locationId(leg.locationId())
                    .movementType(leg.type())
                    .quantity(leg.quantity())
                    .unitCost(command.unitCost())
                    .reason(command.reason())
                    .reference(command.reference())
                    .transferGroupId(transferGroupId)
                    .idempotencyKey(leg.key())
                    .occurredAt(command.occurredAt())
                    .recordedAt(OffsetDateTime.now())
                    .createdBy(command.createdBy())
                    .build();
            movement.setTenantId(tenantId);
            recorded.add(append(movement, level));
        }

        // Report outbound first regardless of the order the locks were taken in.
        return recorded.stream()
                .sorted(Comparator.comparing(m -> m.getMovementType() == MovementType.TRANSFER_OUT ? 0 : 1))
                .toList();
    }

    /**
     * Recomputes one slot's projection from the ledger.
     *
     * <p>The projection should never need this, which is exactly why it is worth
     * having: it repairs drift, and it is the oracle the incremental path is
     * checked against.
     */
    @PreAuthorize("hasAuthority('stock.rebuild')")
    @Transactional
    public StockLevel rebuildSlot(UUID itemId, UUID locationId) {
        UUID tenantId = TenantContext.require();
        StockLevel level = lockSlot(tenantId, itemId, locationId);
        BigDecimal fromLedger = movements.sumQuantity(tenantId, itemId, locationId);
        StockMovement last = movements
                .findFirstByTenantIdAndItemIdAndLocationIdOrderByOccurredAtDescCreatedAtDesc(
                        tenantId, itemId, locationId)
                .orElse(null);

        if (level.getQuantityOnHand().compareTo(fromLedger) != 0) {
            log.warn("Stock level drift for item {} at location {}: projected {}, ledger {}",
                    itemId, locationId, level.getQuantityOnHand(), fromLedger);
        }
        level.rebuildTo(fromLedger, last);
        return level;
    }

    /**
     * Commits stock to an order without moving it.
     *
     * <p>Goes through this service, and takes the same slot lock as a movement,
     * because reserving and selling contend for the same number: two concurrent
     * reservations that each check availability separately would happily promise
     * the same unit twice.
     */
    @PreAuthorize("hasAuthority('stock.reserve')")
    @Transactional
    public StockLevel reserve(UUID itemId, UUID locationId, BigDecimal quantity) {
        requirePositive(quantity);
        UUID tenantId = TenantContext.require();
        StockLevel level = lockSlot(tenantId, itemId, locationId);
        try {
            level.reserve(quantity);
        } catch (IllegalStateException notEnough) {
            throw new InsufficientStockException(itemId, locationId,
                    level.availableQuantity(), quantity);
        }
        return level;
    }

    /** Releases a reservation — the order was cancelled, or shipped separately. */
    @PreAuthorize("hasAuthority('stock.reserve')")
    @Transactional
    public StockLevel releaseReservation(UUID itemId, UUID locationId, BigDecimal quantity) {
        requirePositive(quantity);
        UUID tenantId = TenantContext.require();
        StockLevel level = lockSlot(tenantId, itemId, locationId);
        try {
            level.release(quantity);
        } catch (IllegalStateException tooMuch) {
            throw new InvalidMovementException(tooMuch.getMessage());
        }
        return level;
    }

    private void requirePositive(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new InvalidMovementException("Quantity must be positive");
        }
    }

    @PreAuthorize("hasAuthority('stock.read')")
    @Transactional(readOnly = true)
    public BigDecimal onHand(UUID itemId, UUID locationId) {
        UUID tenantId = TenantContext.require();
        return stockLevels.findByTenantIdAndItemIdAndLocationId(tenantId, itemId, locationId)
                .map(StockLevel::getQuantityOnHand)
                .orElse(BigDecimal.ZERO);
    }

    /** Persists the entry and folds it into the projection. */
    private StockMovement append(StockMovement movement, StockLevel level) {
        movement.validate();
        guardAgainstNegativeStock(level, movement.getQuantity());
        StockMovement saved = movements.save(movement);
        // Flushed so the id exists before the projection points at it.
        movements.flush();
        level.apply(saved);
        return saved;
    }

    private void guardAgainstNegativeStock(StockLevel level, BigDecimal delta) {
        if (properties.allowNegativeStock()) {
            return;
        }
        if (level.getQuantityOnHand().add(delta).signum() < 0) {
            throw new InsufficientStockException(
                    level.getItemId(), level.getLocationId(), level.getQuantityOnHand(), delta);
        }
    }

    /** Locks the slot, creating it first if this is its first movement. */
    private StockLevel lockSlot(UUID tenantId, UUID itemId, UUID locationId) {
        return stockLevels.lockSlot(tenantId, itemId, locationId)
                .orElseGet(() -> {
                    slotProvisioner.ensureSlotExists(tenantId, itemId, locationId);
                    return stockLevels.lockSlot(tenantId, itemId, locationId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Slot for item %s at location %s vanished after provisioning"
                                            .formatted(itemId, locationId)));
                });
    }

    private Optional<StockMovement> findReplay(UUID tenantId, String idempotencyKey) {
        return idempotencyKey == null
                ? Optional.empty()
                : movements.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    }
}
