package com.lordibe.stocksync.iam.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a caller is allowed to do, as a stable code carried in the token.
 *
 * <p>Authorisation checks name permissions, never roles: {@code role} answers
 * "who is this person" and permissions answer "what may they do", and only the
 * second question belongs in a security rule. Today a role maps to a fixed bundle
 * (see {@link UserRole#permissions()}), so this is no more expressive than roles
 * were — but when tenants ask for custom roles ("a manager who may adjust stock
 * but not delete items"), that becomes a change to how the bundle is assembled,
 * not a rewrite of every {@code @PreAuthorize} in the codebase.
 *
 * <p>Codes are {@code domain.action} and are part of the API contract once issued
 * in a token: rename one and existing tokens stop granting it.
 */
public enum Permission {

    STOCK_READ("stock.read"),
    STOCK_RECORD("stock.record"),
    STOCK_TRANSFER("stock.transfer"),
    /** Committing stock to an order without moving it yet. */
    STOCK_RESERVE("stock.reserve"),
    /** Recomputing a projection from the ledger — an administrative repair. */
    STOCK_REBUILD("stock.rebuild"),

    /** Open a count and enter what was found on the shelf. */
    STOCK_COUNT("stock.count"),
    /**
     * Turn a finished count into stock corrections.
     *
     * <p>Separate from {@link #STOCK_COUNT} on purpose: the person who counted
     * does not approve their own adjustment. It is the one control that makes a
     * stocktake evidence rather than an assertion.
     */
    STOCK_COUNT_POST("stock.count.post"),

    ITEM_READ("item.read"),
    ITEM_CREATE("item.create"),
    ITEM_UPDATE("item.update"),
    ITEM_DELETE("item.delete"),

    LOCATION_READ("location.read"),
    LOCATION_CREATE("location.create"),
    LOCATION_UPDATE("location.update"),
    LOCATION_DELETE("location.delete"),

    USER_READ("user.read"),
    USER_INVITE("user.invite"),
    USER_UPDATE("user.update"),
    USER_DELETE("user.delete"),

    TENANT_READ("tenant.read"),
    TENANT_UPDATE("tenant.update"),
    /** Ending the tenant's life. Owner only. */
    TENANT_CLOSE("tenant.close"),
    BILLING_MANAGE("billing.manage");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<Permission> fromCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }
}
