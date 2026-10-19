package com.agenticai.core.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.Map;

/**
 * Spring MVC's own request-binding and HTTP-level failures (bad path-variable type, missing
 * parameter, unreadable body, wrong Content-Type, unknown URL, wrong verb, bean validation, client
 * gone mid-stream). First of the ordered {@code @RestControllerAdvice} chain that replaced the
 * single {@code GlobalExceptionHandler}; see {@link FallbackExceptionHandler} for the ordering
 * rules every advice in the chain obeys and the body contract they share.
 *
 * <p>None of these handles a superclass of anything a later advice handles: every type here is a
 * leaf Spring/JDK type ({@code AsyncRequestNotUsableException} extends {@code IOException}, which
 * no platform exception extends).
 */
@RestControllerAdvice
@Order(RequestBindingExceptionHandler.ORDER)
public class RequestBindingExceptionHandler {

    /** Resolution order of this advice within the chain (lower runs first). */
    static final int ORDER = 100;

    private static final Logger log = LoggerFactory.getLogger(RequestBindingExceptionHandler.class);

    /** Body/content-type builder: RFC 9457 + legacy members, or legacy only. */
    private final ErrorResponseFactory errors;

    private final ExceptionHandlerSupport support;

    public RequestBindingExceptionHandler(ErrorResponseFactory errors) {
        this.errors = errors;
        this.support = new ExceptionHandlerSupport(errors, null);
    }

    // Spring MVC's own request-binding failures. These are NOT reached by
    // Spring's DefaultHandlerExceptionResolver (which would map each of them to
    // the right 4xx) once a @RestControllerAdvice declares an
    // @ExceptionHandler(Exception.class) catch-all: the advice resolver runs
    // FIRST, so every one of them was being answered as an opaque 500 AND
    // recorded as a PlatformIssue, drowning the Issues portal in client
    // mistakes. Concretely, before these three handlers: GET /api/agents/not-a-uuid
    // (bad @PathVariable type), PUT /api/platform/llm-tasks/NOT_A_TASK (bad enum
    // path variable), and any request with a malformed/missing JSON body all
    // returned 500 "Something went wrong."
    //
    // None of them echoes the offending value back: request bodies in this app
    // routinely carry API keys (see this class's header comment), and
    // HttpMessageNotReadableException#getMessage() in particular can quote the
    // raw payload, so that one gets a fixed message and the detail stays in the
    // log only.
    @ExceptionHandler(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleArgumentTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid value for '" + e.getName() + "'.",
                "invalid-parameter");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Required parameter '" + e.getParameterName() + "' is missing.",
                "missing-parameter");
    }

    // A required @RequestHeader that is absent (e.g. POST /api/devices/poll without
    // X-Device-Token, an unauthenticated permitAll endpoint) is a client mistake. Without this
    // it fell through to the Exception.class catch-all: a 500 plus an Issues-portal write that
    // anyone could trigger. Only the header NAME is echoed, never a value.
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestHeader(
            org.springframework.web.bind.MissingRequestHeaderException e) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Required header '" + e.getHeaderName() + "' is missing.",
                "missing-header");
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        // Only the cause's TYPE is logged, never its message: Jackson's parse
        // errors quote the offending fragment of the payload verbatim, and
        // payloads here routinely carry API keys.
        log.warn(
                "Rejected a request whose body could not be parsed ({})",
                e.getMostSpecificCause().getClass().getSimpleName());
        return problem(
                HttpStatus.BAD_REQUEST,
                "The request body is missing or not valid JSON for this endpoint.",
                "malformed-request-body");
    }

    // DAST-2026-07-27 D5 fix - same catch-all-preemption problem as the four
    // handlers above, for the "wrong Content-Type entirely" case (e.g. POST
    // /api/signup with Content-Type: text/plain). Spring throws this BEFORE it
    // even attempts to read the body (no converter registered for that media
    // type can produce the target argument), which is a client mistake (wrong/
    // missing Content-Type), not a server fault - without this handler it fell
    // through to the generic 500 handler below and needlessly recorded a
    // PlatformIssue for what's really routine client-input noise (a stray
    // curl/monitoring probe, a misconfigured integration).
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        return errorResponse(
                e,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported Content-Type. This endpoint expects application/json.");
    }

    // Same catch-all-preemption problem as the three above, but for a URL that
    // matches no mapping at all: without this, hitting a wrong/stale path (a
    // scanner, a typo, a client on an old API version) produced a 500 plus an
    // Issues-portal row instead of a plain 404. Deliberately not logged - these
    // are routine noise, not incidents.
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, "No endpoint matches this request.");
    }

    // Wrong HTTP verb on a real path (e.g. GET on a POST-only route) is a client
    // mistake: 405 + Allow header, not the catch-all's 500 + Issues-portal row.
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        Map<String, Object> body =
                errors.body(
                        HttpStatus.METHOD_NOT_ALLOWED,
                        "Method '" + e.getMethod() + "' is not supported for this endpoint.",
                        ErrorResponseFactory.slugFor(HttpStatus.METHOD_NOT_ALLOWED),
                        null);
        ResponseEntity.BodyBuilder b = errors.builder(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null) {
            b.allow(
                    e.getSupportedHttpMethods()
                            .toArray(new org.springframework.http.HttpMethod[0]));
        }
        return b.body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(MethodArgumentNotValidException e) {
        String message =
                e.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("Invalid request");
        return problem(HttpStatus.BAD_REQUEST, message, "validation-failed");
    }

    // AsyncRequestNotUsableException extends IOException (Spring Framework
    // 6.1+), thrown by the servlet-async write path the moment it detects the
    // client is already gone (tab closed, network drop, navigated away mid-
    // response) - this app's chat endpoint (AgentController#chat, a
    // Flux<String> streamed over the reactive-to-servlet adapter) hits this
    // on every disconnect during a still-in-progress reply, which is normal,
    // constant traffic for a chat product, not a platform bug. Without this
    // handler it fell through to the generic Exception.class catch-all below,
    // which (confirmed live: 62 occurrences over 10 days, all
    // "ServletOutputStream failed to write: Broken pipe") logged a full
    // ERROR-level stack trace for routine client disconnects, recorded each
    // one as a PlatformIssue (flooding the Issues portal with non-issues),
    // and - worse - attempted to write a JSON error body via
    // ResponseEntity.body(...) onto the exact connection this exception
    // exists to say is no longer usable, which itself throws and doubles the
    // log noise. Returning null tells Spring MVC the response is already
    // finished and to attempt no further write - the documented-correct way
    // to handle this specific exception.
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Map<String, Object>> handleAsyncRequestNotUsable(
            AsyncRequestNotUsableException e) {
        log.debug("Client disconnected mid-stream (no longer writable): {}", e.getMessage());
        return null;
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            Throwable e, HttpStatus requested, String message) {
        return support.errorResponse(e, requested, message);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus requested, String message) {
        return support.errorResponse(requested, message);
    }

    private ResponseEntity<Map<String, Object>> problem(
            HttpStatus requested, String message, String typeSlug) {
        return support.problem(requested, message, typeSlug);
    }
}
