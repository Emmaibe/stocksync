package com.lordibe.stocksync.shared.tenancy;

/** Raised when tenant-scoped work is attempted with no tenant bound. */
public class MissingTenantContextException extends IllegalStateException {

    public MissingTenantContextException() {
        super("No tenant bound to the current context; refusing to run tenant-scoped work");
    }
}
