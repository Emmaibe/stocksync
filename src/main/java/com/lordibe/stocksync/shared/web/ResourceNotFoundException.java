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

    /**
     * The id is deliberately not in the message. A caller who sees "Item
     * 0a2a00bc-a0f7-1e39 was not found" learns nothing they can act on, and the
     * id is already in their own request. Logs keep it.
     */
    public ResourceNotFoundException(String type, UUID id) {
        super("That " + type.toLowerCase(java.util.Locale.ROOT) + " could not be found.");
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
