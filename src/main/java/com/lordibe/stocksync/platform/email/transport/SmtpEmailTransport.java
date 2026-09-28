package com.lordibe.stocksync.platform.email.transport;

import com.lordibe.stocksync.platform.email.domain.EmailMessage;
import com.lordibe.stocksync.platform.email.service.EmailProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Real delivery over SMTP. Active only when {@code spring.mail.host} is set.
 *
 * <p>Sends multipart when an HTML body is present, with the plain-text part as
 * the alternative: some clients, and most screen readers, prefer it, and it is
 * what lands in a spam-filter's text analysis.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
@RequiredArgsConstructor
public class SmtpEmailTransport implements EmailTransport {

    private final JavaMailSender mailSender;
    private final EmailProperties properties;

    @Override
    public void send(EmailMessage message) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            boolean multipart = message.getBodyHtml() != null;
            MimeMessageHelper helper =
                    new MimeMessageHelper(mime, multipart, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.fromAddress(), properties.fromName());
            helper.setTo(message.getToAddress());
            helper.setSubject(message.getSubject());
            if (multipart) {
                helper.setText(message.getBodyText(), message.getBodyHtml());
            } else {
                helper.setText(message.getBodyText(), false);
            }
        } catch (Exception malformed) {
            // A message we cannot even build will never succeed; surface it as a
            // mail exception so the dispatcher records it against the row.
            throw new MailPreparationException("Could not build message " + message.getId(), malformed);
        }
        mailSender.send(mime);
    }

    @Override
    public String describe() {
        return "SMTP as " + properties.fromAddress();
    }
}
