package com.agenticai.core.omnichannel.email;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@link ChannelMediaSender} for {@link ChannelType#EMAIL} - a file attachment forwarded to an
 * agent's inbox reply. Shares the same platform- wide {@link JavaMailSender} bean/{@code
 * app.mail.from} address as {@link EmailChannelAdapter} (see that class's javadoc for why outbound
 * email is NOT per-tenant SMTP - only the inbound IMAP side, {@link EmailCredential}, is
 * per-tenant), so there is no tenant credential to resolve here beyond that shared bean.
 *
 * <p>{@link EmailChannelAdapter} itself stays on {@link org.springframework.mail.SimpleMailMessage}
 * for its existing plain-text approval-gated reply path - {@code SimpleMailMessage} cannot carry
 * attachments at all, so this class uses {@link JavaMailSender#createMimeMessage()} + {@link
 * MimeMessageHelper} (Spring's standard attachment API) instead, kept as a separate bean rather
 * than rewriting the existing path so a plain-text approval reply can never be affected by an
 * attachment bug.
 */
@Component
public class EmailMediaSender implements ChannelMediaSender {

    private static final Logger log = LoggerFactory.getLogger(EmailMediaSender.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailHost;
    private final String fromAddress;

    public EmailMediaSender(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            // See EmailOutputService's matching @Value for the full rationale:
            // falls back to the SMTP-authenticated user, not an unrelated
            // hardcoded domain nothing ever overrides in this deployment.
            @Value("${app.mail.from:${spring.mail.username:workflows@agentic-ai.local}}")
                    String fromAddress) {
        this.mailSender = mailSender;
        this.mailHost = mailHost;
        this.fromAddress = fromAddress;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.EMAIL;
    }

    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption)
            throws Exception {
        if (!StringUtils.hasText(mailHost)) {
            throw new ChannelConfigurationException(
                    "email",
                    "No SMTP server configured - set spring.mail.host/port/username/password.");
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new ChannelConfigurationException("email", "JavaMailSender bean unavailable.");
        }
        String subject =
                StringUtils.hasText(caption)
                        ? caption
                        : "New attachment: " + safeFilename(filename);
        String body = StringUtils.hasText(caption) ? caption : "Please find the attached file.";
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body);
            helper.addAttachment(
                    safeFilename(filename),
                    new ByteArrayResource(bytes),
                    StringUtils.hasText(contentType) ? contentType : "application/octet-stream");
            com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("email-send");
            sender.send(message);
            log.info(
                    "Emailed attachment to {} via {}.",
                    com.agenticai.core.security.LogMasking.email(to),
                    mailHost);
        } catch (ChannelConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "email", "Email attachment send failed: " + e.getMessage(), e);
        }
    }

    private static String safeFilename(String filename) {
        return StringUtils.hasText(filename) ? filename : "attachment";
    }
}
