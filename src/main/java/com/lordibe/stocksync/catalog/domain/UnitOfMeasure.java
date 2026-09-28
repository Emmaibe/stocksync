package com.lordibe.stocksync.catalog.domain;

/**
 * How an item is counted. Fixed on purpose: quantities are compared and summed
 * across movements, so the unit cannot be free text.
 */
public enum UnitOfMeasure {
    EACH,
    BOX,
    PACK,
    KG,
    GRAM,
    LITRE,
    MILLILITRE,
    METRE
}
