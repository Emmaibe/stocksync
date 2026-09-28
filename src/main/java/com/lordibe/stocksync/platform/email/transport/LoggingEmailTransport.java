package com.lordibe.stocksync.platform.email.transport;

import com.lordibe.stocksync.platform.email.domain.EmailMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The fallback when no mail server is configured: logs the message and reports
 * success.
 *
 * <p>This is what makes the whole flow usable on a laptop and in tests — an
 * invitation still gets a token, and the log shows exactly what would have been
 * sent, including the link. It is chosen only when {@link SmtpEmailTransport} is
 * absent, so a misconfigured production instance cannot silently fall back to it
 * without {@code spring.mail.host} being missing, which the startup log states.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(SmtpEmailTransport.class)
public class LoggingEmailTransport implements EmailTransport {

    @Override
    public void send(EmailMessage message) {
        log.info("""
                        
                        ---- email not sent (no mail server configured) ----
                        to:       {}
                        subject:  {}
                        template: {}
                        
                        {}
                        ---------------------------------------------------""",
                message.getToAddress(), message.getSubject(), message.getTemplate(),
                message.getBodyText());
    }

    @Override
    public String describe() {
        return "logging only (spring.mail.host is not set)";
    }
}
