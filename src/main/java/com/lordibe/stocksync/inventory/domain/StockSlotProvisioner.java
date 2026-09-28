package com.lordibe.stocksync.inventory.domain;

import com.lordibe.stocksync.inventory.repository.StockLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creates the {@code stock_level} row for an item/location slot the first time
 * anything moves there.
 *
 * <p>Deliberately a separate bean with its own transaction. Two concurrent first
 * movements for the same slot both find nothing and both insert; one loses on
 * {@code uk_stock_level_slot}. Running in {@code REQUIRES_NEW} means that loss
 * is contained here — the caller's transaction is never marked rollback-only, so
 * it can simply lock the row the winner created and carry on.
 */
@Component
@RequiredArgsConstructor
public class StockSlotProvisioner {

    private final StockLevelRepository stockLevels;

    /** Inserts a zeroed slot, treating "someone else got there first" as success. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureSlotExists(UUID tenantId, UUID itemId, UUID locationId) {
        if (stockLevels.findByTenantIdAndItemIdAndLocationId(tenantId, itemId, locationId).isPresent()) {
            return;
        }
        try {
            stockLevels.saveAndFlush(StockLevel.emptySlot(tenantId, itemId, locationId));
        } catch (DataIntegrityViolationException lostTheRace) {
            // The winner's row is what we wanted to exist. Nothing to do.
        }
    }
}
