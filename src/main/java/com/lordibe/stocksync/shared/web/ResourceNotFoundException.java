package com.lordibe.stocksync.shared.web;

import java.util.UUID;

/**
 * The named resource does not exist <em>for this tenant</em>.
 *
 * <p>Another tenant's row is reported as missing rather than forbidden: telling a
 * caller "that exists but is not yours" confirms an id they should not be able to
 * probe for.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String type, UUID id) {
        super(type + " " + id + " was not found");
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
