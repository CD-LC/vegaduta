package com.agenticai.core.tenancy.demo;

import com.agenticai.core.tenancy.demo.dto.DemoTranscriptEmailRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
 * {@code POST /api/demo/transcript-email} - "email me this conversation".
 *
 * <p>Unauthenticated like every {@code /api/demo/**} route ({@code
 * SecurityConfig}'s path-prefix {@code permitAll()} - and per that rule's
 * own NOTE, this endpoint was checked against "safe for an anonymous
 * internet caller": it can only mail the caller's OWN session transcript to
 * an address the caller typed, behind a per-IP limiter and a per-session
 * send cap). Identity is the same {@code X-Demo-Token} bearer header as the
 * rest of the demo surface.
 *
 * <p>Response contract: {@code {sent, message}} with a human sentence the
 * /try page renders as-is. User-caused conditions (cap reached, nothing to
 * send, mail not configured, upstream bounce) are all {@code 200 {sent:
 * false}} - never a 5xx. The two non-200s match the surface's existing
 * conventions exactly: 404 for a dead token (so a stale localStorage entry
 * resets the page), 429 for the per-IP limiter.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoTranscriptEmailController {

    private static final Logger log = LoggerFactory.getLogger(DemoTranscriptEmailController.class);

    private final DemoSessionService demoSessionService;
    private final DemoTranscriptEmailService transcriptEmailService;
    private final DemoSessionRateLimiter rateLimiter;

    public DemoTranscriptEmailController(
        DemoSessionService demoSessionService,
        DemoTranscriptEmailService transcriptEmailService,
        DemoSessionRateLimiter rateLimiter
    ) {
        this.demoSessionService = demoSessionService;
        this.transcriptEmailService = transcriptEmailService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/transcript-email")
    public ResponseEntity<?> send(
        @RequestHeader(value = DemoSessionController.DEMO_TOKEN_HEADER, required = false) String demoToken,
        @Valid @RequestBody DemoTranscriptEmailRequest request,
        HttpServletRequest servletRequest
    ) {
        Optional<DemoSession> session = demoSessionService.resolve(demoToken);
        if (session.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "This sandbox isn't available any more. You can start a new one."
            ));
        }
        // Same direct-remote-address stance (and same documented Cloudflare
        // coarseness caveat) as DemoSessionController#clientIp.
        if (!rateLimiter.tryAcquireTranscriptEmail(servletRequest.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "sent", false,
                "message", "That's a few transcript emails already today - please try again tomorrow."
            ));
        }
        try {
            DemoTranscriptEmailService.Result result =
                transcriptEmailService.send(session.get(), request.email(), request.sessionId());
            return ResponseEntity.ok(Map.of("sent", result.sent(), "message", result.message()));
        } catch (RuntimeException e) {
            // Belt and braces - the service already converts every expected
            // failure into a Result, so this only catches the unexpected, and
            // even that must reach the visitor as a sentence, not a 500.
            log.error("Unexpected failure emailing a demo transcript for sandbox {}", session.get().getId(), e);
            return ResponseEntity.ok(Map.of(
                "sent", false,
                "message", "We couldn't send the email just now. Please try again in a moment."
            ));
        }
    }
}
