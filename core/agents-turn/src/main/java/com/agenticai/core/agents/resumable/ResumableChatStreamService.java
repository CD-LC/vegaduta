package com.agenticai.core.agents.resumable;

import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.Entry;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.ReadResult;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.TurnMeta;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

/**
 * Resumable chat streams ({@code app.chat.resumable-streams.*}, default OFF): a long answer
 * survives a page refresh, a sleeping tab, a flaky mobile network or a navigation.
 *
 * <h2>How it works</h2>
 *
 * <ol>
 *   <li><b>Generation is decoupled from the HTTP subscriber.</b> {@link #start} subscribes to the
 *       chat Flux itself (a detached subscription) and tees every chunk into a bounded per-turn
 *       {@link ResumableStreamBuffer} with a monotonic event id. The HTTP response is just a reader
 *       of that buffer, so a client disconnect no longer cancels the model call.
 *   <li><b>Resume:</b> {@code GET /api/chat/turns/{turnId}/stream?after=N} ({@link
 *       ResumableChatTurnController}) replays buffered events with id &gt; N and then follows the
 *       turn live until it ends. The original POST stream is the same reader starting at 0, with
 *       the event id as the SSE {@code id:} field and the turn id in the {@code X-Turn-Id} header.
 *   <li><b>Cost safety:</b> the detached subscription is the SAME pipeline as before - run caps,
 *       per-user budgets and the tool step limit all still apply. When nobody is watching a turn
 *       (no HTTP reader attached) for {@code detach-timeout-seconds} it is cancelled, which fires
 *       the normal partial-reply persistence. An explicit Stop still works through {@code
 *       ChatTurnCancelService}, which is wired INSIDE the chat Flux.
 * </ol>
 *
 * <h2>Opt-in per request</h2>
 *
 * Only a request that sends {@value #OPT_IN_HEADER}: 1 is detached and framed with ids. The LiveKit
 * voice bridge, IDE plugins and every other consumer of {@code POST /api/agents/{id}/chat} never
 * send it, so their bytes - and their "client disconnect = stop generating" behaviour - are
 * unchanged even with the flag on. With the flag off nobody is detached whatever they send.
 *
 * <h2>Authorisation</h2>
 *
 * A turn is addressable only inside the tenant that started it (the tenant is part of the buffer
 * lookup and of every Redis key) and only by the subject that started it, and the caller must still
 * hold RUNNER on the agent - the same bar as starting the turn. Anything else answers 404, never
 * 403, so a turn id is not even confirmable to a stranger.
 */
