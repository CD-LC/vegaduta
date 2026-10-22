package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Pulls Signal messages from the operator's signal-cli REST API. Signal has no push to a public
 * URL, so this is the inbound path on every install (no webhook counterpart exists). Each message
 * is handed to {@link SignalInboundService}.
 *
 * <p>How it receives follows signal-cli's mode, read from {@code GET /v1/about}: long polling
 * {@code GET /v1/receive/{number}} for "normal" and "native", the receive websocket for "json-rpc".
 * {@code app.signal.receive.mode} forces {@code poll} or {@code websocket}; the default {@code
 * auto} asks the server. Failures back off exponentially (1 s up to 60 s) and the receiver
 * reconnects by itself, so a signal-cli restart needs no action here.
 *
 * <p>signal-cli removes a message once it has been delivered, so a message that could not be
 * handled because the inbound executor was full is offered again a few times before it is dropped
 * with a warning; it is never silently lost on the first refusal.
 *
 * <p>One virtual thread per connected number. It sets the owning tenant's {@code TenantContext}
 * itself, from the number-to-tenant link, because no request exists to resolve it from, and clears
 * it after every iteration. Run it in ONE process per number; two receivers on one number would
 * split its messages between them.
 *
 * <p>On by default but idle until a Signal connection exists; {@code
 * app.signal.receive.enabled=false} removes it (for example in a process that must not receive).
 */
