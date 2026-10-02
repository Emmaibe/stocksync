package com.lordibe.stocksync.stocktake.web.dto;

import com.lordibe.stocksync.stocktake.domain.StockCountLine;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param expectedQuantity what the system believed when the sheet was produced
 * @param countedQuantity  what was found, or null if nobody has looked yet
 * @param systemQuantity   what the system believed when the count was posted;
 *                         null until then. Differs from expected when stock
 *                         moved during the count
 * @param variance         counted minus expected, for the sheet. Null until counted
 * @param movementId       the correction this line produced, if any
 */
public record StockCountLineResponse(
        UUID id,
        UUID itemId,
        BigDecimal expectedQuantity,
        BigDecimal countedQuantity,
        BigDecimal systemQuantity,
        BigDecimal variance,
        UUID movementId,
        String note
) {

    public static StockCountLineResponse from(StockCountLine line) {
        return new StockCountLineResponse(
                line.getId(),
                line.getItemId(),
                line.getExpectedQuantity(),
                line.getCountedQuantity(),
                line.getSystemQuantity(),
                line.variance(),
                line.getMovementId(),
                line.getNote());
    }
}
