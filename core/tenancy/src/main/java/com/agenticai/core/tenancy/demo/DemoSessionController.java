package com.agenticai.core.tenancy.demo;

import com.agenticai.core.tenancy.demo.dto.DemoSessionCreateRequest;
import com.agenticai.core.tenancy.demo.dto.DemoSessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/demo/session} - the unauthenticated try-before-you-register
 * surface.
 *
 * <p>Unauthenticated by necessity: the whole premise is a visitor with no
 * account. {@code SecurityConfig} therefore {@code permitAll()}s
 * {@code /api/demo/**}, exactly as it already does for {@code /api/signup},
 * {@code /webhook/**} and the device-token endpoints - and, exactly as in
 * those cases, "permitted" means "Spring Security is not the thing
 * authenticating it", not "unprotected". The controls here are
 * {@link DemoSessionRateLimiter} (per-IP creation rate), a per-IP live-
 * sandbox cap, a per-sandbox message cap, and the fact that the demo token
 * only ever unlocks this handful of endpoints.
 *
 * <p><b>Identity is the {@code X-Demo-Token} header, never a JWT.</b> A
 * 256-bit random bearer token minted at creation and held in the browser's
 * {@code localStorage}. It is deliberately a header and not a cookie: no
 * cookie means no CSRF surface on a {@code csrf().disable()}d API, and no
 * ambient authority riding along on unrelated requests to the same origin.
 *
 * <p><b>Error posture.</b> Every failure mode returns a short, human,
 * non-technical message the {@code /try} page can render as-is. Nothing here
 * throws into {@code GlobalExceptionHandler} - see
 * {@link DemoSandboxUnavailableException}'s javadoc.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoSessionController {

    private static final Logger log = LoggerFactory.getLogger(DemoSessionController.class);

    static final String DEMO_TOKEN_HEADER = "X-Demo-Token";

    private final DemoSessionService demoSessionService;
    private final DemoSessionRateLimiter rateLimiter;

    public DemoSessionController(DemoSessionService demoSessionService, DemoSessionRateLimiter rateLimiter) {
        this.demoSessionService = demoSessionService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Starts a sandbox. Accepts no body at all (one click, nothing typed) or
     * an optional email/business name - see {@link DemoSessionCreateRequest}.
     */
    @PostMapping("/session")
    public ResponseEntity<?> create(
        @Valid @RequestBody(required = false) DemoSessionCreateRequest request,
        HttpServletRequest servletRequest
    ) {
        String clientIp = clientIp(servletRequest);
        try {
            DemoSessionResponse created = demoSessionService.create(request, clientIp);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (DemoSandboxUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            // Belt and braces. Anything unexpected still leaves the visitor
            // with a sentence, not a stack trace or a 500 page.
            log.error("Unexpected failure creating a demo sandbox", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "We couldn't start a sandbox just now. Please try again in a moment."
            ));
        }
    }

    /**
     * Status + time remaining for the sandbox this token identifies. 404 for
     * an unknown token so a stale {@code localStorage} entry cleanly resets
     * the page to "start a new sandbox" rather than wedging it.
     */
    @GetMapping("/session")
    public ResponseEntity<?> status(@RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken) {
        Optional<DemoSession> session = demoSessionService.resolve(demoToken);
        if (session.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "This sandbox isn't available any more. You can start a new one."
            ));
        }
        return ResponseEntity.ok(demoSessionService.status(session.get()));
    }

    /**
     * Reset the current sandbox - allows users to start fresh without waiting
     * for expiration. This endpoint deletes the current sandbox and returns
     * a new one immediately.
     */
    @PostMapping("/session/reset")
    public ResponseEntity<?> reset(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
        @Valid @RequestBody(required = false) DemoSessionCreateRequest request,
        HttpServletRequest servletRequest
    ) {
        String clientIp = clientIp(servletRequest);
        Optional<DemoSession> existingSession = demoSessionService.resolve(demoToken);
        
        // If there's an existing session, clean it up first
        if (existingSession.isPresent()) {
            try {
                demoSessionService.deleteSession(existingSession.get());
            } catch (RuntimeException e) {
                log.warn("Failed to delete existing sandbox during reset, proceeding with new creation", e);
            }
        }
        
        // Create a new sandbox
        try {
            DemoSessionResponse created = demoSessionService.create(request, clientIp);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (DemoSandboxUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Unexpected failure resetting demo sandbox", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "We couldn't reset your sandbox just now. Please try again in a moment."
            ));
        }
    }

    /**
     * The durable transcript (migration V146) - what the read-only phase
     * renders, and what lets a refreshed tab re-show the conversation instead
     * of losing it with the React state. Same token auth as every other read
     * here; served for expired sessions too (an expired sandbox's "register
     * to keep this" pitch is exactly the moment the visitor most wants to see
     * what "this" is).
     */
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken) {
        Optional<DemoSession> session = demoSessionService.resolve(demoToken);
        if (session.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "This sandbox isn't available any more. You can start a new one."
            ));
        }
        return ResponseEntity.ok(Map.of(
            "messages",
            demoSessionService.transcript(session.get()).stream()
                .map(m -> Map.of(
                    "direction", m.getDirection(),
                    "text", m.getContent(),
                    "simulated", m.isSimulated(),
                    "at", m.getCreatedAt().toString()
                ))
                .toList()
        ));
    }

    /**
     * {@code request.getRemoteAddr()} - deliberately NOT trusting
     * {@code X-Forwarded-For} directly at this layer, identical to
     * {@code TenantSignupController#clientIp} and for the identical reason:
     * a client-settable header, trusted blindly, would let an attacker
     * present a fresh IP per request and walk straight through both the
     * rate limit and the per-IP live-sandbox cap.
     *
     * <p>Fixed at the deployment layer (2026-07-26, {@code
     * server.forward-headers-strategy=native} +
     * {@code server.tomcat.remoteip.trusted-proxies} in {@code
     * application.yml}) rather than here: Tomcat's {@code RemoteIpValve}
     * now resolves this method's return value from {@code X-Forwarded-For}
     * ONLY when the direct TCP connection is from a trusted internal-network
     * address (i.e. actually came through Caddy), so this method needed no
     * change to start returning the real visitor IP instead of Caddy's own
     * container address. Previously EVERY demo session recorded the same
     * Docker-internal IP - confirmed live before this fix, every row in
     * {@code control_plane.demo_session} had {@code created_ip =
     * 172.18.0.1} - meaning the rate limit and live-sandbox cap were one
     * shared bucket across every visitor, not a real per-IP control.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
