package com.agenticai.core.workflow.output;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * One-recipient MIME send for the {@code bulk_email} node. Same platform SMTP relay and
 * From-address rules as {@link EmailOutputService} (see its javadoc), but built as a {@link
 * MimeMessage} rather than a {@code SimpleMailMessage} so the outbound {@code Message-ID} is known
 * (needed for reply detection), {@code List-Unsubscribe} headers can be attached and an HTML body
 * is possible.
 */
@Service
public class BulkEmailSender {

    private static final Logger log = LoggerFactory.getLogger(BulkEmailSender.class);

    /** The outcome of one successful hand-off to the SMTP relay. */
    public record Sent(String messageId) {}

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailHost;
    private final String fromAddress;

    public BulkEmailSender(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${app.mail.from:${spring.mail.username:workflows@agentic-ai.local}}")
                    String fromAddress) {
        this.mailSender = mailSender;
        this.mailHost = mailHost;
        this.fromAddress = fromAddress;
    }

    /** Fails fast (before any recipient is touched) when no SMTP relay is configured. */
    public void requireConfigured() {
        if (!StringUtils.hasText(mailHost)) {
            throw new ChannelConfigurationException(
                    "email",
                    "No SMTP server configured - set spring.mail.host/port/username/password (see"
                            + " application-workflow.yml).");
        }
        if (mailSender.getIfAvailable() == null) {
            throw new ChannelConfigurationException("email", "JavaMailSender bean unavailable.");
        }
    }

    /**
     * @param unsubscribeUrl when non-null adds {@code List-Unsubscribe} and the RFC 8058 {@code
     *     List-Unsubscribe-Post} one-click header.
     * @return the Message-ID actually on the wire (without angle brackets).
     */
    public Sent send(
            String to,
            String subject,
            String body,
            boolean html,
            String replyTo,
            String unsubscribeUrl) {
        requireConfigured();
        JavaMailSender sender = mailSender.getIfAvailable();
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            if (StringUtils.hasText(replyTo)) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(subject == null ? "" : subject);
            helper.setText(body == null ? "" : body, html);
            message.setHeader("Message-ID", newMessageId());
            message.setHeader("Precedence", "bulk");
            if (unsubscribeUrl != null) {
                message.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");
                message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            }
            com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("email-send");
            sender.send(message);
            String id = message.getMessageID();
            return new Sent(id == null ? null : id.replaceAll("^<|>$", ""));
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Bulk email send failed: {}", e.getMessage());
            throw new ChannelDeliveryException("email", "Email send failed: " + e.getMessage(), e);
        }
    }

    private String newMessageId() {
        int at = fromAddress == null ? -1 : fromAddress.lastIndexOf('@');
        String domain =
                at >= 0 && at < fromAddress.length() - 1
                        ? fromAddress.substring(at + 1)
                        : "localhost";
        return "<" + UUID.randomUUID() + "@" + domain + ">";
    }
}
