package com.lordibe.stocksync.shared.tenancy;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enables the tenant filter on the Hibernate session backing the current
 * persistence context.
 *
 * <p>Called once per unit of work, after {@link TenantContext} is populated: by
 * the authentication filter for HTTP requests, and by the job runner for
 * scheduled work. The filter lives on the session, so it must be re-enabled for
 * each new session rather than configured once at startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantFilterActivator {

    private final EntityManager entityManager;

    /** Activates the filter for the tenant currently in context. */
    public void activate() {
        activateFor(TenantContext.require());
    }

    public void activateFor(UUID tenantId) {
        entityManager.unwrap(Session.class)
                .enableFilter(TenantFilter.NAME)
                .setParameter(TenantFilter.PARAMETER, tenantId);
        log.trace("Tenant filter enabled for {}", tenantId);
    }
}
