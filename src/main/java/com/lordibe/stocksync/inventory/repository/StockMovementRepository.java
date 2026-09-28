package com.lordibe.stocksync.inventory.repository;

import com.lordibe.stocksync.inventory.domain.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    /** Replay detection. Backed by {@code uk_movement_tenant_idempotency}. */
    Optional<StockMovement> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /** The authoritative on-hand figure. {@code stock_level} must always agree with this. */
    @Query("""
            select coalesce(sum(m.quantity), 0)
            from StockMovement m
            where m.tenantId = :tenantId
              and m.itemId = :itemId
              and m.locationId = :locationId
            """)
    BigDecimal sumQuantity(@Param("tenantId") UUID tenantId,
                           @Param("itemId") UUID itemId,
                           @Param("locationId") UUID locationId);

    /** Most recent entry for a slot, used when rebuilding its projection. */
    Optional<StockMovement> findFirstByTenantIdAndItemIdAndLocationIdOrderByOccurredAtDescCreatedAtDesc(
            UUID tenantId, UUID itemId, UUID locationId);

    Page<StockMovement> findByTenantIdAndItemIdOrderByOccurredAtDesc(
            UUID tenantId, UUID itemId, Pageable pageable);

    /** Both legs of a transfer. */
    List<StockMovement> findByTenantIdAndTransferGroupId(UUID tenantId, UUID transferGroupId);
}
