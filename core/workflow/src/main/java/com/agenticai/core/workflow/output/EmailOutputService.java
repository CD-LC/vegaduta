package com.agenticai.core.workflow.output;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Output node "email" destination (#153). Deliberately a single platform-wide SMTP relay configured
 * via standard {@code spring.mail.*} properties (env vars in production - see {@code
 * application-workflow.yml}), not a per-tenant credential entity: unlike Twilio/WhatsApp (where
 * each tenant genuinely needs their own phone number to send *as themselves*), an "email me this
 * workflow's output" destination just needs *a* working outbound mailbox, the same pattern
 * Zapier/n8n/Make all use for their own built-in "Send Email" action. This avoids inventing a new
 * encrypted credential entity/UI for a feature that doesn't need per-tenant identity.
 *
 * <p>{@code spring-boot-starter-mail} auto-configures a {@link JavaMailSender} bean unconditionally
 * (defaulting to {@code localhost:25} if unset), so its mere presence doesn't mean email actually
 * works in a given deployment - {@link #mailHost} is checked explicitly and a clear {@link
 * ChannelConfigurationException} is thrown if it's blank, the same "tell the truth about what's
 * configured" pattern {@code McpCatalogService} uses.
 */
@Service
public class EmailOutputService {

    private static final Logger log = LoggerFactory.getLogger(EmailOutputService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailHost;
    private final String fromAddress;
    private final WorkflowOutputDedupGuard dedupGuard;

    public EmailOutputService(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            // 2026-08-20 fix: this used to default straight to a literal
            // "workflows@agentic-ai.local" that nothing in deploy/docker-compose.yml
            // or either .env file ever overrides - so every workflow "email"
            // output node sent From: workflows@agentic-ai.local while
            // authenticated to the SMTP relay as spring.mail.username
            // (no-reply@staging.example.com staging / noreply@example.com production).
            // That exact from-vs-authenticated-user mismatch is the documented,
            // already-reproduced cause of Porkbun's "553 5.7.1 Sender address
            // rejected: not owned by user" (see BillingEmailService's env-var
            // history) - Gmail's Workspace relay enforces the same "Send As"
            // ownership rule. Falling back to spring.mail.username instead of an
            // unrelated hardcoded domain means this node sends AS the account it
            // authenticates as unless someone deliberately sets app.mail.from to
            // a real delegate/alias - which is what the account actually needs to
            // send successfully, not a config knob nobody was setting.
            @Value("${app.mail.from:${spring.mail.username:workflows@agentic-ai.local}}")
                    String fromAddress,
            WorkflowOutputDedupGuard dedupGuard) {
        this.mailSender = mailSender;
        this.mailHost = mailHost;
        this.fromAddress = fromAddress;
        this.dedupGuard = dedupGuard;
    }

    /**
     * @param runId the workflow run this send belongs to.
     * @param nodeId the Output node's id within the workflow graph.
     * @return a short, human-readable log line describing what happened, for the run's detailed
     *     trace.
     */
    public String send(String to, String subject, String body, String runId, String nodeId) {
        if (!StringUtils.hasText(mailHost)) {
            throw new ChannelConfigurationException(
                    "email",
                    "No SMTP server configured - set spring.mail.host/port/username/password (see"
                            + " application-workflow.yml).");
        }
        if (!StringUtils.hasText(to)) {
            throw new ChannelConfigurationException(
                    "email", "Output node is missing a destination email address.");
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new ChannelConfigurationException("email", "JavaMailSender bean unavailable.");
        }
        // At-least-once execution (Temporal Activity retries, node retries) can
        // re-run this send with identical arguments - claim the dedup key
        // AFTER config validation (a config error must stay retryable without
        // burning a claim) and release it if the SMTP hand-off fails, so the
        // legitimate retry of a FAILED send is not suppressed. See
        // WorkflowOutputDedupGuard's javadoc for the key's honest limits.
        String dedupKey = dedupGuard.identityKey("email", runId, nodeId, to);
        if (!dedupGuard.tryClaim(dedupKey)) {
            log.info(
                    "Suppressed duplicate workflow-output email send (dedup key already claimed"
                            + " within TTL).");
            return "Skipped duplicate email to "
                    + to
                    + " - an identical send already went out moments ago.";
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("email-send");
            sender.send(message);
        } catch (RuntimeException e) {
            dedupGuard.release(dedupKey);
            throw e;
        }
        return "Emailed output to " + to + " via " + mailHost + ".";
    }
}
