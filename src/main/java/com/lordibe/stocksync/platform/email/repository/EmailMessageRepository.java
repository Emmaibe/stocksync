package com.lordibe.stocksync.platform.email.repository;

import com.lordibe.stocksync.platform.email.domain.EmailMessage;
import com.lordibe.stocksync.platform.email.domain.EmailStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, UUID> {

    /**
     * Claims a batch of due messages.
     *
     * <p>Locked with {@code SKIP LOCKED}, so several replicas can run the
     * dispatcher at once and each takes a different batch instead of colliding —
     * the one piece of scheduled work here that is safe to run everywhere.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select m from EmailMessage m
            where m.status = com.lordibe.stocksync.platform.email.domain.EmailStatus.PENDING
              and m.nextAttemptAt <= :now
            order by m.nextAttemptAt asc
            """)
    List<EmailMessage> claimDue(@Param("now") OffsetDateTime now, Limit limit);

    List<EmailMessage> findByToAddressAndStatusOrderByCreatedAtDesc(String toAddress, EmailStatus status);

    long countByTemplateAndStatus(String template, EmailStatus status);
}
