package com.lordibe.stocksync.platform.observability;

import com.lordibe.stocksync.platform.email.domain.EmailStatus;
import com.lordibe.stocksync.platform.email.repository.EmailMessageRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counts the outbox, with a short cache in front.
 *
 * <p>The cache is not an optimisation for its own sake. These numbers are read
 * by a metrics gauge, and a gauge is sampled every time anything scrapes
 * {@code /actuator/metrics} — without a cache, a monitoring tool polling every
 * few seconds would put three aggregate queries per poll onto the same database
 * the application is trying to serve requests from.
 *
 * <p>Ten seconds of staleness is invisible for a backlog measured in minutes.
 */
@Component
class JpaOutboxStats implements OutboxStats {

    private static final Duration CACHE_TTL = Duration.ofSeconds(10);

    private final EmailMessageRepository repository;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>();

    JpaOutboxStats(EmailMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    public long pending() {
        return current().pending;
    }

    @Override
    public long failed() {
        return current().failed;
    }

    @Override
    public long oldestPendingMinutes() {
        return current().oldestPendingMinutes;
    }

    private Snapshot current() {
        Snapshot snapshot = cached.get();
        OffsetDateTime now = OffsetDateTime.now();
        if (snapshot != null && snapshot.takenAt.plus(CACHE_TTL).isAfter(now)) {
            return snapshot;
        }

        long pending = repository.countByStatus(EmailStatus.PENDING);
        long failed = repository.countByStatus(EmailStatus.FAILED);

        OffsetDateTime oldest = pending == 0 ? null : repository.oldestPendingCreatedAt();
        long waitingMinutes = oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMinutes());

        Snapshot fresh = new Snapshot(pending, failed, waitingMinutes, now);
        // Plain set, not compareAndSet: two threads racing here both computed
        // from the same database a moment apart, so either answer is correct.
        cached.set(fresh);
        return fresh;
    }

    private record Snapshot(long pending, long failed, long oldestPendingMinutes, OffsetDateTime takenAt) {
    }
}
