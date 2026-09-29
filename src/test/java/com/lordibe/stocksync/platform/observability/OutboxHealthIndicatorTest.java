package com.lordibe.stocksync.platform.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thresholds, tested without a database. What matters here is which
 * combinations are treated as broken, and that is pure arithmetic.
 */
class OutboxHealthIndicatorTest {

    @Test
    @DisplayName("an empty queue is healthy")
    void emptyQueueIsUp() {
        Health health = indicator(0, 0, 0).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("mail waiting a short time is healthy — that is the queue working")
    void recentBacklogIsUp() {
        // Forty messages queued two minutes ago is a burst being worked through,
        // not a fault. Alerting on this would train everyone to ignore alerts.
        Health health = indicator(40, 0, 2).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("pending", 40L);
    }

    @Test
    @DisplayName("a single abandoned message is enough to report DOWN")
    void anyFailedMessageIsDown() {
        // Nothing retries a FAILED message, so the count only ever grows. There
        // is no healthy number of them above zero.
        Health health = indicator(0, 1, 0).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason").toString()).contains("abandoned");
    }

    @Test
    @DisplayName("a queue that stops draining reports DOWN even with nothing failed yet")
    void stalledQueueIsDown() {
        // This is the shape of a wrong SMTP password: everything still PENDING,
        // nothing FAILED yet because the attempt limit has not been reached, and
        // no user has complained. Caught here rather than in a week.
        Health health = indicator(3, 0, 45).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason").toString()).contains("not draining");
    }

    @Test
    @DisplayName("the stall window is exclusive, so a message exactly at the edge is not yet a fault")
    void ageEqualToTheWindowIsUp() {
        Health health = indicator(1, 0, 15).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    private OutboxHealthIndicator indicator(long pending, long failed, long oldestMinutes) {
        return new OutboxHealthIndicator(new OutboxStats() {
            @Override public long pending() { return pending; }
            @Override public long failed() { return failed; }
            @Override public long oldestPendingMinutes() { return oldestMinutes; }
        }, 15);
    }
}
