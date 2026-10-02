package com.lordibe.stocksync.iam.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Roles within a single tenant, ordered from most to least privileged.
 *
 * <p>A role is a named bundle of {@link Permission}s and nothing more. Security
 * rules check the permissions, so replacing these fixed bundles with per-tenant
 * custom roles later changes only how {@link #permissions()} is resolved.
 *
 * <p>Each bundle includes the one below it, so the levels stay genuinely
 * cumulative and a rule never has to list several roles.
 */
public enum UserRole {

    /** Created the tenant; the only role that can close it or manage billing. */
    OWNER,
    /** Full access to configuration and users. */
    ADMIN,
    /** Runs day-to-day inventory: items, locations, movements, reports. */
    MANAGER,
    /** Records movements on the floor. No configuration access. */
    STAFF;

    private static final Set<Permission> STAFF_PERMISSIONS = EnumSet.of(
            Permission.STOCK_READ,
            Permission.STOCK_RECORD,
            Permission.ITEM_READ,
            Permission.LOCATION_READ,
            // Counting is floor work; approving the correction is not.
            Permission.STOCK_COUNT);

    private static final Set<Permission> MANAGER_PERMISSIONS = union(STAFF_PERMISSIONS, EnumSet.of(
            Permission.STOCK_TRANSFER,
            Permission.STOCK_RESERVE,
            Permission.STOCK_COUNT_POST,
            Permission.ITEM_CREATE,
            Permission.ITEM_UPDATE,
            Permission.LOCATION_CREATE,
            Permission.LOCATION_UPDATE,
            Permission.USER_READ));

    private static final Set<Permission> ADMIN_PERMISSIONS = union(MANAGER_PERMISSIONS, EnumSet.of(
            Permission.STOCK_REBUILD,
            Permission.ITEM_DELETE,
            Permission.LOCATION_DELETE,
            Permission.USER_INVITE,
            Permission.USER_UPDATE,
            Permission.TENANT_READ));

    private static final Set<Permission> OWNER_PERMISSIONS = union(ADMIN_PERMISSIONS, EnumSet.of(
            Permission.USER_DELETE,
            Permission.TENANT_UPDATE,
            Permission.TENANT_CLOSE,
            Permission.BILLING_MANAGE));

    /** The permissions this role grants. Immutable. */
    public Set<Permission> permissions() {
        return switch (this) {
            case OWNER -> OWNER_PERMISSIONS;
            case ADMIN -> ADMIN_PERMISSIONS;
            case MANAGER -> MANAGER_PERMISSIONS;
            case STAFF -> STAFF_PERMISSIONS;
        };
    }

    public Set<String> permissionCodes() {
        return permissions().stream()
                .map(Permission::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<Permission> union(Set<Permission> inherited, Set<Permission> added) {
        EnumSet<Permission> all = EnumSet.copyOf(inherited);
        all.addAll(added);
        return java.util.Collections.unmodifiableSet(all);
    }
}
