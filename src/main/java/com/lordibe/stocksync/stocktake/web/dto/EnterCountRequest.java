package com.lordibe.stocksync.stocktake.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * What was found on the shelf.
 *
 * <p>Required, and never defaulted: a missing count means nobody looked, and the
 * difference between that and a counted zero is the difference between leaving
 * stock alone and writing it off.
 */
public record EnterCountRequest(
        @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal countedQuantity,
        @Size(max = 255) String note
) {
}
