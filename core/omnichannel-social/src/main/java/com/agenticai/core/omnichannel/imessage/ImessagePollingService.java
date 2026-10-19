package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.util.List;
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
 * The polling fallback: asks the tenant's BlueBubbles server for new messages every few seconds
 * instead of waiting for it to call our webhook. For installs where the Mac cannot reach this
 * platform (BlueBubbles pushes outward, so a platform behind NAT or on a laptop that sleeps never
 * hears it) - the platform reaches the Mac instead, over the same guarded client as sends.
 *
 * <p>Off unless {@code app.imessage.polling.enabled=true}; a tenant opts in per connection with
 * {@code inboundMode=POLLING}. Run it in ONE process per tenant (the single-process stack): two
 * pollers would both see every message, and only the Redis dedup claim would keep the agent from
 * answering twice.
 *
 * <p>The cursor is the newest {@code dateCreated} handled, persisted after every round. The first
 * round after polling is switched on sets it to "now" and handles nothing: a connection never
 * replays history into an agent. Messages go through the exact path a webhook event takes ({@link
 * ImessageUpdateService#acceptVerified}). One virtual thread per tenant; it sets the owning
 * tenant's {@code TenantContext} itself and clears it after every round.
 */
@Component
@ConditionalOnProperty(name = "app.imessage.polling.enabled", havingValue = "true")
public class ImessagePollingService {

    private static final Logger log = LoggerFactory.getLogger(ImessagePollingService.class);
    private static final String REQUESTED_BY_PREFIX = "system:imessage-polling:";
    static final int PAGE_SIZE = 100;
    private static final long SHED_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_SECONDS = 60;

    /** Outcome of one polling round. */
    enum Round {
        /** Credential gone, disconnected or no longer in polling mode: the poller should exit. */
        STOP,
        /** Nothing new, or everything handled. */
        IDLE,
        /** The inbound executor was full; the cursor was held back so the rest is retried. */
        SHED
    }

    private final TenantResolutionService tenantResolutionService;
    private final ImessageCredentialService credentialService;
    private final ImessageApiClient apiClient;
    private final ImessageUpdateService updateService;
    private final long intervalMs;
    private final Map<String, Poller> pollers = new ConcurrentHashMap<>();

    public ImessagePollingService(
            TenantResolutionService tenantResolutionService,
            ImessageCredentialService credentialService,
            ImessageApiClient apiClient,
            ImessageUpdateService updateService,
            @Value("${app.imessage.polling.interval-ms:5000}") long intervalMs) {
        this.tenantResolutionService = tenantResolutionService;
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.updateService = updateService;
        this.intervalMs = Math.max(1_000, intervalMs);
    }

    /** Restores a poller for every connected tenant after a restart (a poller for a webhook-mode tenant exits at once). */
    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        for (TenantResolutionService.AccountTenant account :
                tenantResolutionService.listAccounts(ExternalAccountProvider.IMESSAGE)) {
            ensurePolling(account.externalAccountId(), account.tenantSchema());
        }
    }

    /** Starts polling this connection unless it already is. Safe to call again on reconnect. */
    public void ensurePolling(String webhookId, String tenantSchema) {
        pollers.compute(
                webhookId,
                (id, existing) -> {
                    if (existing != null && existing.running()) {
                        return existing;
                    }
                    Poller poller = new Poller(webhookId, tenantSchema);
                    poller.thread =
                            TenantAwareExecutors.startVirtualThread("imessage-poll-" + abbreviate(webhookId), poller::run);
                    log.info("iMessage polling started for connection ***{}", abbreviate(webhookId));
                    return poller;
                });
    }

    public void stopPolling(String webhookId) {
        Poller poller = pollers.remove(webhookId);
        if (poller != null) {
            poller.stop();
        }
    }

    @PreDestroy
    void shutdown() {
        pollers.values().forEach(Poller::stop);
        pollers.clear();
    }

    /**
     * One round for one connection. The caller has set the {@code TenantContext}. Package-private
     * so tests can drive a round without a thread.
     */
    Round pollOnce(String webhookId) {
        Optional<ImessageCredentialService.PollTarget> target = credentialService.pollTarget(webhookId);
        if (target.isEmpty()) {
            return Round.STOP;
        }
        ImessageCredentialService.PollTarget t = target.get();
        if (t.cursorMs() == null) {
            credentialService.savePollCursor(System.currentTimeMillis());
            return Round.IDLE;
        }
        long cursor = t.cursorMs();
        List<JsonNode> messages = apiClient.queryMessages(t.serverUrl(), t.password(), cursor, PAGE_SIZE);
        long newest = cursor;
        Round result = Round.IDLE;
        for (JsonNode message : messages) {
            if (!updateService.acceptVerified(message)) {
                result = Round.SHED;
                break;
            }
            newest = Math.max(newest, message.path("dateCreated").asLong(0));
        }
        if (newest > cursor) {
            credentialService.savePollCursor(newest);
        }
        return result;
    }

    private final class Poller {
        private final String webhookId;
        private final String tenantSchema;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile Thread thread;

        Poller(String webhookId, String tenantSchema) {
            this.webhookId = webhookId;
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
            int failures = 0;
            while (!stopped.get()) {
                long pause = intervalMs;
                TenantContext.set(TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + "poller", Set.of()));
                try {
                    Round round = pollOnce(webhookId);
                    if (round == Round.STOP) {
                        log.info("iMessage polling stopped for connection ***{}: not in polling mode any more", abbreviate(webhookId));
                        pollers.remove(webhookId, this);
                        return;
                    }
                    if (round == Round.SHED) {
                        pause = SHED_BACKOFF_MS;
                    }
                    failures = 0;
                } catch (RuntimeException e) {
                    if (stopped.get()) {
                        return;
                    }
                    failures++;
                    log.warn(
                            "iMessage poll failed for connection ***{} ({} in a row): {}",
                            abbreviate(webhookId),
                            failures,
                            e.getMessage());
                    pause = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(failures, 6)) * 1000;
                } finally {
                    TenantContext.clear();
                }
                sleep(pause);
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

    private static String abbreviate(String value) {
        return value == null || value.length() < 4 ? "***" : value.substring(value.length() - 4);
    }
}
