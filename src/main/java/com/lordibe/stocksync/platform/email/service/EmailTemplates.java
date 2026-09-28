package com.lordibe.stocksync.platform.email.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The messages this service sends.
 *
 * <p>Plain Java text blocks rather than a template engine: there are a handful of
 * transactional emails, none of them designed by anyone but us, and adding
 * Thymeleaf back for them would reintroduce a dependency this application
 * deliberately dropped when it became API-only.
 *
 * <p>Every message ships text and HTML. The text part is not a courtesy — it is
 * what screen readers and spam filters read, and what survives a client that
 * blocks HTML.
 */
@Component
@RequiredArgsConstructor
public class EmailTemplates {

    public static final String INVITATION = "invitation";
    public static final String PASSWORD_RESET = "password-reset";
    public static final String PASSWORD_CHANGED = "password-changed";

    private final EmailProperties properties;

    public Rendered invitation(String tenantName, String inviterName, String rawToken,
                               Duration validFor) {
        String link = properties.appBaseUrl() + "/accept-invitation?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String subject = "You have been invited to " + tenantName + " on StockSync";
        String text = """
                %s has invited you to join %s on StockSync.

                Set your password and get started:
                %s

                This invitation expires in %d days. If you were not expecting it,
                you can ignore this email — nothing will happen until it is used.
                """.formatted(inviterName, tenantName, link, validFor.toDays());
        return new Rendered(subject, text, html(
                "You have been invited to " + escape(tenantName),
                escape(inviterName) + " has invited you to join <strong>" + escape(tenantName)
                        + "</strong> on StockSync.",
                "Accept invitation", link,
                "This invitation expires in " + validFor.toDays() + " days. If you were not "
                        + "expecting it, you can ignore this email."));
    }

    public Rendered passwordReset(String rawToken, Duration validFor) {
        String link = properties.appBaseUrl() + "/reset-password?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String subject = "Reset your StockSync password";
        String text = """
                Someone asked to reset the password for this StockSync account.

                Choose a new password:
                %s

                This link works once and expires in %d minutes. If you did not ask
                for it, ignore this email — your password has not changed.
                """.formatted(link, validFor.toMinutes());
        return new Rendered(subject, text, html(
                "Reset your password",
                "Someone asked to reset the password for this StockSync account.",
                "Choose a new password", link,
                "This link works once and expires in " + validFor.toMinutes()
                        + " minutes. If you did not ask for it, ignore this email — your "
                        + "password has not changed."));
    }

    /**
     * Sent after a password changes. Not a courtesy: it is how someone finds out
     * their account was taken over, so it goes out even though nothing is needed
     * from the reader.
     */
    public Rendered passwordChanged() {
        String subject = "Your StockSync password was changed";
        String text = """
                The password for your StockSync account was just changed, and you
                have been signed out everywhere.

                If this was not you, reset your password immediately and contact
                whoever administers your workspace.
                """;
        return new Rendered(subject, text, html(
                "Your password was changed",
                "The password for your StockSync account was just changed, and you have been "
                        + "signed out everywhere.",
                null, null,
                "If this was not you, reset your password immediately and contact whoever "
                        + "administers your workspace."));
    }

    /** One layout, deliberately plain: it renders the same in every mail client. */
    private String html(String heading, String body, String buttonLabel, String buttonHref,
                        String footer) {
        String button = buttonLabel == null ? "" : """
                <p style="margin:24px 0;">
                  <a href="%s" style="background:#1f2937;color:#ffffff;padding:12px 20px;
                     border-radius:6px;text-decoration:none;display:inline-block;">%s</a>
                </p>
                <p style="font-size:13px;color:#6b7280;">
                  If the button does not work, copy this link:<br><span>%s</span>
                </p>
                """.formatted(buttonHref, buttonLabel, buttonHref);

        return """
                <!doctype html>
                <html><body style="margin:0;padding:24px;background:#f9fafb;
                      font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
                      color:#111827;">
                  <div style="max-width:520px;margin:0 auto;background:#ffffff;padding:32px;
                       border-radius:10px;border:1px solid #e5e7eb;">
                    <h1 style="font-size:20px;margin:0 0 16px;">%s</h1>
                    <p style="font-size:15px;line-height:1.6;margin:0;">%s</p>
                    %s
                    <p style="font-size:13px;color:#6b7280;line-height:1.6;margin:24px 0 0;">%s</p>
                  </div>
                </body></html>
                """.formatted(heading, body, button, footer);
    }

    /** Tenant and user names are attacker-influenced; they never go into HTML raw. */
    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public record Rendered(String subject, String text, String html) {
    }
}
