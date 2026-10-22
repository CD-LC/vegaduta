package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.Begin;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.InputEvent;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.SessionRef;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.TokenRefresh;
import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverDtos.View;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The person's side of browser takeover: see where a takeover stands, pause a session, take
 * control, watch the page (a bounded-rate JPEG stream over Server-Sent Events, the platform's
 * existing streaming pattern), send input, renew the short-lived token and hand the page back.
 *
 * <p>Thin on purpose: every rule (owner = the session's tenant AND user, signed token, state, rate
 * limit, size caps, site allow-list) lives in {@link BrowserTakeoverService}, which answers 404 for
 * someone else's session and for the whole surface until {@code app.browser.takeover.enabled} (and
 * browser v2) is on. No {@code SecurityConfig} entry: the paths fall through to {@code
 * anyRequest().authenticated()}. The takeover token travels in the {@value #TOKEN_HEADER} header,
 * never in a URL. Browser-facing URLs are the app's own API base; nothing here builds one.
 */
@RestController
@RequestMapping("/api/browser/takeover")
public class BrowserTakeoverController {

    private static final Logger log = LoggerFactory.getLogger(BrowserTakeoverController.class);

    static final String TOKEN_HEADER = "X-Takeover-Token";

    private final BrowserTakeoverService service;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService pumps =
            TenantAwareExecutors.newScheduledThreadPool(
                    2, TenantAwareExecutors.threadFactory("browser-takeover-stream", true));

    public BrowserTakeoverController(BrowserTakeoverService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        pumps.shutdownNow();
    }

    /**
     * Always 200: {@code {"enabled":true,...}} when takeover is on, {@code {"enabled":false}} when
     * it is off (the web hides the panel on enabled:false), so a switched-off deployment does not
     * log a 404 per page.
     */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        if (!service.isActive()) {
            return Map.of("enabled", false);
        }
        BrowserTakeoverSettings settings = service.settings();
        return Map.of(
                "enabled",
                true,
                "fps",
                settings.fps(),
                "idleTimeoutSeconds",
                settings.idleTimeoutSeconds());
    }

    /** The takeover (if any) waiting on or held by the caller for this agent + chat session. */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam UUID agentId, @RequestParam String sessionId) {
        requireOn();
        Caller caller = caller();
        View view = service.status(caller.tenant(), caller.user(), agentId, sessionId);
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("takeover", view);
        return body;
    }

    @PostMapping("/pause")
    public View pause(@RequestBody SessionRef ref) {
        requireOn();
        Caller caller = caller();
        if (ref == null || ref.agentId() == null || ref.sessionId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "agentId and sessionId are required.");
        }
        return service.pause(
                caller.tenant(),
                caller.user(),
                ref.agentId(),
                ref.sessionId(),
                ref.kind(),
                ref.reason());
    }

    @PostMapping("/{id}/begin")
    public Begin begin(@PathVariable UUID id) {
        requireOn();
        Caller caller = caller();
        return service.begin(caller.tenant(), caller.user(), id);
    }

    @PostMapping("/{id}/token")
    public TokenRefresh token(@PathVariable UUID id) {
        requireOn();
        Caller caller = caller();
        return service.refreshToken(caller.tenant(), caller.user(), id);
    }

    @PostMapping("/{id}/input")
    public ResponseEntity<Void> input(
            @PathVariable UUID id,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody InputEvent event) {
        requireOn();
        Caller caller = caller();
        service.input(caller.tenant(), caller.user(), id, token, event);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/hand-back")
    public View handBack(@PathVariable UUID id) {
        requireOn();
        Caller caller = caller();
        return service.handBack(caller.tenant(), caller.user(), id);
    }

    @PostMapping("/{id}/cancel")
    public View cancel(@PathVariable UUID id) {
        requireOn();
        Caller caller = caller();
        return service.cancel(caller.tenant(), caller.user(), id);
    }

    /**
     * The live view: SSE with {@code frame} events ({@link BrowserTakeoverDtos.FrameEvent}) at the
     * configured frame rate (never slower than one every five seconds, so no proxy idles it out)
     * and an {@code end} event when control returns. Needs the takeover token (header) and
     * USER_CONTROL; one slot per stream, capped server-wide.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @PathVariable UUID id,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireOn();
        Caller caller = caller();
        int epoch = service.openStream(caller.tenant(), caller.user(), id, token);
        long lifetimeMs = (service.settings().maxDurationSeconds() + 30) * 1_000L;
        SseEmitter emitter = new SseEmitter(lifetimeMs);
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        AtomicBoolean released = new AtomicBoolean();
        // Idempotent: the slot is returned exactly once, whichever of end / error / timeout /
        // client-gone comes first
        // (and even if that happens before the future below has been stored).
        Runnable release =
                () -> {
                    if (released.compareAndSet(false, true)) {
                        ScheduledFuture<?> running = task.get();
                        if (running != null) {
                            running.cancel(false);
                        }
                        service.releaseStream();
                    }
                };
        BrowserTakeoverStreamPump pump =
                new BrowserTakeoverStreamPump(
                        service,
                        caller.tenant(),
                        caller.user(),
                        id,
                        epoch,
                        frame -> send(emitter, "frame", frame),
                        reason -> {
                            try {
                                send(emitter, "end", Map.of("reason", reason));
                            } catch (RuntimeException ignored) {
                                // the client is already gone
                            }
                            emitter.complete();
                            release.run();
                        });
        emitter.onCompletion(
                () -> {
                    pump.cancel();
                    release.run();
                });
        emitter.onTimeout(
                () -> {
                    pump.cancel();
                    emitter.complete();
                });
        emitter.onError(
                error -> {
                    pump.cancel();
                    release.run();
                });
        long interval = service.settings().frameIntervalMillis();
        ScheduledFuture<?> future =
                pumps.scheduleWithFixedDelay(
                        () -> {
                            try {
                                if (!pump.tick()) {
                                    release.run();
                                }
                            } catch (RuntimeException e) {
                                log.debug(
                                        "browser takeover stream tick failed: {}",
                                        e.getClass().getSimpleName());
                                pump.cancel();
                                release.run();
                            }
                        },
                        0,
                        interval,
                        TimeUnit.MILLISECONDS);
        task.set(future);
        if (released.get()) {
            future.cancel(false);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                // Proxies (nginx, and Cloudflare honours it too) must not buffer an event stream.
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(event)
                            .data(mapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // The client went away; the emitter's completion callbacks stop the pump.
            throw new IllegalStateException("stream closed", e);
        }
    }

    private void requireOn() {
        if (!service.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found.");
        }
    }

    private record Caller(String tenant, String user) {}

    private static Caller caller() {
        TenantContext context =
                TenantContext.current()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED,
                                                "Sign in to take control of a browser session."));
        if (context.getTenantId() == null || context.getSubject() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Sign in to take control of a browser session.");
        }
        return new Caller(context.getTenantId(), context.getSubject());
    }
}
