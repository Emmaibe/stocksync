package com.lordibe.stocksync.inventory.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param allowNegativeStock when true, a sale or transfer may drive a slot below
 *                           zero. Off by default. This is checked in the service
 *                           rather than by a database constraint on purpose:
 *                           backdated offline movements can legitimately make an
 *                           intermediate sum negative, so the rule belongs to the
 *                           write path, not to the ledger's own integrity.
 */
@ConfigurationProperties(prefix = "stocksync.inventory")
public record InventoryProperties(@DefaultValue("false") boolean allowNegativeStock) {
}