@Service
public class ResumableChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ResumableChatStreamService.class);

    /** Request header a client sends to opt a turn into detachment + event ids. */
    public static final String OPT_IN_HEADER = "X-Resumable-Stream";

    /** Response header carrying the turn id (exposed to browsers in WebConfig). */
    public static final String TURN_ID_HEADER = "X-Turn-Id";

    /** SSE event name of the final frame; its data is {@code {"status":"...","lastEventId":N}}. */
    public static final String TURN_END_EVENT = "turn-end";

    static final String STATUS_COMPLETE = "complete";
    static final String STATUS_ERROR = "error";
    static final String STATUS_DETACH_TIMEOUT = "detach-timeout";
    static final String STATUS_ABORTED = "aborted";
    static final String STATUS_RUNNING = "running";
    static final String STATUS_UNKNOWN = "unknown";

    /**
     * Reader poll on the node that generates the turn: its own producer wakes readers on every
     * chunk, so this is only a safety net.
     */
    static final Duration POLL_INTERVAL_LOCAL = Duration.ofSeconds(1);

    /** Reader poll when another replica produces (Redis mode): polling is then the only signal. */
    static final Duration POLL_INTERVAL_REMOTE = Duration.ofMillis(500);

    /**
     * Default cap on concurrently open resume readers per tenant+user (a reconnect storm after a
     * network blip).
     */
    static final int DEFAULT_MAX_READERS_PER_OWNER = 8;

    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final String NO_TENANT = "_none";
    private static final Pattern TURN_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final TurnGone TURN_GONE = new TurnGone();

    private final boolean enabled;
    private final ResumableStreamBuffer buffer;
    private final AgentService agentService;
    private final Duration detachTimeout;
    private final LongSupplier clock;
    private final Map<String, TurnRun> runs = new ConcurrentHashMap<>();
    private final ResumeReaderLimiter readers;

    @Autowired
    public ResumableChatStreamService(
            @Value("${app.chat.resumable-streams.enabled:false}") boolean enabled,
            @Value("${app.chat.resumable-streams.store:memory}") String store,
            @Value("${app.chat.resumable-streams.buffer-max-events:5000}") int bufferMaxEvents,
            @Value("${app.chat.resumable-streams.ttl-seconds:600}") long ttlSeconds,
            @Value("${app.chat.resumable-streams.detach-timeout-seconds:900}")
                    long detachTimeoutSeconds,
            @Value("${app.chat.resumable-streams.max-bytes-per-turn:4194304}") long maxBytesPerTurn,
            @Value("${app.chat.resumable-streams.max-total-bytes:268435456}") long maxTotalBytes,
            @Value("${app.chat.resumable-streams.max-readers-per-user:8}") int maxReadersPerUser,
            AgentService agentService,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this(
                enabled,
                ResumableStreamBuffers.build(
                        store,
                        bufferMaxEvents,
                        ttlSeconds,
                        detachTimeoutSeconds,
                        maxBytesPerTurn,
                        maxTotalBytes,
                        redisProvider),
                agentService,
                Duration.ofSeconds(Math.max(1L, detachTimeoutSeconds)),
                System::currentTimeMillis,
                maxReadersPerUser);
    }

    /** Test seam: inject the buffer, the detach timeout and the clock. */
    ResumableChatStreamService(
            boolean enabled,
            ResumableStreamBuffer buffer,
            AgentService agentService,
            Duration detachTimeout,
            LongSupplier clock) {
        this(enabled, buffer, agentService, detachTimeout, clock, DEFAULT_MAX_READERS_PER_OWNER);
    }

    ResumableChatStreamService(
            boolean enabled,
            ResumableStreamBuffer buffer,
            AgentService agentService,
            Duration detachTimeout,
            LongSupplier clock,
            int maxReadersPerOwner) {
        this.readers = new ResumeReaderLimiter(maxReadersPerOwner);
        this.enabled = enabled;
        this.buffer = buffer;
        this.agentService = agentService;
        this.detachTimeout = detachTimeout;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True when the flag is on AND the request opted in. Everything else takes the unchanged path.
     */
    public boolean accepts(String optInHeader) {
        if (!enabled || optInHeader == null) {
            return false;
        }
        String v = optInHeader.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /** What {@link #start} hands the controller. */
    public record Started(String turnId, Flux<ServerSentEvent<String>> events) {

        /**
         * The same Flux typed as the controller's declared body. {@code AgentController#chat} is
         * declared {@code ResponseEntity<Flux<String>>} (its signature is a contract other code
         * compiles against); Spring's SSE return-value handling dispatches on the RUNTIME element
         * type, so an element that is a {@link ServerSentEvent} is written with its {@code
         * id:}/{@code event:} fields. Same bytes for {@code data:} as a bare String element.
         */
        @SuppressWarnings("unchecked")
        public Flux<String> asBodyFlux() {
            return (Flux<String>) (Flux<?>) events;
        }
    }

    /**
     * Starts generating {@code source} detached from the caller and returns the original request's
     * reader of it. Must be called on the request thread (the chat Flux's first, synchronous stage
     * needs the caller's {@link TenantContext}, exactly as when Spring subscribed to it).
     */
    public Started start(UUID agentId, String sessionId, Flux<String> source) {
        String turnId = UUID.randomUUID().toString();
        TurnMeta meta =
                new TurnMeta(
                        turnId,
                        tenantKey(),
                        subject(),
                        agentId.toString(),
                        sessionId,
                        clock.getAsLong());
        buffer.open(meta);
        TurnRun run = new TurnRun(turnId, TenantScope.capture());
        runs.put(turnId, run);
        // Nobody is attached yet: the first reader's subscribe cancels this, a reader that never
        // comes does not leak a generation.
        run.armDetachTimer();
        try {
            run.subscription =
                    source.subscribe(
                            chunk -> onChunk(run, chunk),
                            error -> onError(run, error),
                            () -> finishTurn(run, STATUS_COMPLETE));
        } catch (RuntimeException e) {
            // Nothing will ever read or finish this turn: close it so the timer and the buffer do
            // not linger.
            run.failure = e;
            finishTurn(run, STATUS_ERROR);
            throw e;
        }

        Flux<ServerSentEvent<String>> events =
                entries(meta.tenantId(), turnId, 0L, run)
                        .<ServerSentEvent<String>>handle(
                                (entry, sink) -> {
                                    // A turn that failed before producing anything must surface as
                                    // the ORIGINAL error, so
                                    // GlobalExceptionHandler maps it (402/503/...) exactly as it
                                    // did before detachment.
                                    if (entry.terminal()
                                            && STATUS_ERROR.equals(entry.payload())
                                            && entry.id() == 1L
                                            && run.failure != null) {
                                        sink.error(run.failure);
                                    } else {
                                        sink.next(toSse(entry));
                                    }
                                })
                        .doOnSubscribe(s -> run.attach())
                        .doFinally(
                                signal -> {
                                    run.detach();
                                    // The original reader left before the turn produced a single
                                    // event. Response headers (X-Turn-Id) only
                                    // go out with the first byte, so this client never learned the
                                    // id and nobody can ever resume or
                                    // cancel this turn: stop it now, exactly as a disconnect always
                                    // did, instead of letting it generate
                                    // (and bill) until the detach timeout.
                                    if (signal == SignalType.CANCEL && !run.produced) {
                                        run.cancelUnclaimed();
                                    }
                                });
        return new Started(turnId, events);
    }

    /**
     * Replays turn {@code turnId} after event {@code after} and follows it live to its end. Throws
     * {@link ResponseStatusException}: 404 for unknown / expired / not yours / flag off, 410 when
     * events after {@code after} were already dropped from the bounded buffer (the caller cannot
     * get a complete stream any more).
     */
    public Flux<ServerSentEvent<String>> resume(String turnId, Long after) {
        TurnMeta meta = authorize(turnId);
        long cursor = after == null ? 0L : Math.max(0L, after);
        ReadResult probe = buffer.read(meta.tenantId(), turnId, cursor);
        if (!probe.found()) {
            throw notFound();
        }
        if (probe.gap()) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "Part of this reply is no longer buffered.");
        }
        TurnRun run = runs.get(turnId);
        Flux<ServerSentEvent<String>> events =
                entries(meta.tenantId(), turnId, cursor, run)
                        .map(ResumableChatStreamService::toSse)
                        .doOnSubscribe(
                                s -> {
                                    if (run != null) {
                                        run.attach();
                                    }
                                })
                        .doFinally(
                                signal -> {
                                    if (run != null) {
                                        run.detach();
                                    }
                                });
        String ownerKey = meta.tenantId() + "|" + meta.ownerSubject();
        // Deferred so the cap is taken when the reader actually subscribes and always given back
        // when it ends.
        return Flux.defer(
                () -> {
                    if (!readers.acquire(ownerKey)) {
                        return Flux.<ServerSentEvent<String>>error(
                                new ResponseStatusException(
                                        HttpStatus.TOO_MANY_REQUESTS,
                                        "Too many reconnecting streams are open for this account."));
                    }
                    return withHeartbeat(events).doFinally(signal -> readers.release(ownerKey));
                });
    }

    /** The turn's state and the text it has produced so far, for reconciling a resumed stream. */
    public TurnSnapshot snapshot(String turnId) {
        TurnMeta meta = authorize(turnId);
        ReadResult read = buffer.read(meta.tenantId(), turnId, 0L);
        if (!read.found()) {
            throw notFound();
        }
        if (read.gap()) {
            return new TurnSnapshot(turnId, STATUS_UNKNOWN, 0L, null);
        }
        String status = STATUS_RUNNING;
        long last = 0L;
        StringBuilder text = new StringBuilder();
        for (Entry entry : read.entries()) {
            last = entry.id();
            if (entry.terminal()) {
                status = entry.payload();
            } else if (!entry.payload().startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER)
                    && !entry.payload().startsWith(AgentController.STREAM_ERROR_MARKER)) {
                text.append(entry.payload());
            }
        }
        return new TurnSnapshot(turnId, status, last, text.toString());
    }

    /**
     * {@code text} is null when the buffer no longer holds the whole reply. {@code error} is the
     * message the person was shown when the turn failed before producing text (status {@code
     * error}); null otherwise.
     */
    public record TurnSnapshot(
            String turnId, String status, long lastEventId, String text, String error) {
        public TurnSnapshot(String turnId, String status, long lastEventId, String text) {
            this(turnId, status, lastEventId, text, null);
        }

        public TurnSnapshot withError(String message) {
            return new TurnSnapshot(turnId, status, lastEventId, text, message);
        }
    }

    /**
     * The closing {@code turn-end} frame with the failure message added ({@code "error":"..."}) for
     * a turn that ended in {@code error}; any other event, or a null message, is returned
     * unchanged. See {@link ResumableSseFrames#withTurnEndError}.
     */
    public static ServerSentEvent<String> withTurnEndError(
            ServerSentEvent<String> event, String message) {
        return ResumableSseFrames.withTurnEndError(event, message);
    }

    /**
     * The authorisation rule, pure so it is testable without a context: same tenant AND same
     * subject as the caller who started the turn.
     */
    static boolean mayAccess(TurnMeta meta, String tenantKey, String subject) {
        return Objects.equals(meta.tenantId(), tenantKey)
                && Objects.equals(meta.ownerSubject(), subject);
    }

    /**
     * The turn's owner record, after the same tenant + subject + RUNNER checks every read goes
     * through (404 otherwise).
     */
    public TurnMeta authorizedMeta(String turnId) {
        return authorize(turnId);
    }

    private TurnMeta authorize(String turnId) {
        if (!enabled || turnId == null || !TURN_ID.matcher(turnId).matches()) {
            throw notFound();
        }
        String tenant = tenantKey();
        TurnMeta meta =
                buffer.meta(tenant, turnId).orElseThrow(ResumableChatStreamService::notFound);
        if (!mayAccess(meta, tenant, subject())) {
            throw notFound();
        }
        // Still allowed to run this agent? A revoked grant must not keep reading a live
        // conversation.
        agentService.requireAccess(UUID.fromString(meta.agentId()), PermissionLevel.RUNNER);
        return meta;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "This reply is no longer available.");
    }

    static String tenantKey() {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return tenant == null || tenant.isBlank() ? NO_TENANT : tenant;
    }

    static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    // ---- generation side
    // -------------------------------------------------------------------------------

    private void onChunk(TurnRun run, String chunk) {
        run.produced = true;
        try {
            buffer.append(run.turnId, chunk == null ? "" : chunk);
        } catch (RuntimeException e) {
            log.warn(
                    "Resumable chat stream: could not buffer a chunk of turn {} - live readers continue, a resume may be incomplete.",
                    run.turnId,
                    e);
        }
        run.wake.tryEmitNext(1L);
    }

    private void onError(TurnRun run, Throwable error) {
        run.failure = error;
        finishTurn(run, STATUS_ERROR);
    }

    private void finishTurn(TurnRun run, String status) {
        if (!run.finished.compareAndSet(false, true)) {
            return;
        }
        run.cancelDetachTimer();
        try {
            buffer.finish(run.turnId, status);
        } catch (RuntimeException e) {
            log.warn("Resumable chat stream: could not finish turn {}", run.turnId, e);
        }
        runs.remove(run.turnId, run);
        run.wake.tryEmitNext(1L);
        run.wake.tryEmitComplete();
    }

    /** Live state of one detached generation on this node. */
    private final class TurnRun {
        private final String turnId;
        private final TenantScope tenant;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final Sinks.Many<Long> wake = Sinks.many().multicast().directBestEffort();
        private volatile Throwable failure;

        /**
         * True once the source emitted anything: from then on a client can hold the turn id and
         * resume.
         */
        private volatile boolean produced;

        private volatile Disposable subscription;
        private Disposable detachTimer;
        private int watchers;

        private TurnRun(String turnId, TenantScope tenant) {
            this.turnId = turnId;
            this.tenant = tenant;
        }

        private synchronized void attach() {
            watchers++;
            cancelDetachTimer();
        }

        private synchronized void detach() {
            watchers = Math.max(0, watchers - 1);
            if (watchers == 0 && !finished.get()) {
                armDetachTimer();
            }
        }

        private synchronized void armDetachTimer() {
            cancelDetachTimer();
            detachTimer =
                    TenantAwareSchedulers.parallel()
                            .schedule(
                                    this::onDetachTimeout,
                                    detachTimeout.toMillis(),
                                    TimeUnit.MILLISECONDS);
        }

        private synchronized void cancelDetachTimer() {
            if (detachTimer != null) {
                detachTimer.dispose();
                detachTimer = null;
            }
        }

        /**
         * The original reader cancelled before any output (see start): stop the generation and
         * close the turn.
         */
        private void cancelUnclaimed() {
            if (finished.get()) {
                return;
            }
            log.info(
                    "Resumable chat stream: turn {} was abandoned before its first event - cancelling the generation.",
                    turnId);
            tenant.run(
                    () -> {
                        Disposable current = subscription;
                        if (current != null) {
                            current.dispose();
                        }
                    });
            finishTurn(this, STATUS_ABORTED);
        }

        private void onDetachTimeout() {
            synchronized (this) {
                if (finished.get() || watchers > 0) {
                    return;
                }
            }
            log.info(
                    "Resumable chat stream: turn {} had no reader for {}s - cancelling the detached generation.",
                    turnId,
                    detachTimeout.toSeconds());
            // The cancel fires the chat Flux's own cleanup (partial-reply persistence), which needs
            // the
            // tenant context the turn started under - this timer thread has none.
            tenant.run(
                    () -> {
                        Disposable current = subscription;
                        if (current != null) {
                            current.dispose();
                        }
                    });
            finishTurn(this, STATUS_DETACH_TIMEOUT);
        }
    }

    // ---- reading side
    // ----------------------------------------------------------------------------------

    /**
     * Replays entries with id &gt; {@code after}, then follows the turn until its terminal entry.
     * Wake-ups from this node's own producer make same-node reads immediate; a poll tick covers a
     * producer on another replica (Redis mode) and any missed wake-up.
     */
    private Flux<Entry> entries(String tenantKey, String turnId, long after, TurnRun run) {
        AtomicLong cursor = new AtomicLong(Math.max(0L, after));
        Flux<Long> wakeups = run == null ? Flux.<Long>empty() : run.wake.asFlux();
        return Flux.merge(
                        Flux.just(0L),
                        wakeups,
                        Flux.interval(
                                run == null ? POLL_INTERVAL_REMOTE : POLL_INTERVAL_LOCAL,
                                TenantAwareSchedulers.boundedElastic()))
                .onBackpressureLatest()
                .publishOn(TenantAwareSchedulers.boundedElastic())
                .concatMap(tick -> readBatch(tenantKey, turnId, cursor), 1)
                .takeUntil(Entry::terminal)
                .onErrorResume(TurnGone.class, e -> Flux.<Entry>empty());
    }

    private Flux<Entry> readBatch(String tenantKey, String turnId, AtomicLong cursor) {
        ReadResult result = buffer.read(tenantKey, turnId, cursor.get());
        if (!result.found() || result.gap()) {
            return Flux.error(TURN_GONE);
        }
        if (result.entries().isEmpty()) {
            return Flux.<Entry>empty();
        }
        return Flux.fromIterable(result.entries()).doOnNext(e -> cursor.set(e.id()));
    }

    static ServerSentEvent<String> toSse(Entry entry) {
        return ResumableSseFrames.toSse(entry);
    }

    /**
     * SSE comment keepalives for a RESUME stream, so a long silent tool call does not read as a
     * dead connection to the client's idle timer or an intermediary. Never added to the original
     * POST stream: a byte before the first token would commit the response and lose the
     * pre-first-token status mapping.
     */
    private static Flux<ServerSentEvent<String>> withHeartbeat(
            Flux<ServerSentEvent<String>> events) {
        Sinks.Empty<Void> done = Sinks.empty();
        Flux<ServerSentEvent<String>> main = events.doFinally(signal -> done.tryEmitEmpty());
        Flux<ServerSentEvent<String>> heartbeat =
                Flux.interval(HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL)
                        .map(tick -> ServerSentEvent.<String>builder().comment("keepalive").build())
                        .takeUntilOther(done.asMono());
        return Flux.merge(main, heartbeat);
    }

    /**
     * Internal: the buffer no longer holds (all of) the turn. Stackless on purpose - it is control
     * flow.
     */
    private static final class TurnGone extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private TurnGone() {
            super("turn gone", null, false, false);
        }
    }
}
