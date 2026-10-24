package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.telegram.dto.TelegramActivationRequest;
import com.agenticai.core.omnichannel.telegram.dto.TelegramCredentialResponse;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.security.SecureRandom;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * BYOK activation for a tenant's own Telegram Bot - mirrors {@code DiscordCredentialService}'s
 * shape, with one extra step: unlike Discord, Telegram genuinely has a public webhook, so
 * activation also calls {@code setWebhook} so Telegram starts pushing updates immediately.
 *
 * <p><b>What a tenant does to get this value</b>: message @BotFather on Telegram, {@code /newbot},
 * copy the token it hands back.
 */
@Service
public class TelegramCredentialService {

    private static final Logger log = LoggerFactory.getLogger(TelegramCredentialService.class);

    private final TelegramCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final TelegramApiClient apiClient;
    private final ObjectProvider<TelegramPollingService> pollingService;
    private final String webhookBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public TelegramCredentialService(
            TelegramCredentialRepository repository,
            EncryptionService encryptionService,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            TelegramApiClient apiClient,
            @Value("${app.telegram.webhook-base-url:}") String webhookBaseUrl,
            ObjectProvider<TelegramPollingService> pollingService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.apiClient = apiClient;
        this.webhookBaseUrl = webhookBaseUrl;
        this.pollingService = pollingService;
    }

    @Transactional
    public TelegramCredentialResponse activate(TelegramActivationRequest request) {
        TelegramApiClient.BotInfo botInfo = apiClient.fetchBotInfo(request.botToken());
        String encryptedToken = encryptionService.encrypt(request.botToken());
        String webhookSecret = generateSecret();
        // Telegram gets the raw token once (setWebhook below); only its digest is persisted.
        String webhookSecretHash = TelegramWebhookSecret.hash(webhookSecret);

        TelegramCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reactivate(
                                            botInfo.id(),
                                            botInfo.username(),
                                            encryptedToken,
                                            webhookSecretHash);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new TelegramCredential(
                                                botInfo.id(),
                                                botInfo.username(),
                                                encryptedToken,
                                                webhookSecretHash));

        TelegramCredentialResponse response =
                TelegramCredentialResponse.from(repository.save(credential));

        tenantResolutionService.link(
                ExternalAccountProvider.TELEGRAM,
                botInfo.id(),
                tenantSchemaResolver.resolveCurrentTenantIdentifier());

        // Best-effort - if webhookBaseUrl isn't configured on this deployment
        // (self-hosted, no public HTTPS URL yet), the credential still saves
        // and can be reused for outbound sends; the tenant just won't get
        // live inbound routing until an operator sets
        // app.telegram.webhook-base-url and the tenant reactivates.
        if (StringUtils.hasText(webhookBaseUrl)) {
            apiClient.setWebhook(
                    request.botToken(),
                    webhookBaseUrl + "/webhook/telegram/" + botInfo.id(),
                    webhookSecret);
        } else {
            // No public URL: receive by long polling instead (lite profile), so the bot works
            // with nothing but its token. A no-op where polling is off.
            // After commit: the poller reads the credential from its own thread and must see it.
            String schema = tenantSchemaResolver.resolveCurrentTenantIdentifier();
            pollingService.ifAvailable(
                    polling ->
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCommit() {
                                            polling.ensurePolling(botInfo.id(), schema);
                                        }
                                    }));
        }

        return response;
    }

    @Transactional(readOnly = true)
    public Optional<TelegramCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(TelegramCredentialResponse::from);
    }

    /**
     * Tenant-initiated "log out of Telegram" - marks the credential {@link
     * TelegramCredentialStatus#DISCONNECTED} (same contract as {@code
     * TwilioCredentialService#disconnect}), drops the bot-&gt;tenant link so inbound webhook calls
     * stop resolving this tenant, AND calls {@code deleteWebhook} so Telegram stops pushing updates
     * entirely. The deleteWebhook call is best-effort: the bot token may already be revoked, and
     * unlinking alone is enough to make inbound a no-op - a failed upstream call must not leave the
     * tenant unable to disconnect.
     */
    @Transactional
    public void disconnect() {
        repository
                .findFirstByOrderByCreatedAtAsc()
                .ifPresent(
                        credential -> {
                            credential.disconnect();
                            repository.save(credential);
                            tenantResolutionService.unlink(
                                    ExternalAccountProvider.TELEGRAM, credential.getBotUserId());
                            pollingService.ifAvailable(
                                    polling -> polling.stopPolling(credential.getBotUserId()));
                            try {
                                apiClient.deleteWebhook(
                                        encryptionService.decrypt(
                                                credential.getEncryptedBotToken()));
                            } catch (RuntimeException e) {
                                log.warn(
                                        "Telegram deleteWebhook failed during disconnect for bot ***{} - continuing (tenant link already removed)",
                                        lastFour(credential.getBotUserId()),
                                        e);
                            }
                        });
    }

    @Transactional(readOnly = true)
    public DecryptedCredential resolveActive() {
        TelegramCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == TelegramCredentialStatus.ACTIVE)
                        .orElseThrow(TelegramNotConfiguredException::new);
        return new DecryptedCredential(
                credential.getBotUserId(),
                encryptionService.decrypt(credential.getEncryptedBotToken()));
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return value.substring(value.length() - 4);
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public record DecryptedCredential(String botUserId, String botToken) {}
}
