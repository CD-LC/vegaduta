package com.agenticai.core.omnichannel.whatsappqr;

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
 * Pulls WhatsApp messages from the operator's whatsapp-qr bridge. Linked-device WhatsApp has no
 * push to a public URL, so this long-poll is the inbound path on every install - which is also why
 * the channel works on a personal laptop: the only socket that leaves the machine is the bridge's
 * outbound one to WhatsApp itself.
 *
 * <p>{@code GET /sessions/{key}/events} answers early on any status transition, so the poll doubles
 * as the session's state feed: CONNECTED flips the row PAIRING -&gt; ACTIVE (a scan lands even with
 * nobody watching the console), LOGGED_OUT marks it DISCONNECTED and ends the loop. Undelivered
 * events stay queued on the bridge, so a refused message is offered again a few times before it is
 * dropped with a warning; it is never silently lost on the first refusal.
 *
 * <p>One virtual thread per session key. It sets the owning tenant's {@code TenantContext} itself,
 * from the sessionKey-to-tenant link, because no request exists to resolve it from, and clears it
 * after every iteration. Run it in ONE process per key; two receivers on one session would split
 * its messages between them.
 *
 * <p>On by default but idle until a connection exists; {@code app.whatsappqr.receive.enabled=false}
 * removes it (for example in a process that must not receive).
 */
