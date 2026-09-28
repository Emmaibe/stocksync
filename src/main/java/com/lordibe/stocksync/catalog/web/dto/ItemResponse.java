package com.lordibe.stocksync.catalog.web.dto;

import com.lordibe.stocksync.catalog.domain.Item;
import com.lordibe.stocksync.catalog.domain.UnitOfMeasure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ItemResponse(
        UUID id,
        String sku,
        String barcode,
        String name,
        String description,
        String category,
        UnitOfMeasure unit,
        BigDecimal costPrice,
        BigDecimal salePrice,
        BigDecimal reorderPoint,
        boolean tracked,
        boolean active,
        Map<String, Object> attributes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ItemResponse from(Item item) {
        return new ItemResponse(item.getId(), item.getSku(), item.getBarcode(), item.getName(),
                item.getDescription(), item.getCategory(), item.getUnit(), item.getCostPrice(),
                item.getSalePrice(), item.getReorderPoint(), item.isTracked(), item.isActive(),
                item.getAttributes(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
