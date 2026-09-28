package com.lordibe.stocksync.platform.email.transport;

import com.lordibe.stocksync.platform.email.domain.EmailMessage;

/**
 * How a message actually leaves the building.
 *
 * <p>An interface with two implementations rather than a direct
 * {@code JavaMailSender} call, so the application runs with no mail server
 * configured — which is what every developer machine and every test looks like —
 * and so a hosted provider (SES, SendGrid) can be added later without touching
 * the outbox or the dispatcher.
 */
public interface EmailTransport {

    /**
     * @throws org.springframework.mail.MailException when delivery fails; the
     *         dispatcher treats any exception as a retryable failure
     */
    void send(EmailMessage message);

    /** For logging at startup, so it is obvious which one is active. */
    String describe();
}
