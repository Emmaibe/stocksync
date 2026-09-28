package com.lordibe.stocksync.shared.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param requireTenantContext when true, tenant-scoped work with no tenant
 *                             bound fails instead of proceeding unfiltered.
 */
@ConfigurationProperties(prefix = "stocksync.tenancy")
public record TenancyProperties(@DefaultValue("true") boolean requireTenantContext) {
}
