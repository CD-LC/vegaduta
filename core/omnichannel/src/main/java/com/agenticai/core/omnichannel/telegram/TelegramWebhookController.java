package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Real public Telegram Bot API webhook - unlike Discord, Telegram delivers ordinary messages over
 * plain HTTPS (no persistent Gateway needed), so this receives Telegram's native {@code Update}
 * JSON directly, one endpoint per bot (path-scoped by {@code botUserId}, mirroring how Twilio's
 * inbound path is scoped by Account SID).
 *
 * <p>Auth is Telegram's own {@code secret_token} mechanism: {@link
 * TelegramCredentialService#activate} registers a per-tenant random secret via {@code setWebhook},
 * and Telegram echoes it back on every call via {@code X-Telegram-Bot-Api-Secret-Token} - verified
 * here against the resolved tenant's stored value before anything else runs. Once the origin is
 * trusted, {@link TelegramUpdateService} does the rest.
 */
@RestController
@RequestMapping("/webhook/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);
    private static final String REQUESTED_BY_PREFIX = "system:telegram-inbound:";

    private final TenantResolutionService tenantResolutionService;
    private final TelegramCredentialRepository credentialRepository;
    private final EncryptionService encryptionService;
    private final TelegramUpdateService updateService;
    private final InboundChannelExecutor inboundExecutor;

    public TelegramWebhookController(
            TenantResolutionService tenantResolutionService,
            TelegramCredentialRepository credentialRepository,
            EncryptionService encryptionService,
            TelegramUpdateService updateService,
            InboundChannelExecutor inboundExecutor) {
        this.tenantResolutionService = tenantResolutionService;
        this.credentialRepository = credentialRepository;
        this.encryptionService = encryptionService;
        this.updateService = updateService;
        this.inboundExecutor = inboundExecutor;
    }

    @PostMapping("/{botUserId}")
    public ResponseEntity<Void> inbound(
            @PathVariable String botUserId,
            @RequestBody JsonNode update,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false)
                    String secretHeader) {
        Optional<String> tenantSchema =
                tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.TELEGRAM, botUserId);
        if (tenantSchema.isEmpty()) {
            log.warn(
                    "No tenant link found for Telegram bot ***{} - ignoring.", lastFour(botUserId));
            return ResponseEntity.ok().build();
        }

        TenantContext.set(
                TenantContext.of(tenantSchema.get(), REQUESTED_BY_PREFIX + botUserId, Set.of()));
        try {
            Optional<TelegramCredential> credential =
                    credentialRepository.findFirstByOrderByCreatedAtAsc();
            if (credential.isEmpty()
                    || secretHeader == null
                    || !constantTimeEquals(secretHeader, credential.get().getWebhookSecret())) {
                log.warn(
                        "Rejected Telegram inbound call with missing/invalid secret token for bot"
                                + " ***{}",
                        lastFour(botUserId));
                return ResponseEntity.status(403).build();
            }
            // Decrypt BEFORE claiming: a decrypt failure must not leave a dedup claim held for a
            // message that was never processed.
            String botToken = encryptionService.decrypt(credential.get().getEncryptedBotToken());
            if (!updateService.acceptVerifiedUpdate(botUserId, update, botToken)) {
                // shed-mode=retry (default): 503 so Telegram redelivers; ack: 200 and drop (never
                // throttled).
                return inboundExecutor.shedAcks()
                        ? ResponseEntity.ok().build()
                        : ResponseEntity.status(503).build();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to process Telegram inbound update: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.ok().build();
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return value.substring(value.length() - 4);
    }

    // Constant-time comparison - a secret-token check is exactly the kind of
    // thing a naive String.equals() timing side-channel could leak information
    // from, however marginal. Same idiom as MetaWhatsAppSignatureValidator.
    // A null on either side (e.g. a credential with no stored webhook secret)
    // is a mismatch, never an NPE.
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
