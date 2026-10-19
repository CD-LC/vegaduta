package com.agenticai.core.common;

import com.agenticai.core.agents.trust.DelegationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.tenancy.SignupRateLimitExceededException;
import com.agenticai.core.workflow.UnsupportedTriggerChannelException;
import com.agenticai.core.workflow.WorkflowStepLimitExceededException;
import com.agenticai.core.workflow.WorkflowValidationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verdicts on what an agent turn or workflow asked for: tool and workflow step caps (429), the
 * tenant's prompt content policy (422), workflow governance validation and unsupported trigger
 * channels (400), and the agent trust/commerce delegation codes (403/404/400 with a stable {@code
 * code}). Ordered after {@link ServiceCredentialExceptionHandler}; see {@link
 * FallbackExceptionHandler} for the ordering rules. Every type handled here extends {@code
 * RuntimeException} directly.
 */
@RestControllerAdvice
@Order(ExecutionPolicyExceptionHandler.ORDER)
public class ExecutionPolicyExceptionHandler {

    /** Resolution order of this advice within the chain (lower runs first). */
    static final int ORDER = 320;

    private static final Logger log = LoggerFactory.getLogger(ExecutionPolicyExceptionHandler.class);

    /** Body/content-type builder: RFC 9457 + legacy members, or legacy only. */
    private final ErrorResponseFactory errors;

    private final ExceptionHandlerSupport support;

    public ExecutionPolicyExceptionHandler(ErrorResponseFactory errors) {
        this.errors = errors;
        this.support = new ExceptionHandlerSupport(errors, null);
    }

    @ExceptionHandler(ToolStepLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleToolStepLimitExceeded(
            ToolStepLimitExceededException e) {
        // 429: closest-fitting existing status (same as SignupRateLimitExceededException)
        // for "this turn asked for too much work" - not literally a request-rate
        // limit, but the same "come back with less" semantics. Logged, since a
        // string of these for one agent/session could indicate a genuinely
        // broken tool-calling loop worth an operator's attention, not just an
        // occasional legitimate long task.
        log.warn("Tool step limit exceeded: {}", e.getMessage());
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(
            com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException.class)
    public ResponseEntity<Map<String, Object>> handleContentPolicyViolation(
            com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException e) {
        // 422: the tenant's own BLOCK-mode injection policy rejected the prompt
        // before it reached any provider - a verdict on the input, not a 5xx.
        log.warn("LLM gateway content policy blocked a prompt: {}", e.getMessage());
        return errorResponse(e, HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(WorkflowValidationException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowValidation(
            WorkflowValidationException e) {
        // Governance gate rejection (OrchestratorService#validate) - the full
        // list of problems rides in "errors" so the canvas can flag every
        // offending node in one round trip, not just the first.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errors", e.getErrors());
        return errors.build(
                HttpStatus.BAD_REQUEST,
                e.getMessage(),
                ErrorResponseFactory.slugFor(e, HttpStatus.BAD_REQUEST),
                body);
    }

    @ExceptionHandler(WorkflowStepLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowStepLimitExceeded(
            WorkflowStepLimitExceededException e) {
        log.warn("Workflow step limit exceeded: {}", e.getMessage());
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(UnsupportedTriggerChannelException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedTriggerChannel(
            UnsupportedTriggerChannelException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // Agent trust & commerce surface (delegation grants, agent wallets,
    // AP2-style mandates): each exception carries a stable machine-readable
    // code that IS the wire contract - introspection responses, payment
    // rejections and audit rows all quote it, so it goes into the body via
    // errorResponseWithCode. Controllers already translate the expected cases
    // to ResponseStatusException; these handlers catch escapes from the
    // non-controller call paths (x402 settlement, future tool callers) so a
    // domain rejection never becomes an opaque 500 + Issues-portal row.
    @ExceptionHandler(DelegationException.class)
    public ResponseEntity<Map<String, Object>> handleDelegation(DelegationException e) {
        HttpStatus status =
                switch (e.getCode()) {
                    case "GRANT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    // Token verification failures - the caller's credential was
                    // rejected, which is a 403 shape, not a malformed request.
                    case "TOKEN_EXPIRED",
                            "TOKEN_REVOKED",
                            "AUDIENCE_MISMATCH",
                            "SCOPE_MISSING",
                            "GRANT_INACTIVE",
                            "GRANT_REVOKED",
                            "GRANT_EXPIRED",
                            "BAD_SIGNATURE" ->
                            HttpStatus.FORBIDDEN;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return errorResponseWithCode(status, e.getMessage(), e.getCode());
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            Throwable e, HttpStatus requested, String message) {
        return support.errorResponse(e, requested, message);
    }

    private ResponseEntity<Map<String, Object>> errorResponseWithCode(
            HttpStatus requested, String message, String code) {
        return support.errorResponseWithCode(requested, message, code);
    }
}
