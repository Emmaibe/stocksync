package com.lordibe.stocksync.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's apparent IP address, for rate limiting.
 *
 * <p>{@code X-Forwarded-For} is honoured because this runs behind an ingress that
 * sets it. That header is trivially forged by a client talking to the service
 * directly, so this is only sound while the service is not directly reachable —
 * worth stating plainly, because an IP-keyed limit that a caller can vary at will
 * is no limit at all.
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Left-most entry is the original client; the rest are proxies.
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
