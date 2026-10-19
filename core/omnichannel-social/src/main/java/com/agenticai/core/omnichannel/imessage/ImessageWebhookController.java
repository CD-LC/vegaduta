package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives BlueBubbles webhook events, one URL per tenant: {@code
 * /webhook/imessage/{webhookId}?token=<secret>}. BlueBubbles cannot add headers to its webhook
 * calls, so the secret travels in the query string (a reverse proxy may send it as the {@code
 * X-Imessage-Webhook-Token} header instead; the header wins). {@code LogMasking.path} drops query
 * strings from the request log, and this class never logs the token.
 *
 * <p>Unauthenticated callers learn nothing: an unknown webhook id, a disconnected tenant, a missing
 * token and a wrong token all answer the same 403, and the token is compared in constant time
 * against the tenant's decrypted secret ({@link ImessageCredentialService#webhookTokenMatches}).
 * Only after that does {@link ImessageUpdateService} see the event.
 */
@RestController
@RequestMapping("/webhook/imessage")
public class ImessageWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ImessageWebhookController.class);
    private static final String REQUESTED_BY_PREFIX = "system:imessage-inbound:";

    private final TenantResolutionService tenantResolutionService;
    private final ImessageCredentialService credentialService;
    private final ImessageUpdateService updateService;
    private final InboundChannelExecutor inboundExecutor;

    public ImessageWebhookController(
            TenantResolutionService tenantResolutionService,
            ImessageCredentialService credentialService,
            ImessageUpdateService updateService,
            InboundChannelExecutor inboundExecutor) {
        this.tenantResolutionService = tenantResolutionService;
        this.credentialService = credentialService;
        this.updateService = updateService;
        this.inboundExecutor = inboundExecutor;
    }

    @PostMapping("/{webhookId}")
    public ResponseEntity<Void> inbound(
            @PathVariable String webhookId,
            @RequestBody JsonNode event,
            @RequestParam(value = "token", required = false) String queryToken,
            @RequestHeader(value = "X-Imessage-Webhook-Token", required = false) String headerToken) {
        Optional<String> tenantSchema =
                tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.IMESSAGE, webhookId);
        if (tenantSchema.isEmpty()) {
            log.warn("Rejected iMessage webhook call for an unknown webhook id");
            return ResponseEntity.status(403).build();
        }
        TenantContext.set(TenantContext.of(tenantSchema.get(), REQUESTED_BY_PREFIX + "webhook", Set.of()));
        try {
            String token = headerToken != null && !headerToken.isEmpty() ? headerToken : queryToken;
            if (!credentialService.webhookTokenMatches(webhookId, token)) {
                log.warn("Rejected iMessage webhook call with a missing or invalid token");
                return ResponseEntity.status(403).build();
            }
            if (!updateService.acceptVerified(event)) {
                // shed-mode=retry (default): 503 so BlueBubbles retries; ack: 200 and drop.
                return inboundExecutor.shedAcks() ? ResponseEntity.ok().build() : ResponseEntity.status(503).build();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to process iMessage webhook event: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.ok().build();
    }
}