@Component
@ConditionalOnProperty(
        name = "app.whatsappqr.receive.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WhatsAppQrReceiveService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppQrReceiveService.class);
    private static final String REQUESTED_BY_PREFIX = "system:whatsapp-qr-receive:";
    private static final long SHED_BACKOFF_MS = 2_000;
    private static final int SHED_ATTEMPTS = 5;
    private static final long MAX_BACKOFF_SECONDS = 60;
    private static final long MIN_POLL_INTERVAL_MS = 1_000;
    private static final int PROBLEM_AFTER_FAILURES = 3;
    /** After the first few failures a steady one-WARN-per-minute loop is noise; log every Nth. */
    private static final int QUIET_LOG_EVERY = 30;
    static final String NOT_CONFIGURED =
            "WhatsApp bridge not configured: set APP_WHATSAPPQR_BRIDGE_URL and"
                    + " APP_WHATSAPPQR_BRIDGE_SECRET, then restart core";

    private final TenantResolutionService tenantResolutionService;
    private final WhatsAppQrConnectionRepository connectionRepository;
    private final WhatsAppQrService service;
    private final WhatsAppQrClient client;
    private final WhatsAppQrInboundService inboundService;
    private final int pollSeconds;
    private final Map<String, Receiver> receivers = new ConcurrentHashMap<>();
    private final Map<String, String> problems = new ConcurrentHashMap<>();

    public WhatsAppQrReceiveService(
            TenantResolutionService tenantResolutionService,
            WhatsAppQrConnectionRepository connectionRepository,
            WhatsAppQrService service,
            WhatsAppQrClient client,
            WhatsAppQrInboundService inboundService,
            @Value("${app.whatsappqr.receive.poll-seconds:15}") int pollSeconds) {
        this.tenantResolutionService = tenantResolutionService;
        this.connectionRepository = connectionRepository;
        this.service = service;
        this.client = client;
        this.inboundService = inboundService;
        this.pollSeconds = Math.max(1, Math.min(pollSeconds, 20));
    }

    /** Restores a receiver for every linked session after a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        if (!client.configured()) {
            // A linked session can outlive the operator's bridge configuration (env rewritten,
            // install moved). Polling a blank URL can never succeed: say so once and stay idle.
            log.warn(
                    "WhatsApp QR: app.whatsappqr.bridge-url / bridge-secret are not set - linked"
                            + " sessions stay idle until the bridge is configured and core restarts.");
            return;
        }
        try {
            for (TenantResolutionService.AccountTenant account :
                    tenantResolutionService.listAccounts(ExternalAccountProvider.WHATSAPP_QR)) {
                ensureReceiving(account.externalAccountId(), account.tenantSchema());
            }
        } catch (RuntimeException e) {
            // Never let an optional channel stop the application from starting.
            log.warn("Could not restore WhatsApp QR receivers: {}", e.getMessage());
        }
    }

    /** Starts receiving for this session key unless it already is. Safe to call again. */
    public void ensureReceiving(String sessionKey, String tenantSchema) {
        if (!client.configured()) {
            problems.put(sessionKey, NOT_CONFIGURED);
            return;
        }
        receivers.compute(
                sessionKey,
                (id, existing) -> {
                    if (existing != null && existing.running()) {
                        return existing;
                    }
                    Receiver receiver = new Receiver(sessionKey, tenantSchema);
                    receiver.thread =
                            TenantAwareExecutors.startVirtualThread(
                                    "whatsapp-qr-receive-" + sessionKey.substring(0, 8), receiver::run);
                    log.info("WhatsApp QR receiving started for session {}", sessionKey.substring(0, 8));
                    return receiver;
                });
    }

    public void stopReceiving(String sessionKey) {
        Receiver receiver = receivers.remove(sessionKey);
        problems.remove(sessionKey);
        if (receiver != null) {
            receiver.stop();
        }
    }

    /** A short description of what is wrong with this session's receiver, or null when healthy. */
    public String problem(String sessionKey) {
        return problems.get(sessionKey);
    }

    @PreDestroy
    void shutdown() {
        receivers.values().forEach(Receiver::stop);
        receivers.clear();
    }

    private final class Receiver {
        private final String sessionKey;
        private final String tenantSchema;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile Thread thread;
        /** Consecutive failures; a poll that answered resets it. */
        private int failures;

        Receiver(String sessionKey, String tenantSchema) {
            this.sessionKey = sessionKey;
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
            while (!stopped.get()) {
                TenantContext.set(
                        TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + sessionKey.substring(0, 8), Set.of()));
                try {
                    if (!connectionAlive()) {
                        log.info("WhatsApp QR receiving stopped for session {}: connection is gone", sessionKey.substring(0, 8));
                        receivers.remove(sessionKey, this);
                        problems.remove(sessionKey);
                        return;
                    }
                    JsonNode result = pollOnce();
                    failures = 0;
                    problems.remove(sessionKey);
                    if (applyStatus(result)) {
                        // LOGGED_OUT: the link is dead; stopReceiving already removed us.
                        return;
                    }
                } catch (RuntimeException e) {
                    if (stopped.get()) {
                        return;
                    }
                    if (e instanceof WhatsAppQrApiException api
                            && api.status() == WhatsAppQrApiException.REFUSED) {
                        // Refused by policy (or no URL at all) is configuration, not weather:
                        // retrying cannot help. Record it for the console and end the loop
                        // instead of one WARN per minute forever.
                        problems.put(sessionKey, "WhatsApp bridge refused: " + brief(e));
                        log.warn(
                                "WhatsApp QR receiving stopped for session {}: {}",
                                sessionKey.substring(0, 8),
                                brief(e));
                        receivers.remove(sessionKey, this);
                        return;
                    }
                    failures++;
                    if (failures >= PROBLEM_AFTER_FAILURES) {
                        problems.put(sessionKey, "WhatsApp bridge not reachable: " + brief(e));
                    }
                    if (failures <= PROBLEM_AFTER_FAILURES || failures % QUIET_LOG_EVERY == 0) {
                        log.warn(
                                "WhatsApp QR receive failed for session {} ({} in a row): {}",
                                sessionKey.substring(0, 8),
                                failures,
                                brief(e));
                    } else {
                        log.debug(
                                "WhatsApp QR receive failed for session {} ({} in a row): {}",
                                sessionKey.substring(0, 8),
                                failures,
                                brief(e));
                    }
                    sleep(Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(failures, 6)) * 1000);
                } finally {
                    TenantContext.clear();
                }
            }
        }

        /** The row must still exist and not be DISCONNECTED - the session's tenant-side identity. */
        private boolean connectionAlive() {
            Optional<WhatsAppQrConnection> row = connectionRepository.findBySessionKey(sessionKey);
            return row.isPresent() && row.get().getStatus() != WhatsAppQrStatus.DISCONNECTED;
        }

        private JsonNode pollOnce() {
            long started = System.nanoTime();
            JsonNode result;
            try {
                result = client.receive(sessionKey, pollSeconds);
            } catch (WhatsAppQrApiException e) {
                if (e.status() == 404) {
                    // A restarted bridge forgot the session; its auth dir survived, so ensure
                    // brings the socket back and the stored credentials resume it.
                    client.ensureSession(sessionKey);
                    return null;
                }
                throw e;
            }
            for (JsonNode event : result.path("events")) {
                deliver(event);
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (result.path("events").isEmpty() && elapsedMs < MIN_POLL_INTERVAL_MS) {
                // A server that ignores the wait would otherwise be hammered in a tight loop.
                sleep(MIN_POLL_INTERVAL_MS - elapsedMs);
            }
            return result;
        }

        /** Bridge status -&gt; row transitions. @return true when the loop must end (logged out). */
        private boolean applyStatus(JsonNode result) {
            if (result == null) {
                return false;
            }
            String status = result.path("status").asText("");
            switch (status) {
                case "CONNECTED" -> service.markConnected(
                        sessionKey, result.path("phoneNumber").asText(null));
                case "LOGGED_OUT" -> {
                    service.markLoggedOut(sessionKey);
                    stopReceiving(sessionKey);
                    return true;
                }
                default -> {
                    // QR_PENDING / RECONNECTING: nothing to persist; the console reads them live.
                }
            }
            return false;
        }

        /** One message to the inbound pipeline; a bad message never stops the receiver. */
        private void deliver(JsonNode event) {
            for (int attempt = 1; attempt <= SHED_ATTEMPTS && !stopped.get(); attempt++) {
                try {
                    if (inboundService.accept(event)) {
                        return;
                    }
                } catch (RuntimeException e) {
                    log.warn(
                            "Failed to process a WhatsApp QR message for session {}: {}",
                            sessionKey.substring(0, 8),
                            brief(e));
                    return;
                }
                sleep(SHED_BACKOFF_MS);
            }
            if (!stopped.get()) {
                log.warn("Dropped a WhatsApp QR message for session {}: the inbound executor stayed full", sessionKey.substring(0, 8));
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

    private static String brief(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
