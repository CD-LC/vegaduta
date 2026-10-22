package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.omnichannel.matrix.MatrixSyncParser.Batch;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Receives Matrix messages by the Client-Server API's {@code /sync} long poll, the only way a
 * Matrix bot receives anything. One virtual thread per connected bot; it runs the owning tenant's
 * {@code TenantContext} itself (no request exists to resolve it from) and clears it after every
 * iteration, so no thread is ever left holding a tenant.
 *
 * <p>Off unless {@code app.matrix.sync.enabled=true}. Run it in ONE process per bot: two syncing
 * processes would each answer every message. The position ({@code next_batch}) is persisted after a
 * batch has been handled, so a restart resumes instead of replaying; the very first sync of a
 * connection only records the position and skips history. Failures back off exponentially
 * (honouring the homeserver's {@code retry_after_ms}), and a rejected token ends the loop and marks
 * the credential invalid instead of hammering the homeserver.
 */
@Component
@ConditionalOnProperty(name = "app.matrix.sync.enabled", havingValue = "true")
public class MatrixSyncService {

    private static final Logger log = LoggerFactory.getLogger(MatrixSyncService.class);
    private static final String REQUESTED_BY_PREFIX = "system:matrix-sync:";
    static final int SYNC_TIMEOUT_MS = 30_000;
    private static final long SHED_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final TenantResolutionService tenantResolutionService;
    private final MatrixCredentialService credentialService;
    private final MatrixApiClient apiClient;
    private final MatrixEventService eventService;
    private final Map<String, Syncer> syncers = new ConcurrentHashMap<>();

    /** Test seam: shortens the long poll. */
    private final int syncTimeoutMs;

    @org.springframework.beans.factory.annotation.Autowired
    public MatrixSyncService(
            TenantResolutionService tenantResolutionService,
            MatrixCredentialService credentialService,
            MatrixApiClient apiClient,
            MatrixEventService eventService) {
        this(tenantResolutionService, credentialService, apiClient, eventService, SYNC_TIMEOUT_MS);
    }

    MatrixSyncService(
            TenantResolutionService tenantResolutionService,
            MatrixCredentialService credentialService,
            MatrixApiClient apiClient,
            MatrixEventService eventService,
            int syncTimeoutMs) {
        this.tenantResolutionService = tenantResolutionService;
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.eventService = eventService;
        this.syncTimeoutMs = syncTimeoutMs;
    }

    /** Restores a sync loop for every connected bot after a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        for (TenantResolutionService.AccountTenant account :
                tenantResolutionService.listAccounts(ExternalAccountProvider.MATRIX)) {
            ensureSyncing(account.externalAccountId(), account.tenantSchema());
        }
    }

    /** Starts syncing this bot unless it already is. */
    public void ensureSyncing(String botUserId, String tenantSchema) {
        syncers.compute(
                botUserId,
                (id, existing) -> {
                    if (existing != null && existing.running()) {
                        return existing;
                    }
                    Syncer syncer = new Syncer(botUserId, tenantSchema);
                    syncer.thread =
                            TenantAwareExecutors.startVirtualThread(
                                    "matrix-sync-" + lastFour(botUserId), syncer::run);
                    log.info("Matrix sync started for bot ***{}", lastFour(botUserId));
                    return syncer;
                });
    }

    /**
     * A new connection replaces the old one: the loop restarts on the new credential and position.
     */
    public void restartSync(String botUserId, String tenantSchema) {
        stopSync(botUserId);
        ensureSyncing(botUserId, tenantSchema);
    }

    public void stopSync(String botUserId) {
        Syncer syncer = syncers.remove(botUserId);
        if (syncer != null) {
            syncer.stop();
        }
    }

    @PreDestroy
    void shutdown() {
        syncers.values().forEach(Syncer::stop);
        syncers.clear();
    }

    private final class Syncer {
        private final String botUserId;
        private final String tenantSchema;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile Thread thread;
        private String since;
        private boolean sinceLoaded;

        Syncer(String botUserId, String tenantSchema) {
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
            int failures = 0;
            while (!stopped.get()) {
                TenantContext.set(
                        TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + botUserId, Set.of()));
                try {
                    Optional<MatrixCredentialService.ActiveConnection> active =
                            credentialService.activeFor(botUserId);
                    if (active.isEmpty()) {
                        log.info(
                                "Matrix sync stopped for bot ***{}: credential no longer active",
                                lastFour(botUserId));
                        syncers.remove(botUserId, this);
                        return;
                    }
                    MatrixCredentialService.ActiveConnection connection = active.get();
                    if (!sinceLoaded) {
                        since = connection.sinceToken();
                        sinceLoaded = true;
                    }
                    boolean initial = since == null;
                    JsonNode response =
                            apiClient.sync(
                                    connection.endpoint(), since, initial ? 0 : syncTimeoutMs);
                    if (stopped.get()) {
                        // Replaced or disconnected while the long poll was open: a newer loop owns
                        // this
                        // bot now, so this response must not be handled or its position saved.
                        return;
                    }
                    Batch batch = MatrixSyncParser.parse(response, connection.botUserId());
                    if (!eventService.process(connection, batch, !initial)) {
                        // Inbound executor full: handle this batch again from the same position.
                        sleep(SHED_BACKOFF_MS);
                        continue;
                    }
                    if (batch.nextBatch() != null
                            && (initial
                                    || !batch.events().isEmpty()
                                    || !batch.invites().isEmpty())) {
                        credentialService.saveSinceToken(botUserId, batch.nextBatch());
                    }
                    if (batch.nextBatch() != null) {
                        since = batch.nextBatch();
                    }
                    failures = 0;
                } catch (MatrixApiException e) {
                    if (stopped.get()) {
                        return;
                    }
                    if (e.isUnknownToken()) {
                        log.warn(
                                "Matrix homeserver rejected the token for bot ***{}; sync stopped",
                                lastFour(botUserId));
                        credentialService.markInvalid(botUserId);
                        syncers.remove(botUserId, this);
                        return;
                    }
                    if (since != null && e.status() == 400 && "M_UNKNOWN_POS".equals(e.errcode())) {
                        // The homeserver forgot our position: start over without replaying history.
                        log.warn(
                                "Matrix sync position expired for bot ***{}; resyncing from now",
                                lastFour(botUserId));
                        since = null;
                        credentialService.saveSinceToken(botUserId, null);
                        continue;
                    }
                    failures++;
                    log.warn(
                            "Matrix sync failed for bot ***{} ({} in a row): {}",
                            lastFour(botUserId),
                            failures,
                            e.getMessage());
                    sleep(Math.max(e.retryAfterMs(), backoffMs(failures)));
                } catch (RuntimeException e) {
                    if (stopped.get()) {
                        return;
                    }
                    failures++;
                    log.warn(
                            "Matrix sync failed for bot ***{} ({} in a row): {}",
                            lastFour(botUserId),
                            failures,
                            e.getMessage());
                    sleep(backoffMs(failures));
                } finally {
                    TenantContext.clear();
                }
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(Math.min(millis, MAX_BACKOFF_SECONDS * 1000));
            } catch (InterruptedException e) {
                stopped.set(true);
                Thread.currentThread().interrupt();
            }
        }
    }

    static long backoffMs(int failures) {
        return Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(failures, 6)) * 1000;
    }

    private static String lastFour(String value) {
        return value == null || value.length() < 4 ? "***" : value.substring(value.length() - 4);
    }
}
