package com.agenticai.core.workflow;

import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /webhook/workflow-waits/{token}} - the signed resume URL of a webhook-mode Wait node.
 * Public by construction ({@code /webhook/**} is {@code permitAll} in SecurityConfig, exactly like
 * the workflow webhook trigger); the HMAC token ({@link WorkflowWaitResumeTokens}) is the whole
 * credential and carries the tenant, so the tenant is set by hand the way {@link
 * WorkflowWebhookController} does. An unknown/forged token is a 404, a well-signed URL whose wait
 * is already over (resumed, timed out, cancelled) is a 409, and the posted JSON becomes the Wait
 * node's output (see {@link WorkflowWaitResumeService}).
 */
@RestController
@RequestMapping("/webhook/workflow-waits")
public class WorkflowWaitWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWaitWebhookController.class);
    private static final String RATE_LIMIT_PREFIX = "agentic:workflow-wait-resume-rate:";
    private static final String REQUESTED_BY = "system:workflow-wait-resume";

    private final WorkflowWaitResumeTokens tokens;
    private final WorkflowWaitResumeService resumeService;
    private final WorkflowPublicRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long maxCallsPerMinute;
    private final int maxBodyChars;

    public WorkflowWaitWebhookController(
            WorkflowWaitResumeTokens tokens,
            WorkflowWaitResumeService resumeService,
            WorkflowPublicRateLimiter rateLimiter,
            ObjectMapper objectMapper,
            @Value("${app.workflow.wait-node.enabled:false}") boolean enabled,
            @Value("${app.workflow.wait-node.max-resume-calls-per-minute:30}")
                    long maxCallsPerMinute,
            @Value("${app.workflow.wait-node.max-resume-body-chars:65536}") int maxBodyChars) {
        this.tokens = tokens;
        this.resumeService = resumeService;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxCallsPerMinute = maxCallsPerMinute;
        this.maxBodyChars = maxBodyChars;
    }

    @PostMapping("/{token}")
    public ResponseEntity<?> resume(
            @PathVariable String token, @RequestBody(required = false) Map<String, Object> body) {
        Optional<WorkflowWaitResumeTokens.Claims> claims =
                enabled ? tokens.verify(token) : Optional.empty();
        if (claims.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!rateLimiter.tryAcquire(
                RATE_LIMIT_PREFIX,
                WebhookTokenHasher.sha256Hex(token).substring(0, 24),
                maxCallsPerMinute)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate_limited"));
        }
        if (body != null && !body.isEmpty() && serializedLength(body) > maxBodyChars) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "payload_too_large"));
        }
        WorkflowWaitResumeTokens.Claims c = claims.get();
        TenantContext.set(TenantContext.of(c.tenantId(), REQUESTED_BY, Set.of()));
        try {
            WorkflowWaitResumeService.ResumeOutcome outcome =
                    resumeService.resumeByWebhook(c.runId(), c.nodeId(), body);
            if (outcome == WorkflowWaitResumeService.ResumeOutcome.RESUMED) {
                return ResponseEntity.ok(Map.of("status", "resumed"));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            Map.of(
                                    "error",
                                    "This run is not waiting on this URL (already resumed, timed out or cancelled)."));
        } catch (RuntimeException e) {
            log.warn("Workflow wait resume for run {} failed: {}", c.runId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "resume_failed"));
        } finally {
            TenantContext.clear();
        }
    }

    private int serializedLength(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body).length();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
