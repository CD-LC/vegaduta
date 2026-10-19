package com.agenticai.core.common;

import com.agenticai.core.security.RequestCorrelationFilter;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds every error body the ordered {@code @RestControllerAdvice} chain returns (see {@link
 * FallbackExceptionHandler} for the chain and {@link ExceptionHandlerSupport} for the shared
 * builders).
 *
 * <p>With {@code app.errors.problem-details.enabled=true} (the default) the body is an RFC 9457
 * Problem Details object served as {@code application/problem+json}:
 *
 * <pre>
 * {
 *   "type": "urn:vegaduta:problem:agent-not-found",   // stable, machine-readable
 *   "title": "Not Found",                              // HTTP reason phrase
 *   "status": 404,                                     // RFC 9457 AND legacy field
 *   "detail": "Agent not found: ...",                  // same text as "error"
 *   "instance": "/api/agents/...",                     // request path, never the query
 *   "timestamp": "2026-09-29T10:00:00Z",               // legacy
 *   "error": "Agent not found: ...",                   // legacy
 *   ...handler-specific legacy members (code, errors, upstreamStatus, budget, ...)
 *   "requestId": "...", "traceId": "..."               // when present in MDC
 * }
 * </pre>
 *
 * Every member the pre-RFC body carried keeps its name, type and value, so a client that reads
 * {@code error}/{@code status}/{@code code}/... is unaffected. RFC 9457 explicitly allows such
 * extension members.
 *
 * <p>With the flag off the body is exactly the legacy map (same members, same order, no
 * content-type preset, no correlation members).
 *
 * <p>The body stays a {@code Map} rather than Spring's {@code ProblemDetail} class on purpose: the
 * wire format is identical to what Jackson writes for a {@code ProblemDetail} with properties,
 * while the ~150 handler signatures, and every caller that reads {@code getBody().get("error")},
 * stay unchanged.
 */
@Component
public class ErrorResponseFactory {

    /** Default {@code type} prefix; a URN because it must not imply a hosted page. */
    public static final String DEFAULT_TYPE_PREFIX = "urn:vegaduta:problem:";

    static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private final boolean problemDetailsEnabled;
    private final String typePrefix;

    public ErrorResponseFactory(
            @Value("${app.errors.problem-details.enabled:true}") boolean problemDetailsEnabled,
            @Value("${app.errors.problem-details.type-prefix:" + DEFAULT_TYPE_PREFIX + "}")
                    String typePrefix) {
        this.problemDetailsEnabled = problemDetailsEnabled;
        this.typePrefix =
                typePrefix == null || typePrefix.isBlank() ? DEFAULT_TYPE_PREFIX : typePrefix;
    }

    public boolean isProblemDetailsEnabled() {
        return problemDetailsEnabled;
    }

    /** Status line plus, in RFC mode, {@code Content-Type: application/problem+json}. */
    public ResponseEntity.BodyBuilder builder(HttpStatus status) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (problemDetailsEnabled) {
            // Preset so Spring skips Accept negotiation: an error on an SSE
            // endpoint (Accept: text/event-stream) still gets its JSON body.
            b.contentType(PROBLEM_JSON);
        }
        return b;
    }

    /**
     * The error body. {@code extensions} are the handler-specific legacy members, appended in
     * iteration order (pass a LinkedHashMap); null values are kept, as the legacy bodies did.
     */
    public Map<String, Object> body(
            HttpStatus status, String message, String typeSlug, Map<String, Object> extensions) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (problemDetailsEnabled) {
            body.put(
                    "type",
                    typePrefix
                            + (typeSlug == null || typeSlug.isBlank()
                                    ? slugFor(status)
                                    : typeSlug));
            body.put("title", status.getReasonPhrase());
            body.put("status", status.value());
            if (message != null) {
                body.put("detail", message);
            }
            String instance = currentRequestPath();
            if (instance != null) {
                body.put("instance", instance);
            }
            body.put("timestamp", Instant.now().toString());
            body.put("error", message);
        } else {
            body.put("timestamp", Instant.now().toString());
            body.put("status", status.value());
            body.put("error", message);
        }
        if (extensions != null) {
            body.putAll(extensions);
        }
        if (problemDetailsEnabled) {
            putIfPresent(body, "requestId", MDC.get(RequestCorrelationFilter.MDC_KEY));
            putIfPresent(body, "traceId", MDC.get("traceId"));
        }
        return body;
    }

    public ResponseEntity<Map<String, Object>> build(
            HttpStatus status, String message, String typeSlug) {
        return build(status, message, typeSlug, null);
    }

    public ResponseEntity<Map<String, Object>> build(
            HttpStatus status, String message, String typeSlug, Map<String, Object> extensions) {
        return builder(status).body(body(status, message, typeSlug, extensions));
    }

    /**
     * Stable {@code type} slug for a platform exception: its simple class name without the {@code
     * Exception} suffix, in kebab case ({@code AgentNotFoundException} -> {@code agent-not-found}).
     * Only VegaDūta's own exception types are named this way; JDK, Spring and other library
     * exceptions get the status slug, so {@code type} never exposes an internal implementation
     * class.
     */
    public static String slugFor(Throwable e, HttpStatus status) {
        if (e == null || !e.getClass().getName().startsWith("com.agenticai.")) {
            return slugFor(status);
        }
        String name = e.getClass().getSimpleName();
        if (name.endsWith("Exception") && name.length() > "Exception".length()) {
            name = name.substring(0, name.length() - "Exception".length());
        }
        return kebab(name);
    }

    /** {@code HttpStatus.NOT_FOUND} -> {@code not-found}. */
    public static String slugFor(HttpStatus status) {
        return status.getReasonPhrase()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /** {@code VISION_PLAN_LOCKED} -> {@code vision-plan-locked}. */
    public static String slugForCode(String code) {
        return code.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    static String kebab(String camel) {
        return camel.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank() && !body.containsKey(key)) {
            body.put(key, value);
        }
    }

    private static String currentRequestPath() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            // Path only: a query string can carry tokens (share links, OAuth state).
            return request.getRequestURI();
        }
        return null;
    }
}
