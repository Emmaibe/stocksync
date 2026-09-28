package com.lordibe.stocksync.shared.security;

/** Custom claim names, in one place so the issuer and the readers cannot drift. */
public final class JwtClaims {

    /** The tenant the caller belongs to. Drives {@code TenantContext}. */
    public static final String TENANT_ID = "tenant_id";

    /** Convenience for clients; never trusted for authorisation. */
    public static final String TENANT_SLUG = "tenant_slug";

    /** Single role per user within a tenant. Descriptive; not what rules check. */
    public static final String ROLE = "role";

    /**
     * The permission codes the role grants, and the authorities the application
     * actually authorises on.
     */
    public static final String PERMISSIONS = "permissions";

    /** Distinguishes an access token from a refresh token. */
    public static final String TOKEN_TYPE = "typ";

    /** The user's token version at issue time; a stale value is refused at refresh. */
    public static final String TOKEN_VERSION = "tver";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private JwtClaims() {
    }
}
