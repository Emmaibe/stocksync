package com.lordibe.stocksync.platform.email.domain;

import com.lordibe.stocksync.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One queued email.
 *
 * <p>Not tenant-scoped, deliberately: password-reset mail is written before any
 * tenant is in context, and the dispatcher runs platform-wide with no tenant
 * bound. {@code tenantId} is recorded for traceability, not for scoping — which
 * is why this extends {@link BaseEntity} and carries no tenant filter.
 */
@Entity
@Table(name = "email_message")
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class EmailMessage extends BaseEntity {

    /** Attempts before a message is abandoned. */
    public static final int MAX_ATTEMPTS = 6;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "to_address", nullable = false, length = 255)
    private String toAddress;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "body_text", nullable = false, columnDefinition = "text")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    @Column(name = "template", nullable = false, length = 60)
    private String template;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmailStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public void markSent() {
        this.status = EmailStatus.SENT;
        this.sentAt = OffsetDateTime.now();
        this.lastError = null;
    }

    /**
     * Records a failure and schedules the next attempt.
     *
     * <p>Backoff doubles each time — roughly a minute, then two, four, and so on —
     * so a mail server that is briefly down is waited out rather than hammered,
     * and a permanently bad address stops consuming attempts within the hour.
     */
    public void markFailed(String error) {
        this.attempts++;
        // Error text can be long and occasionally contains the recipient; truncate.
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        if (this.attempts >= MAX_ATTEMPTS) {
            this.status = EmailStatus.FAILED;
            return;
        }
        this.nextAttemptAt = OffsetDateTime.now()
                .plus(Duration.ofMinutes(1L << (this.attempts - 1)));
    }
}
