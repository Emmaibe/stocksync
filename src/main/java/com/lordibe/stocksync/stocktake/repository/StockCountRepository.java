package com.lordibe.stocksync.stocktake.repository;

import com.lordibe.stocksync.stocktake.domain.StockCount;
import com.lordibe.stocksync.stocktake.domain.StockCountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StockCountRepository extends JpaRepository<StockCount, UUID> {

    Page<StockCount> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * The open count at a location, if there is one.
     *
     * <p>A partial unique index enforces that there is at most one, so this is a
     * check for a friendly refusal rather than the guarantee itself.
     */
    Optional<StockCount> findByLocationIdAndStatus(UUID locationId, StockCountStatus status);
}
