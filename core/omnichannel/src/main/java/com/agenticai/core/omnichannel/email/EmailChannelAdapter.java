package com.agenticai.core.omnichannel.email;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ApprovalActionExecutor} for {@link #ACTION_TYPE} - sends replies via the platform's
 * existing SMTP relay bean (same {@link JavaMailSender} {@code EmailOutputService} uses for
 * workflow-output emails), not a per-tenant SMTP connection - only the *inbound* IMAP side is
 * per-tenant (see {@link EmailCredential}'s javadoc for why).
 */
@Component
public class EmailChannelAdapter implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "channel.email.send";

    private static final Logger log = LoggerFactory.getLogger(EmailChannelAdapter.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailHost;
    private final String fromAddress;
    private final ChannelOutboundLogger outboundLogger;

    public EmailChannelAdapter(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            // See EmailOutputService's matching @Value for the full rationale:
            // falls back to the SMTP-authenticated user, not an unrelated
            // hardcoded domain nothing ever overrides in this deployment.
            @Value("${app.mail.from:${spring.mail.username:workflows@agentic-ai.local}}")
                    String fromAddress,
            ChannelOutboundLogger outboundLogger) {
        this.mailSender = mailSender;
        this.mailHost = mailHost;
        this.fromAddress = fromAddress;
        this.outboundLogger = outboundLogger;
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        SendPayload payload = parsePayload(request.getPayloadJson());
        if (!StringUtils.hasText(mailHost)) {
            throw new ChannelConfigurationException(
                    "email",
                    "No SMTP server configured - set spring.mail.host/port/username/password.");
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new ChannelConfigurationException("email", "JavaMailSender bean unavailable.");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(payload.to());
            message.setSubject(payload.subject());
            message.setText(payload.body());
            com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("email-send");
            sender.send(message);
            log.info(
                    "Emailed reply to {} via {}.",
                    com.agenticai.core.security.LogMasking.email(payload.to()),
                    mailHost);
            outboundLogger.recordSent(
                    ChannelType.EMAIL, payload.to(), payload.body(), request.getRequestedBy());
        } catch (RuntimeException e) {
            throw new RetryableChannelDeliveryException(
                    "email", "Email send failed: " + e.getMessage(), e);
        }
    }

    private SendPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new ChannelDeliveryException("email", "Approval request has no payload to send");
        }
        try {
            return MAPPER.readValue(payloadJson, SendPayload.class);
        } catch (Exception e) {
            throw new ChannelDeliveryException(
                    "email", "Could not parse email send payload: " + e.getMessage(), e);
        }
    }

    /** {@code to} is a plain email address. */
    public record SendPayload(String to, String subject, String body) {}
}
