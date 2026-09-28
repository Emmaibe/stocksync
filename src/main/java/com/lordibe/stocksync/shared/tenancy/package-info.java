/**
 * Tenant isolation plumbing.
 *
 * <p>The filter is declared here, once, at package level. Concrete entities
 * carry {@code @Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")}
 * so Hibernate narrows every query — including {@code find} by primary key,
 * thanks to {@code applyToLoadByKey} — to the tenant in context.
 *
 * <p>The filter is a safety net, not the authorisation model: services still
 * scope their queries explicitly. It exists so that a forgotten {@code where}
 * clause leaks nothing.
 */
@FilterDef(
        name = TenantFilter.NAME,
        parameters = @ParamDef(name = TenantFilter.PARAMETER, type = UUID.class),
        applyToLoadByKey = true
)
package com.lordibe.stocksync.shared.tenancy;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.UUID;
