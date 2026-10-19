package com.agenticai.core.common;

import static com.agenticai.core.common.ExceptionHandlerSupport.isRaisedByPlatformCode;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.http.HttpStatusMapped;
import com.agenticai.core.workflow.WorkflowGraphCorruptException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Last-ordered advice of the chain that replaced the single {@code GlobalExceptionHandler}: the
 * generic JDK catch-alls ({@code IllegalArgumentException} 400, {@code NoSuchElementException}
 * 404), Spring's {@code ResponseStatusException} carrier, the two platform-side data faults that
 * are answered 500 and recorded as issues, and the {@code Exception.class} catch-all that turns
 * {@link HttpStatusMapped} exceptions into their declared status and everything else into a 500 +
 * Issues-portal row.
 *
 * <h2>Why the chain is ordered the way it is</h2>
 *
 * <p>Spring MVC's {@code ExceptionHandlerExceptionResolver} walks every {@code @ControllerAdvice}
 * bean in {@code @Order} order and stops at the FIRST advice that has ANY {@code @ExceptionHandler}
 * whose declared type is assignable from the thrown exception (or, failing that, from one of its
 * causes); only inside that one advice does it then pick the MOST SPECIFIC matching handler. Two
 * consequences shape the split:
 *
 * <ol>
 *   <li>The catch-alls for {@code Exception} (and the JDK-wide {@code IllegalArgumentException} /
 *       {@code NoSuchElementException}) live HERE, in the last-ordered advice, and nowhere else.
 *       Were {@code Exception.class} handled in any earlier advice, that advice would claim every
 *       exception and no later advice would ever run.
 *   <li>No advice may handle a superclass of an exception that a LATER advice handles more
 *       specifically, or the subclass would be answered by the superclass handler. Every handled
 *       type's hierarchy was read before grouping; the only handled types with handled subclasses
 *       are kept whole inside one advice:
 *       <ul>
 *         <li>{@code TenantLimitExceededException} &gt; {@code PerUserBudgetExhaustedException},
 *             {@code EditLockedException} - all three in {@link ClientRequestExceptionHandler};
 *         <li>{@code CapabilityCredentialValidationException} &gt; {@code
 *             JevCredentialValidationException}, {@code CapabilityNotConfiguredException} &gt;
 *             {@code JevNotConfiguredException}, {@code CapabilityUpstreamException} &gt; {@code
 *             JevUpstreamException} - all in {@link UpstreamDependencyExceptionHandler};
 *         <li>{@code KeycloakAdminException} &gt; {@code KeycloakUserConflictException}, {@code
 *             KeycloakUserValidationException} - all in {@link UpstreamDependencyExceptionHandler}.
 *       </ul>
 *       Every other platform exception handled by the chain extends {@code RuntimeException}
 *       directly ({@code ChannelDeliveryException}, {@code ChannelConfigurationException} and
 *       {@code ChannelPayloadSerializationException} extend the unhandled abstract {@code
 *       ChannelException}); their own subclasses that exist in the codebase ({@code
 *       HostedPoolExhaustedException} extends {@code UpstreamProviderException}, {@code
 *       RetryableChannelDeliveryException} extends {@code ChannelDeliveryException}) have no
 *       handler of their own and are meant to reach the parent's. Platform types that extend {@code
 *       IllegalArgumentException} ({@code OpenApiToolException}, {@code SkillValidationException},
 *       {@code DisposableEmailException}, {@code ConversationHeldByHumanException}, {@code
 *       InvalidResultSchemaException}, ...) and {@code NoSuchElementException} ({@code
 *       AgentJobNotFoundException}) have no handler of their own either and are meant to land in
 *       the generic 400/404 here, which is why those two JDK handlers sit in this last advice
 *       rather than with the domain 400s/404s.
 * </ol>
 *
 * <p>The chain, in resolution order: {@link RequestBindingExceptionHandler} (Spring MVC binding /
 * HTTP-level client mistakes), {@link ResourceStateExceptionHandler} (domain 404/409), {@link
 * ClientRequestExceptionHandler} (client-actionable domain 400/401/402/403/413/415/422/423/429),
 * {@link UpstreamDependencyExceptionHandler} (outbound-call failures, not-configured, DB down,
 * optimistic lock), then this class. Each advice has an explicit {@code @Order} so resolution never
 * depends on bean registration order. {@code OpenAiCompatAdvice} (core/agents) sits at highest
 * precedence but is scoped by {@code assignableTypes} to its own controllers and is unaffected.
 *
 * <p>Every handler in the chain returns a small, predictable JSON error body and never echoes
 * request payloads back - request bodies in this app can contain API keys, so no handler logs or
 * returns the raw request. Body contract (see docs/architecture/overview.md "Error responses"): RFC
 * 9457 Problem Details ({@code type}, {@code title}, {@code status}, {@code detail}, {@code
 * instance}) as {@code application/problem+json}, PLUS every legacy member ({@code timestamp},
 * {@code status}, {@code error}, and per-handler extras such as {@code code}, {@code errors},
 * {@code upstreamStatus}) unchanged. All bodies are built by {@link ErrorResponseFactory} through
 * {@link ExceptionHandlerSupport}; {@code app.errors.problem-details.enabled=false} reverts to the
 * legacy-only body.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class FallbackExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FallbackExceptionHandler.class);

    /**
     * One WARN per minute for a 503 that is only "a data store (Redis) is down" - see {@link
     * #handleResponseStatus}. Per-request ERROR + stack trace + Issues-portal row for each of those
     * is the flood this limiter exists to prevent during exactly the incident that causes it.
     */
    private static final RateLimitedLog STORE_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    private final ExceptionHandlerSupport support;

    public FallbackExceptionHandler(
            PlatformIssueService issueService, ErrorResponseFactory errors) {
        this.support = new ExceptionHandlerSupport(errors, issueService);
    }

    // 4 LOW-severity backend audit findings, item 1: all 7 *SendController
    // classes previously threw a bare IllegalStateException here, which fell
    // through to the generic Exception.class catch-all below and showed
    // customers a raw 500 with no channel context. 500 (not 400) is still
    // the right status - this is a serialization failure of a small,
    // plain-string record the ObjectMapper should never actually choke on,
    // so it's genuinely unexpected, same posture as the generic catch-all -
    // just with the channel name preserved for triage.
    @ExceptionHandler(ChannelPayloadSerializationException.class)
    public ResponseEntity<Map<String, Object>> handleChannelPayloadSerialization(
            ChannelPayloadSerializationException e) {
        log.error(
                "Channel send payload serialization failed ({}): {}",
                e.getChannelName(),
                e.getMessage(),
                e);
        recordIssue(e);
        return errorResponse(
                e,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong sending this message. Please try again.");
    }

    // 4 LOW-severity backend audit findings, item 2: WorkflowService#readGraph
    // previously threw a bare IllegalStateException on a corrupt stored
    // graph, same generic-500 loss of context as the *SendController fix
    // just above. 500 + recordIssue (not 400) - this is a data-corruption
    // problem on OUR side (the stored graph_json, not anything the caller
    // sent), same reasoning as the generic catch-all below.
    @ExceptionHandler(WorkflowGraphCorruptException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowGraphCorrupt(
            WorkflowGraphCorruptException e) {
        log.error("Stored workflow graph is corrupt: {}", e.getMessage(), e);
        recordIssue(e);
        return errorResponse(
                e,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "This workflow's saved data appears to be corrupted. Please contact support.");
    }

    // Generic catch-all for plain IllegalArgumentException, used by a few
    // services for validation that doesn't warrant its own named exception
    // type (e.g. KnowledgeCollectionService's "cannot be its own parent" and
    // "unknown embedding model" checks) - without this, those fell through
    // to the generic 500 handler below, which is wrong for a client mistake.
    //
    // JAVA-012/013: the message is echoed ONLY when the exception was raised
    // by platform code (top stack frame in com.agenticai.*) - those are the
    // deliberate, client-facing validation messages. An IAE thrown inside a
    // library or the JDK (UUID.fromString, Enum.valueOf, Assert.*, Jackson,
    // ...) carries internal detail - class names, raw input, implementation
    // text - so it gets a fixed message and the detail goes to the log.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        if (isRaisedByPlatformCode(e)) {
            return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
        }
        log.warn(
                "IllegalArgumentException from library code answered as 400 without its message",
                e);
        return errorResponse(e, HttpStatus.BAD_REQUEST, "Invalid request.");
    }

    // Generic catch-all for plain NoSuchElementException, same posture as
    // IllegalArgumentException just above - #815 Governance follow-up's
    // TenantEmergencyControlService#requireTenant throws this for an unknown
    // tenantId (a platform-admin's client-side mistake - wrong/stale id -
    // not a server bug), and without this handler it fell through to the
    // generic 500 handler below, which also wrongly records it as a
    // PlatformIssue.
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNoSuchElement(NoSuchElementException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    // ResponseStatusException is Spring's own "answer with this status" carrier
    // (16 controllers throw it for their 400/404/409s). Like the binding
    // failures above, it never reached DefaultHandlerExceptionResolver once
    // the Exception.class catch-all existed, so every one of them was answered
    // as a 500 "Something went wrong" - the Contacts page was 500ing on a plain
    // "channel is required" 400 (prod, 2026-09-24). Map it to its own status;
    // only a 5xx one is a platform bug worth an Issues-portal record.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = e.getReason() != null ? e.getReason() : status.getReasonPhrase();
        if (status == HttpStatus.SERVICE_UNAVAILABLE
                && e.getCause() instanceof org.springframework.dao.DataAccessException) {
            // Deliberate 503 raised by a Redis-backed store (Agent Lab, vision sessions) while
            // Redis is
            // unreachable: infrastructure, already reported once a minute by the throwing service.
            // No
            // stack trace and no Issues-portal row per request - the client still gets the 503.
            STORE_DOWN_LOG.warn(
                    "response-status-503-data-access",
                    "ResponseStatusException 503 caused by "
                            + e.getCause().getClass().getSimpleName()
                            + " - answering 503 without a per-request issue record");
        } else if (status.is5xxServerError()) {
            log.error("ResponseStatusException {}", status.value(), e);
            recordIssue(e);
        } else {
            log.debug("ResponseStatusException {}: {}", status.value(), message);
        }
        return errorResponse(status, message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        if (e instanceof HttpStatusMapped mapped) {
            return handleStatusMapped(e, mapped);
        }
        ResponseStatus annotated =
                AnnotatedElementUtils.findMergedAnnotation(e.getClass(), ResponseStatus.class);
        if (annotated != null) {
            return handleAnnotatedStatus(e, annotated);
        }
        log.error("Unhandled exception", e);
        recordIssue(e);
        return errorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
    }

    /**
     * Exceptions annotated {@code @ResponseStatus} (eval dataset/run 404 and 409, simulation and
     * experiment 404/409, code-intel 403). Spring's own {@code ResponseStatusExceptionResolver}
     * never sees them because this advice's {@code Exception.class} handler claims them first, so
     * honour the annotation here. The exception message is the deliberate client-facing text; an
     * empty one falls back to the annotation reason, then the status phrase. Only a 5xx is
     * recorded as a platform issue.
     */
    private ResponseEntity<Map<String, Object>> handleAnnotatedStatus(
            Exception e, ResponseStatus annotated) {
        HttpStatus status = annotated.code();
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = annotated.reason().isBlank() ? status.getReasonPhrase() : annotated.reason();
        }
        if (status.is5xxServerError()) {
            log.error("{} answered {}", e.getClass().getSimpleName(), status.value(), e);
            recordIssue(e);
        } else {
            log.debug("{} answered {}: {}", e.getClass().getSimpleName(), status.value(), message);
        }
        return errorResponse(e, status, message);
    }

    /**
     * Exceptions that declare their own HTTP mapping ({@link HttpStatusMapped}) - the vendor
     * modules' "not configured yet" (400), "outbound call failed" (502, logged with its stack
     * trace), rejected webhook signature (403, logged) and coded wallet/mandate refusals. Declared
     * by the exception rather than mapped here so this kernel handler never imports a vendor
     * (ADR-0011 rule a). Same body as a named handler: {@code errorResponse}, or {@code
     * errorResponseWithCode} when the exception has a code. Not an Issues-portal record - each of
     * these has a clear, client-actionable meaning.
     */
    private ResponseEntity<Map<String, Object>> handleStatusMapped(
            Exception e, HttpStatusMapped mapped) {
        HttpStatus status = HttpStatus.resolve(mapped.httpStatus());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (mapped.logAtWarn()) {
            if (status.is5xxServerError()) {
                log.warn("{}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            } else {
                log.warn("Rejected request ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return mapped.errorCode() == null
                ? errorResponse(e, status, e.getMessage())
                : errorResponseWithCode(status, e.getMessage(), mapped.errorCode());
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            Throwable e, HttpStatus requested, String message) {
        return support.errorResponse(e, requested, message);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus requested, String message) {
        return support.errorResponse(requested, message);
    }

    private ResponseEntity<Map<String, Object>> errorResponseWithCode(
            HttpStatus requested, String message, String code) {
        return support.errorResponseWithCode(requested, message, code);
    }

    private void recordIssue(Exception e) {
        support.recordIssue(e);
    }
}
