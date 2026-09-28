package com.lordibe.stocksync.shared.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * The tenant the current unit of work belongs to.
 *
 * <p>Populated at the edge from the {@code tenant_id} claim of the caller's JWT
 * and cleared when the request finishes. Background jobs set it explicitly with
 * {@link #runAs(UUID, Runnable)} before touching tenant data.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * @throws MissingTenantContextException when no tenant is bound, which is
     *         always a bug rather than a client error: fail closed instead of
     *         querying across tenants.
     */
    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new MissingTenantContextException();
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code work} bound to {@code tenantId}, restoring the previous binding. */
    public static void runAs(UUID tenantId, Runnable work) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            work.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
