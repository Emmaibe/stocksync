package com.lordibe.stocksync.stocktake.repository;

import com.lordibe.stocksync.stocktake.domain.StockCountLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockCountLineRepository extends JpaRepository<StockCountLine, UUID> {

    List<StockCountLine> findByStockCountIdOrderByCreatedAtAsc(UUID stockCountId);

    Optional<StockCountLine> findByStockCountIdAndItemId(UUID stockCountId, UUID itemId);

    boolean existsByStockCountIdAndItemId(UUID stockCountId, UUID itemId);
}
