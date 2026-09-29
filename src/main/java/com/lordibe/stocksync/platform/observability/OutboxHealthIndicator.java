package com.lordibe.stocksync.platform.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether queued email is moving.
 *
 * <p>This is the failure nobody notices. The outbox is deliberately tolerant:
 * SMTP can be down for an hour and no request fails, which is the right
 * behaviour and also means a permanently broken mail configuration looks
 * exactly like a healthy one from the outside. Invitations and password resets
 * simply never arrive, and the first report comes from a confused user.
 *
 * <p>Two conditions make it DOWN:
 *
 * <ul>
 *   <li><b>Any FAILED message.</b> Nothing retries these, so the count only ever
 *       grows. One is a signal; it does not need a threshold.</li>
 *   <li><b>The oldest waiting message is older than the stall window.</b> The
 *       dispatcher runs every 30 seconds, so anything waiting 15 minutes is not
 *       waiting for the dispatcher — it is failing and backing off, or the
 *       dispatcher is not running at all.</li>
 * </ul>
 *
 * <p><b>This is registered under the {@code ops} health group, never under the
 * readiness probe.</b> A mail backlog must not take pods out of the load
 * balancer: the API is still perfectly able to record stock, and removing the
 * pods would turn a mail problem into an outage. Wiring a business-level check
 * into readiness is a common and expensive mistake.
 */
@Component("outbox")
class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxStats stats;
    private final long stalledAfterMinutes;

    OutboxHealthIndicator(OutboxStats stats,
                          @Value("${stocksync.email.stalled-after-minutes:15}") long stalledAfterMinutes) {
        this.stats = stats;
        this.stalledAfterMinutes = stalledAfterMinutes;
    }

    @Override
    public Health health() {
        long pending = stats.pending();
        long failed = stats.failed();
        long waiting = stats.oldestPendingMinutes();

        boolean stalled = waiting > stalledAfterMinutes;
        Health.Builder builder = (failed > 0 || stalled) ? Health.down() : Health.up();

        return builder
                .withDetail("pending", pending)
                .withDetail("failed", failed)
                .withDetail("oldestPendingMinutes", waiting)
                .withDetail("stalledAfterMinutes", stalledAfterMinutes)
                .withDetail("reason", reason(failed, stalled))
                .build();
    }

    private String reason(long failed, boolean stalled) {
        if (failed > 0 && stalled) {
            return "messages have been abandoned and the queue is not draining";
        }
        if (failed > 0) {
            return "messages were abandoned after the attempt limit; check last_error on email_message";
        }
        if (stalled) {
            return "the queue is not draining; check SMTP credentials and whether the dispatcher is running";
        }
        return "queue is draining";
    }
}
