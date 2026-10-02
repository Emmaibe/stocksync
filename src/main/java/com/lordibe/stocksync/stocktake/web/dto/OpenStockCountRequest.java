package com.lordibe.stocksync.stocktake.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record OpenStockCountRequest(
        @NotNull UUID locationId,
        @Size(max = 120) String reference,
        String note
) {
}
