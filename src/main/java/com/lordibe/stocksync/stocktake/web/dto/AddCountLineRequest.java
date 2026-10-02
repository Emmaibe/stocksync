package com.lordibe.stocksync.stocktake.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddCountLineRequest(@NotNull UUID itemId) {
}
