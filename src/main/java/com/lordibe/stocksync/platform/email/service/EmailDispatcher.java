package com.lordibe.stocksync.platform.email.service;

import com.lordibe.stocksync.platform.email.domain.EmailMessage;
import com.lordibe.stocksync.platform.email.repository.EmailMessageRepository;
import com.lordibe.stocksync.platform.email.transport.EmailTransport;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Delivers what the outbox has queued.
 *
 * <p>Runs every 30 seconds, claiming a batch with {@code SKIP LOCKED} so several
 * replicas cooperate rather than collide. A send failure is recorded on the row
 * and retried with widening backoff; after {@link EmailMessage#MAX_ATTEMPTS} the
 * message is marked FAILED and left alone for a human to look at.
 *
 * <p>One transaction covers the whole batch: a message that throws is marked
 * failed rather than rolling the batch back, because the send has already
 * happened as far as the mail server is concerned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDispatcher {

    private final EmailMessageRepository messages;
    private final EmailTransport transport;
    private final EmailProperties properties;

    @PostConstruct
    void announceTransport() {
        // Worth a startup line: "why did nobody get the email" is otherwise a
        // long afternoon.
        log.info("Email delivery: {}", transport.describe());
    }

    @Scheduled(fixedDelayString = "${stocksync.email.dispatch-interval-ms:30000}")
    @Transactional
    public void dispatchPending() {
        List<EmailMessage> due = messages.claimDue(
                OffsetDateTime.now(), Limit.of(properties.dispatchBatchSize()));
        if (due.isEmpty()) {
            return;
        }

        int sent = 0;
        int failed = 0;
        for (EmailMessage message : due) {
            try {
                transport.send(message);
                message.markSent();
                sent++;
            } catch (RuntimeException failure) {
                message.markFailed(failure.getMessage());
                failed++;
                log.warn("Email {} to {} failed on attempt {}: {}",
                        message.getId(), message.getToAddress(), message.getAttempts(),
                        failure.getMessage());
            }
        }
        log.info("Email dispatch: {} sent, {} failed", sent, failed);
    }
}
