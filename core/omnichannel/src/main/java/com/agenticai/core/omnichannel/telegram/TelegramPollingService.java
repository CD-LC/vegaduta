package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Receives Telegram messages by long polling ({@code getUpdates}) for installs that have no public
 * HTTPS URL, so a laptop or an office server can talk to a bot with nothing but the bot token. It
 * is the counterpart of {@link TelegramWebhookController}: the same {@link
 * TelegramUpdateService#acceptVerifiedUpdate} handles each update (dedup, pairing, agent turn,
 * approval-gated reply).
 *
 * <p>Off unless {@code app.telegram.polling.enabled=true} (the {@code lite} profile turns it on),
 * and it does nothing when {@code app.telegram.webhook-base-url} is set: Telegram delivers either
 * by webhook or by polling, never both, and two pollers on one bot get HTTP 409. Run it in ONE
 * process per bot (the single-process lite stack); a multi-replica deployment uses the webhook.
 *
 * <p>One virtual thread per bot. It sets the owning tenant's {@code TenantContext} itself, from the
 * bot-to-tenant link, because no request exists to resolve it from, and clears it after every
 * iteration, so no thread is ever left holding a tenant.
 */
@Component
@ConditionalOnProperty(name = "app.telegram.polling.enabled", havingValue = "true")
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);
    private static final String REQUESTED_BY_PREFIX = "system:telegram-polling:";
    static final int POLL_SECONDS = 25;
    private static final long SHED_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final TenantResolutionService tenantResolutionService;
    private final TelegramCredentialRepository credentialRepository;
    private final EncryptionService encryptionService;
    private final TelegramApiClient apiClient;
    private final TelegramUpdateService updateHandler;
    private final boolean webhookConfigured;
    private final Map<String, Poller> pollers = new ConcurrentHashMap<>();

    public TelegramPollingService(
            TenantResolutionService tenantResolutionService,
            TelegramCredentialRepository credentialRepository,
            EncryptionService encryptionService,
            TelegramApiClient apiClient,
            TelegramUpdateService updateHandler,
            @Value("${app.telegram.webhook-base-url:}") String webhookBaseUrl) {
        this.tenantResolutionService = tenantResolutionService;
        this.credentialRepository = credentialRepository;
        this.encryptionService = encryptionService;
        this.apiClient = apiClient;
        this.updateHandler = updateHandler;
        this.webhookConfigured = webhookBaseUrl != null && !webhookBaseUrl.isBlank();
    }

    /** Whether polling is the delivery mode on this install (no webhook URL configured). */
    public boolean active() {
        return !webhookConfigured;
    }

    /** Restores a poller for every connected bot after a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        if (!active()) {
            log.info(
                    "Telegram polling is enabled but a webhook base URL is set; using the webhook.");
            return;
        }
        for (TenantResolutionService.AccountTenant account :
                tenantResolutionService.listAccounts(ExternalAccountProvider.TELEGRAM)) {
            ensurePolling(account.externalAccountId(), account.tenantSchema());
        }
    }

    /** Starts polling this bot unless it already is. Safe to call again on reactivation. */
    public void ensurePolling(String botUserId, String tenantSchema) {
        if (!active()) {
            return;
        }
        pollers.compute(
                botUserId,
                (id, existing) -> {
                    if (existing != null && existing.running()) {
                        return existing;
                    }
                    Poller poller = new Poller(botUserId, tenantSchema);
                    poller.thread =
                            TenantAwareExecutors.startVirtualThread(
                                    "telegram-poll-" + lastFour(botUserId), poller::run);
                    log.info("Telegram polling started for bot ***{}", lastFour(botUserId));
                    return poller;
                });
    }

    public void stopPolling(String botUserId) {
        Poller poller = pollers.remove(botUserId);
        if (poller != null) {
            poller.stop();
        }
    }

    @PreDestroy
    void shutdown() {
        pollers.values().forEach(Poller::stop);
        pollers.clear();
    }

    private final class Poller {
        private final String botUserId;
        private final String tenantSchema;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile Thread thread;
        private long offset;

        Poller(String botUserId, String tenantSchema) {
            this.botUserId = botUserId;
            this.tenantSchema = tenantSchema;
        }

        boolean running() {
            return !stopped.get() && thread != null && thread.isAlive();
        }

        void stop() {
            stopped.set(true);
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }

        void run() {
            boolean webhookCleared = false;
            int failures = 0;
            while (!stopped.get()) {
                TenantContext.set(
                        TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + botUserId, Set.of()));
                try {
                    Optional<TelegramCredential> credential =
                            credentialRepository.findFirstByOrderByCreatedAtAsc();
                    if (credential.isEmpty()
                            || credential.get().getStatus() != TelegramCredentialStatus.ACTIVE
                            || !botUserId.equals(credential.get().getBotUserId())) {
                        log.info(
                                "Telegram polling stopped for bot ***{}: credential no longer"
                                        + " active",
                                lastFour(botUserId));
                        pollers.remove(botUserId, this);
                        return;
                    }
                    String botToken =
                            encryptionService.decrypt(credential.get().getEncryptedBotToken());
                    if (!webhookCleared) {
                        // getUpdates is refused while a webhook is registered.
                        apiClient.deleteWebhook(botToken);
                        webhookCleared = true;
                    }
                    JsonNode updates = apiClient.getUpdates(botToken, offset, POLL_SECONDS);
                    for (JsonNode update : updates) {
                        if (!updateHandler.acceptVerifiedUpdate(botUserId, update, botToken)) {
                            // Inbound executor full: keep this update for the next poll.
                            sleep(SHED_BACKOFF_MS);
                            break;
                        }
                        offset = update.path("update_id").asLong() + 1;
                    }
                    failures = 0;
                } catch (RuntimeException e) {
                    if (stopped.get()) {
                        return;
                    }
                    failures++;
                    log.warn(
                            "Telegram poll failed for bot ***{} ({} in a row): {}",
                            lastFour(botUserId),
                            failures,
                            e.getMessage());
                    sleep(Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(failures, 6)) * 1000);
                } finally {
                    TenantContext.clear();
                }
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                stopped.set(true);
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String lastFour(String value) {
        return value == null || value.length() < 4 ? "***" : value.substring(value.length() - 4);
    }
}
