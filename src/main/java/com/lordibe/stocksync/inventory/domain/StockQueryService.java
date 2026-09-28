package com.lordibe.stocksync.inventory.domain;

import com.lordibe.stocksync.inventory.repository.StockLevelRepository;
import com.lordibe.stocksync.inventory.repository.StockMovementRepository;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the inventory module.
 *
 * <p>Separate from {@link StockMovementService} so the one class that may write
 * stock stays exactly that, and so reads can later move to a replica or a cache
 * without disturbing the write path.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockQueryService {

    private final StockLevelRepository stockLevels;
    private final StockMovementRepository movements;

    @PreAuthorize("hasAuthority('stock.read')")
    public Optional<StockLevel> levelAt(UUID itemId, UUID locationId) {
        return stockLevels.findByTenantIdAndItemIdAndLocationId(
                TenantContext.require(), itemId, locationId);
    }

    /** Every location holding this item. */
    @PreAuthorize("hasAuthority('stock.read')")
    public List<StockLevel> levelsForItem(UUID itemId) {
        return stockLevels.findByTenantIdAndItemId(TenantContext.require(), itemId);
    }

    /** Items at or below their reorder point — the reason most people open a stock app. */
    @PreAuthorize("hasAuthority('stock.read')")
    public List<StockLevel> belowReorderPoint() {
        return stockLevels.findBelowReorderPoint(TenantContext.require());
    }

    /** An item's ledger history, newest first. */
    @PreAuthorize("hasAuthority('stock.read')")
    public Page<StockMovement> historyFor(UUID itemId, Pageable pageable) {
        return movements.findByTenantIdAndItemIdOrderByOccurredAtDesc(
                TenantContext.require(), itemId, pageable);
    }
}
