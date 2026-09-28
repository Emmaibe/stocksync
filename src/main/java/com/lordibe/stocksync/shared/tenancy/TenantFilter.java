package com.lordibe.stocksync.shared.tenancy;

/** Names of the Hibernate tenant filter, kept in one place. */
public final class TenantFilter {

    public static final String NAME = "tenantFilter";
    public static final String PARAMETER = "tenantId";
    public static final String CONDITION = "tenant_id = :tenantId";

    private TenantFilter() {
    }
}
