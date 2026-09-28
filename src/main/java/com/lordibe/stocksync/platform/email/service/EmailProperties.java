package com.lordibe.stocksync.platform.email.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param fromAddress envelope sender for everything this service sends
 * @param appBaseUrl  where the links in emails point — the web app, not this API.
 *                    Wrong here means every invitation link is broken, so it has
 *                    no silent default beyond localhost for development.
 */
@ConfigurationProperties(prefix = "stocksync.email")
public record EmailProperties(
        @DefaultValue("no-reply@stocksync.local") String fromAddress,
        @DefaultValue("StockSync") String fromName,
        @DefaultValue("http://localhost:3000") String appBaseUrl,
        @DefaultValue("25") int dispatchBatchSize
) {
}
