package com.lordibe.stocksync.platform.email.service;

import com.lordibe.stocksync.platform.email.domain.EmailMessage;
import com.lordibe.stocksync.platform.email.domain.EmailStatus;
import com.lordibe.stocksync.platform.email.repository.EmailMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Queues mail inside the caller's transaction.
 *
 * <p>Deliberately {@code MANDATORY}-shaped in spirit: callers enqueue while doing
 * the work that justifies the email, so the two commit or roll back together. An
 * invitation is never created without its mail, and mail is never sent for an
 * invitation that failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOutbox {

    private final EmailMessageRepository messages;

    @Transactional
    public EmailMessage enqueue(UUID tenantId, String toAddress, String template,
                                EmailTemplates.Rendered rendered) {
        EmailMessage message = messages.save(EmailMessage.builder()
                .tenantId(tenantId)
                .toAddress(toAddress)
                .subject(rendered.subject())
                .bodyText(rendered.text())
                .bodyHtml(rendered.html())
                .template(template)
                .status(EmailStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(OffsetDateTime.now())
                .build());
        log.debug("Queued {} email to {}", template, toAddress);
        return message;
    }
}