@Component
@ConditionalOnProperty(
        name = "app.signal.receive.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SignalReceiveService {

    private static final Logger log = LoggerFactory.getLogger(SignalReceiveService.class);
    private static final String REQUESTED_BY_PREFIX = "system:signal-receive:";
    private static final long SHED_BACKOFF_MS = 2_000;
    private static final int SHED_ATTEMPTS = 5;
    private static final long MAX_BACKOFF_SECONDS = 60;
    private static final long MIN_POLL_INTERVAL_MS = 1_000;
    private static final long REFRESH_INTERVAL_MS = 30_000;
    private static final int PROBLEM_AFTER_FAILURES = 3;

    private enum Mode {
        POLL,
        WEBSOCKET
    }

    private final TenantResolutionService tenantResolutionService;
    private final SignalCredentialRepository credentialRepository;
    private final SignalCredentialService credentialService;
    private final SignalApiClient apiClient;
    private final SignalInboundService inboundService;
    private final String configuredMode;
    private final int pollSeconds;
    private final Map<String, Receiver> receivers = new ConcurrentHashMap<>();
    private final Map<String, String> problems = new ConcurrentHashMap<>();

    @Autowired
    public SignalReceiveService(
            TenantResolutionService tenantResolutionService,
            SignalCredentialRepository credentialRepository,
            SignalCredentialService credentialService,
            SignalApiClient apiClient,
            SignalInboundService inboundService,
            @Value("${app.signal.receive.mode:auto}") String configuredMode,
            @Value("${app.signal.receive.poll-seconds:15}") int pollSeconds) {
        this.tenantResolutionService = tenantResolutionService;
        this.credentialRepository = credentialRepository;
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.inboundService = inboundService;
        this.configuredMode =
                configuredMode == null ? "auto" : configuredMode.strip().toLowerCase(Locale.ROOT);
        this.pollSeconds = Math.max(1, Math.min(pollSeconds, 25));
    }

    /** Restores a receiver for every connected number after a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        try {
            for (TenantResolutionService.AccountTenant account :
                    tenantResolutionService.listAccounts(ExternalAccountProvider.SIGNAL)) {
                ensureReceiving(account.externalAccountId(), account.tenantSchema());
            }
        } catch (RuntimeException e) {
            // Never let an optional channel stop the application from starting.
            log.warn("Could not restore Signal receivers: {}", e.getMessage());
        }
    }

    /**
     * Starts receiving for this number unless it already is. Safe to call again on reactivation.
     */
    public void ensureReceiving(String number, String tenantSchema) {
        receivers.compute(
                number,
                (id, existing) -> {
                    if (existing != null && existing.running()) {
                        return existing;
                    }
                    Receiver receiver = new Receiver(number, tenantSchema);
                    receiver.thread =
                            TenantAwareExecutors.startVirtualThread(
                                    "signal-receive-" + SignalAddress.mask(number).substring(3),
                                    receiver::run);
                    log.info("Signal receiving started for {}", SignalAddress.mask(number));
                    return receiver;
                });
    }

    public void stopReceiving(String number) {
        Receiver receiver = receivers.remove(number);
        problems.remove(number);
        if (receiver != null) {
            receiver.stop();
        }
    }

    /**
     * A short description of what is wrong with this number's receiver, or null when it is healthy.
     */
    public String problem(String number) {
        return problems.get(number);
    }

    @PreDestroy
    void shutdown() {
        receivers.values().forEach(Receiver::stop);
        receivers.clear();
    }

    private final class Receiver {
        private final String number;
        private final String tenantSchema;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile Thread thread;

        /**
         * Consecutive failures; a connection that came up (poll answered, websocket opened) resets
         * it.
         */
        private int failures;

        Receiver(String number, String tenantSchema) {
            this.number = number;
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
            Mode mode = null;
            while (!stopped.get()) {
                TenantContext.set(
                        TenantContext.of(
                                tenantSchema,
                                REQUESTED_BY_PREFIX + SignalAddress.mask(number),
                                Set.of()));
                try {
                    SignalConnection connection = currentConnection();
                    if (connection == null) {
                        log.info(
                                "Signal receiving stopped for {}: connection no longer active",
                                SignalAddress.mask(number));
                        receivers.remove(number, this);
                        problems.remove(number);
                        return;
                    }
                    if (mode == null) {
                        mode = resolveMode(connection);
                    }
                    if (mode == Mode.WEBSOCKET) {
                        streamLoop(connection);
                    } else {
                        pollOnce(connection);
                    }
                    failures = 0;
                    problems.remove(number);
                } catch (RuntimeException e) {
                    if (stopped.get()) {
                        return;
                    }
                    failures++;
                    mode = null; // re-read the server's mode: it may have been restarted in
                    // another one
                    if (failures >= PROBLEM_AFTER_FAILURES) {
                        problems.put(number, "signal-cli not reachable: " + brief(e));
                    }
                    log.warn(
                            "Signal receive failed for {} ({} in a row): {}",
                            SignalAddress.mask(number),
                            failures,
                            brief(e));
                    sleep(Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(failures, 6)) * 1000);
                } finally {
                    TenantContext.clear();
                }
            }
        }

        private SignalConnection currentConnection() {
            Optional<SignalCredential> credential =
                    credentialRepository.findFirstByOrderByCreatedAtAsc();
            if (credential.isEmpty()
                    || credential.get().getStatus() != SignalCredentialStatus.ACTIVE
                    || !number.equals(credential.get().getAccountNumber())) {
                return null;
            }
            return credentialService.connectionOf(credential.get());
        }

        private Mode resolveMode(SignalConnection connection) {
            return switch (configuredMode) {
                case "poll" -> Mode.POLL;
                case "websocket" -> Mode.WEBSOCKET;
                default ->
                        apiClient.about(connection).websocketReceive() ? Mode.WEBSOCKET : Mode.POLL;
            };
        }

        private void pollOnce(SignalConnection connection) {
            long started = System.nanoTime();
            JsonNode batch = apiClient.receive(connection, pollSeconds);
            for (JsonNode message : batch) {
                deliver(connection, message);
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (batch.isEmpty() && elapsedMs < MIN_POLL_INTERVAL_MS) {
                // A server that ignores the wait would otherwise be hammered in a tight loop.
                sleep(MIN_POLL_INTERVAL_MS - elapsedMs);
            }
        }

        /**
         * Holds the websocket open, handing each frame over on this thread, until it closes or the
         * credential changes.
         */
        private void streamLoop(SignalConnection initial) {
            SignalConnection connection = initial;
            long lastRefresh = System.currentTimeMillis();
            try (SignalStream stream = apiClient.openStream(connection)) {
                // Up: whatever drops it later is a new incident, not another step of the old
                // backoff.
                failures = 0;
                problems.remove(number);
                while (!stopped.get()) {
                    JsonNode message = stream.poll(Duration.ofSeconds(1));
                    if (message != null) {
                        deliver(connection, message);
                    }
                    if (System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MS) {
                        lastRefresh = System.currentTimeMillis();
                        SignalConnection fresh = currentConnection();
                        if (fresh == null || !fresh.sameEndpoint(connection)) {
                            return; // run() re-reads the credential and reconnects, or stops
                        }
                        connection = fresh; // sender policy can change while connected
                    }
                }
            } catch (InterruptedException e) {
                stopped.set(true);
                Thread.currentThread().interrupt();
            }
        }

        /** One message to the inbound pipeline; a bad message never stops the receiver. */
        private void deliver(SignalConnection connection, JsonNode message) {
            for (int attempt = 1; attempt <= SHED_ATTEMPTS && !stopped.get(); attempt++) {
                try {
                    if (inboundService.accept(connection, message)) {
                        return;
                    }
                } catch (RuntimeException e) {
                    log.warn(
                            "Failed to process Signal message for {}: {}",
                            SignalAddress.mask(number),
                            brief(e));
                    return;
                }
                sleep(SHED_BACKOFF_MS);
            }
            if (!stopped.get()) {
                log.warn(
                        "Dropped a Signal message for {}: the inbound executor stayed full",
                        SignalAddress.mask(number));
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
