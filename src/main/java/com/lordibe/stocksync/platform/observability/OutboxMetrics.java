package com.lordibe.stocksync.platform.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Publishes the outbox numbers as metrics, so they can be charted over time
 * rather than only checked in the moment.
 *
 * <p>A health check answers "is it broken now". A metric answers "when did it
 * start", which is the question asked during an incident. Registering these
 * costs nothing while nothing scrapes them, and means no code change is needed
 * the day a dashboard exists.
 *
 * <p>Gauges are sampled on read, not pushed, which is why {@link OutboxStats}
 * caches: the sampling rate is decided by whatever is scraping, not by us.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(MeterRegistry registry, OutboxStats stats) {
        io.micrometer.core.instrument.Gauge
                .builder("stocksync.email.outbox.pending", stats, OutboxStats::pending)
                .description("Queued emails waiting to be sent, including ones backing off after a failure")
                .baseUnit("messages")
                .register(registry);

        io.micrometer.core.instrument.Gauge
                .builder("stocksync.email.outbox.failed", stats, OutboxStats::failed)
                .description("Emails abandoned after the attempt limit; nothing retries these")
                .baseUnit("messages")
                .register(registry);

        io.micrometer.core.instrument.Gauge
                .builder("stocksync.email.outbox.oldest.pending.minutes", stats, OutboxStats::oldestPendingMinutes)
                .description("Age of the oldest email still waiting to be sent")
                .baseUnit("minutes")
                .register(registry);
    }
}
