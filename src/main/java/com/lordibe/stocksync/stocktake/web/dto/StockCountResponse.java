package com.lordibe.stocksync.stocktake.web.dto;

import com.lordibe.stocksync.stocktake.domain.StockCount;
import com.lordibe.stocksync.stocktake.domain.StockCountStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StockCountResponse(
        UUID id,
        UUID locationId,
        StockCountStatus status,
        String reference,
        String note,
        UUID openedBy,
        UUID postedBy,
        OffsetDateTime postedAt,
        OffsetDateTime createdAt
) {

    public static StockCountResponse from(StockCount count) {
        return new StockCountResponse(
                count.getId(),
                count.getLocationId(),
                count.getStatus(),
                count.getReference(),
                count.getNote(),
                count.getOpenedBy(),
                count.getPostedBy(),
                count.getPostedAt(),
                count.getCreatedAt());
    }
}
