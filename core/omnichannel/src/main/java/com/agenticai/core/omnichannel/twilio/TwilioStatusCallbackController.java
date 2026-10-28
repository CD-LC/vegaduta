package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Twilio's delivery-status callback for outbound WhatsApp messages
 * (#474 follow-up: "I want to know when an auto-reply failed to deliver").
 * {@link com.agenticai.core.omnichannel.WhatsAppDispatchService#sendViaTwilio}
 * points every outbound send here via {@code statusCallback} whenever {@code
 * app.omnichannel.public-base-url} is configured; Twilio then POSTs to this
 * URL every time that message's status changes (queued -&gt; sent -&gt;
 * delivered, or queued -&gt; failed/undelivered).
 *
 * <p>Authenticated exactly like {@link TwilioWebhookController} - {@link
 * TwilioSignatureValidator}, not a JWT, resolving the tenant first from the
 * unverified-but-self-defeating-to-forge {@code AccountSid}, then verifying
 * using that tenant's own Auth Token (see that class's javadoc for the full
 * reasoning, including the same "no tenant link found" -&gt; default-schema
 * fallback used here for a single-tenant setup that never explicitly linked
 * its Twilio credential).
 *
 * <p><b>Only {@code failed}/{@code undelivered} write an audit entry.</b>
 * {@code queued}/{@code sent}/{@code delivered} are logged at INFO for
 * debugging only - auditing every intermediate status of every message
 * would flood {@code audit_log_entry} with noise nobody asked to see. A
 * failure is recorded with the real Twilio error code/message and the
 * masked recipient, visible in the existing Audit Log / Live Audit Tail
 * screens - no new UI needed for this to be visible.
 */
@RestController
@RequestMapping("/webhook/twilio")
public class TwilioStatusCallbackController {

    private static final Logger log = LoggerFactory.getLogger(TwilioStatusCallbackController.class);
    private static final String REQUESTED_BY_PREFIX = "system:twilio-status-callback:";
    private static final String AUDIT_ACTOR = "system:twilio-status-callback";
    private static final Set<String> FAILURE_STATUSES = Set.of("failed", "undelivered");

    private final TwilioSignatureValidator signatureValidator;
    private final TwilioCredentialService credentialService;
    private final TenantResolutionService tenantResolutionService;
    private final AuditLogService auditLogService;

    public TwilioStatusCallbackController(
        TwilioSignatureValidator signatureValidator,
        TwilioCredentialService credentialService,
        TenantResolutionService tenantResolutionService,
        AuditLogService auditLogService
    ) {
        this.signatureValidator = signatureValidator;
        this.credentialService = credentialService;
        this.tenantResolutionService = tenantResolutionService;
        this.auditLogService = auditLogService;
    }

    @PostMapping(value = "/status", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> status(@RequestParam Map<String, String> formParams, HttpServletRequest servletRequest) {
        String accountSid = formParams.getOrDefault("AccountSid", "");
        String tenantSchema = tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, accountSid)
            .orElseGet(() -> {
                log.warn(
                    "No tenant link found for Twilio AccountSid ***{} on a status callback - falling back to the {} schema "
                        + "(same fallback TwilioWebhookController uses for inbound messages).",
                    lastFour(accountSid), TenantSchemaResolver.DEFAULT_SCHEMA
                );
                return TenantSchemaResolver.DEFAULT_SCHEMA;
            });

        TenantContext.set(TenantContext.ofWebhookSchema(tenantSchema, REQUESTED_BY_PREFIX + accountSid, Set.of()));
        try {
            handle(formParams, servletRequest);
        } catch (RuntimeException e) {
            // Same posture as TwilioWebhookController/MetaWhatsAppWebhookController -
            // never let a processing failure here turn into a non-2xx, which
            // would make Twilio retry (or eventually disable) this callback
            // URL for something that isn't Twilio's problem.
            log.warn("Failed to process Twilio status callback: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.ok().build();
    }

    private void handle(Map<String, String> formParams, HttpServletRequest servletRequest) {
        // resolveActive() throws TwilioNotConfiguredException if this
        // tenant somehow has no ACTIVE Twilio credential right now (e.g. it
        // was disconnected between the send and this callback arriving) -
        // there is no auth token left to validate the signature against, so
        // this callback can't be trusted; the outer catch logs and drops it.
        TwilioCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        signatureValidator.validateOrThrow(
            servletRequest, formParams, servletRequest.getHeader("X-Twilio-Signature"), credential.authToken()
        );

        String status = formParams.getOrDefault("MessageStatus", "").toLowerCase(java.util.Locale.ROOT);
        String messageSid = formParams.getOrDefault("MessageSid", "");
        String to = stripWhatsAppPrefix(formParams.getOrDefault("To", ""));
        String errorCode = formParams.get("ErrorCode");
        String errorMessage = formParams.get("ErrorMessage");

        if (FAILURE_STATUSES.contains(status)) {
            String detail = "WhatsApp delivery to " + maskRecipient(to) + " " + status
                + (errorCode != null && !errorCode.isBlank()
                    ? " (Twilio error " + errorCode + (errorMessage != null && !errorMessage.isBlank() ? ": " + errorMessage : "") + ")"
                    : "");
            log.warn("{} (SID {})", detail, messageSid);
            auditLogService.record(AUDIT_ACTOR, "channel.whatsapp.delivery_failed", "twilio_message", messageSid, AuditOutcome.FAILURE, detail);
        } else {
            log.info("Twilio status callback: message {} is now '{}'", messageSid, status);
        }
    }

    private static String stripWhatsAppPrefix(String value) {
        return value.startsWith("whatsapp:") ? value.substring("whatsapp:".length()) : value;
    }

    private static String maskRecipient(String to) {
        if (to == null || to.length() < 4) {
            return "***";
        }
        return "***" + to.substring(to.length() - 4);
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return value.substring(value.length() - 4);
    }
}
