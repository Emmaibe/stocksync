package com.lordibe.stocksync.platform;

import com.lordibe.stocksync.platform.email.domain.EmailMessage;
import com.lordibe.stocksync.platform.email.domain.EmailStatus;
import com.lordibe.stocksync.platform.email.repository.EmailMessageRepository;
import com.lordibe.stocksync.platform.email.service.EmailDispatcher;
import com.lordibe.stocksync.platform.email.service.EmailOutbox;
import com.lordibe.stocksync.platform.email.service.EmailTemplates;
import com.lordibe.stocksync.platform.email.transport.EmailTransport;
import com.lordibe.stocksync.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The outbox and the thing that drains it. */
class EmailDispatchIT extends AbstractPostgresIntegrationTest {

    @Autowired EmailOutbox outbox;
    @Autowired EmailDispatcher dispatcher;
    @Autowired EmailMessageRepository emails;
    @Autowired EmailTemplates templates;
    @Autowired EmailTransport transport;

    @Test
    @DisplayName("with no mail server configured the logging transport is the one wired in")
    void loggingTransportIsTheDefault() {
        // Tests set no spring.mail.host, so this is what a laptop and CI get —
        // and the reason none of these tests need an SMTP server.
        assertThat(transport.describe()).contains("logging only");
    }

    @Test
    @DisplayName("a queued message is delivered and marked sent")
    void dispatcherDrainsTheQueue() {
        EmailMessage queued = outbox.enqueue(null, unique(), EmailTemplates.PASSWORD_RESET,
                templates.passwordReset("a-token", Duration.ofMinutes(30)));
        assertThat(queued.getStatus()).isEqualTo(EmailStatus.PENDING);

        dispatcher.dispatchPending();

        EmailMessage after = emails.findById(queued.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(after.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("a message not yet due is left alone")
    void backoffIsRespected() {
        EmailMessage queued = outbox.enqueue(null, unique(), EmailTemplates.PASSWORD_RESET,
                templates.passwordReset("a-token", Duration.ofMinutes(30)));
        queued.setNextAttemptAt(OffsetDateTime.now().plusMinutes(5));
        emails.saveAndFlush(queued);

        dispatcher.dispatchPending();

        assertThat(emails.findById(queued.getId()).orElseThrow().getStatus())
                .isEqualTo(EmailStatus.PENDING);
    }

    @Test
    @DisplayName("failures widen the retry gap and stop after the attempt limit")
    void failuresBackOffThenGiveUp() {
        EmailMessage message = outbox.enqueue(null, unique(), EmailTemplates.INVITATION,
                templates.invitation("Acme", "Ann", "tok", Duration.ofDays(7)));

        message.markFailed("connection refused");
        OffsetDateTime afterFirst = message.getNextAttemptAt();
        message.markFailed("connection refused");

        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(message.getNextAttemptAt()).isAfter(afterFirst);

        while (message.getStatus() == EmailStatus.PENDING) {
            message.markFailed("connection refused");
        }
        assertThat(message.getAttempts()).isEqualTo(EmailMessage.MAX_ATTEMPTS);
        assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(message.getLastError()).isEqualTo("connection refused");
    }

    @Test
    @DisplayName("both a text and an HTML body are produced, and names are escaped in the HTML")
    void messagesCarryTextAndHtml() {
        var rendered = templates.invitation("Acme <script>", "Ann", "tok", Duration.ofDays(7));

        assertThat(rendered.text()).contains("accept-invitation?token=tok");
        assertThat(rendered.html()).contains("&lt;script&gt;").doesNotContain("<script>");
    }

    private String unique() {
        return "someone-" + UUID.randomUUID() + "@example.test";
    }
}
