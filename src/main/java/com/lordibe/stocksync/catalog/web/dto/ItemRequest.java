package com.lordibe.stocksync.catalog.web.dto;

import com.lordibe.stocksync.catalog.domain.UnitOfMeasure;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Create or update an item.
 *
 * <p>Quantities are absent by design: stock is never set directly, only moved
 * through the ledger.
 */
public record ItemRequest(
        @NotBlank @Size(max = 64) String sku,
        @Size(max = 64) String barcode,
        @NotBlank @Size(max = 200) String name,
        String description,
        @Size(max = 100) String category,
        UnitOfMeasure unit,
        @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal costPrice,
        @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal salePrice,
        @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal reorderPoint,
        Boolean tracked,
        Map<String, Object> attributes
) {
}
