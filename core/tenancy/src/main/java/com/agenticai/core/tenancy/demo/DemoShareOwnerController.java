package com.agenticai.core.tenancy.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OWNER half of shareable sandbox agents - {@code POST
 * /api/demo/session/share} (mint the public link) and {@code POST
 * /api/demo/session/event} (the Academy-lab funnel events). Both are
 * authenticated exactly like every other sandbox-owner endpoint: the opaque
 * {@code X-Demo-Token} header, resolved through {@link DemoSessionService},
 * acting only ever on the caller's OWN session.
 *
 * <p>Its own controller rather than more methods on {@code
 * DemoSessionController} for the standing reason that class itself states for
 * not reusing signup pieces: the session controller is the stable core of the
 * sandbox flow, and a self-contained feature with its own service gains
 * nothing from being edited into it.
 *
 * <p>The PUBLIC half - what the person who RECEIVES the link can do - lives in
 * {@code core/agents}' {@code DemoShareController}, next to {@code
 * DemoChatController}, because dispatching a viewer turn needs {@code
 * AgentChatService} and the module graph runs {@code agents -> tenancy}.
 */
@RestController
@RequestMapping("/api/demo/session")
public class DemoShareOwnerController {

    private static final Logger log = LoggerFactory.getLogger(DemoShareOwnerController.class);

    private static final String DEMO_TOKEN_HEADER = "X-Demo-Token";

    private final DemoSessionService demoSessionService;
    private final DemoShareService demoShareService;

    public DemoShareOwnerController(DemoSessionService demoSessionService, DemoShareService demoShareService) {
        this.demoSessionService = demoSessionService;
        this.demoShareService = demoShareService;
    }

    public record ShareResponse(String shareId, String shareUrl) {
    }

    public record DemoEventRequest(@Size(max = 40) String event) {
    }

    /**
     * Mints (or returns the existing) share link - idempotent, so the page
     * can call it on every open of the share dialog without ever rotating the
     * URL already in circulation.
     */
    @PostMapping("/share")
    public ResponseEntity<?> share(@RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return notFound();
        }
        DemoSession session = found.get();
        if (session.isExpired(Instant.now())) {
            // 410 with the structured error key the share frontend renders -
            // an expired sandbox has nothing left worth linking to.
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            DemoShareService.ShareLink link = demoShareService.createOrGet(session);
            return ResponseEntity.ok(new ShareResponse(link.shareId(), link.shareUrl()));
        } catch (RuntimeException e) {
            log.warn("Could not mint a share link for demo session {}", session.getId(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "We couldn't create a share link just now. Please try again in a moment."
            ));
        }
    }

    /**
     * The Academy-lab funnel events - {@code lab_started} / {@code
     * lab_completed}. Idempotent per session (first timestamp wins), so the
     * page can fire them unconditionally without inflating the funnel.
     * Unknown event names are a 422 rather than silently dropped: the caller
     * is our own frontend, and a typo there should fail loudly in dev.
     */
    @PostMapping("/event")
    public ResponseEntity<?> event(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
        @Valid @RequestBody(required = false) DemoEventRequest request
    ) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return notFound();
        }
        String event = request == null || request.event() == null
            ? ""
            : request.event().trim().toLowerCase(Locale.ROOT);
        DemoSession session = found.get();
        try {
            switch (event) {
                case "lab_started" -> demoShareService.recordLabStarted(session);
                case "lab_completed" -> demoShareService.recordLabCompleted(session);
                default -> {
                    return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid"));
                }
            }
        } catch (RuntimeException e) {
            // Funnel bookkeeping must never break the lab itself - swallow,
            // log, and still 204. The event is analytics, not state the page
            // depends on.
            log.warn("Could not record demo lab event '{}' for session {}", event, session.getId(), e);
        }
        return ResponseEntity.noContent().build();
    }

    /** Identical wording and status to {@code DemoSessionController}'s, so the page has one reset path. */
    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "error", "This sandbox isn't available any more. You can start a new one."
        ));
    }
}
