package com.lordibe.stocksync.inventory.repository;

import com.lordibe.stocksync.inventory.domain.StockLevel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    /**
     * Takes a row-level write lock on one item/location slot, serialising
     * concurrent movements against it.
     *
     * <p>Locking before reading is what makes the whole write path safe: a second
     * transaction waits here, then re-reads under READ COMMITTED and sees the
     * first one's committed movement — which is how a concurrent replay of the
     * same idempotency key is caught rather than double-counted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select sl from StockLevel sl
            where sl.tenantId = :tenantId
              and sl.itemId = :itemId
              and sl.locationId = :locationId
            """)
    Optional<StockLevel> lockSlot(@Param("tenantId") UUID tenantId,
                                  @Param("itemId") UUID itemId,
                                  @Param("locationId") UUID locationId);

    Optional<StockLevel> findByTenantIdAndItemIdAndLocationId(UUID tenantId, UUID itemId, UUID locationId);

    List<StockLevel> findByTenantIdAndItemId(UUID tenantId, UUID itemId);

    List<StockLevel> findByTenantIdAndLocationId(UUID tenantId, UUID locationId);

    /** Items at or below their reorder point, across all locations. */
    @Query("""
            select sl from StockLevel sl, Item i
            where i.id = sl.itemId
              and i.tenantId = sl.tenantId
              and sl.tenantId = :tenantId
              and i.reorderPoint is not null
              and sl.quantityOnHand <= i.reorderPoint
            """)
    List<StockLevel> findBelowReorderPoint(@Param("tenantId") UUID tenantId);
}
