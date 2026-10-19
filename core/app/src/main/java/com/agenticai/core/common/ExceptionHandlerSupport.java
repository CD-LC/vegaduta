package com.agenticai.core.common;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.security.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helpers shared by the ordered {@code @RestControllerAdvice} chain ({@link
 * RequestBindingExceptionHandler}, {@link ResourceStateExceptionHandler}, {@link
 * ClientRequestExceptionHandler}, {@link UpstreamDependencyExceptionHandler}, {@link
 * FallbackExceptionHandler}): the response builders every handler goes through, the best-effort
 * Issues-portal recording, and the edge-proxy status mapping. Package-private on purpose - this is
 * the advices' private seam, not an API.
 */
final class ExceptionHandlerSupport {

    private static final Logger log = LoggerFactory.getLogger(ExceptionHandlerSupport.class);

    /** Body/content-type builder: RFC 9457 + legacy members, or legacy only. */
    private final ErrorResponseFactory errors;

    /** Nullable: only the advices that record issues pass one. */
    private final PlatformIssueService issueService;

    ExceptionHandlerSupport(ErrorResponseFactory errors, PlatformIssueService issueService) {
        this.errors = errors;
        this.issueService = issueService;
    }

    static boolean isRaisedByPlatformCode(Throwable e) {
        StackTraceElement[] stack = e.getStackTrace();
        return stack.length > 0 && stack[0].getClassName().startsWith("com.agenticai.");
    }

    private static boolean hasTenant() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .filter(id -> !id.isBlank())
                .isPresent();
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /**
     * An edge proxy / CDN such as Cloudflare in front of the API REPLACES any 502/504 an origin
     * returns with its own branded HTML page: our JSON body and the CORS headers never reach the
     * browser, so the app can only say "Load failed" (Safari) / "Failed to fetch" instead of e.g.
     * "Signup could not be completed - please try again". Every upstream-failure handler here
     * therefore answers 503, which such proxies pass through; the web client already treats 502 and
     * 503 alike.
     */
    static HttpStatus edgeSafe(HttpStatus status) {
        return status == HttpStatus.BAD_GATEWAY || status == HttpStatus.GATEWAY_TIMEOUT
                ? HttpStatus.SERVICE_UNAVAILABLE
                : status;
    }

    // Admin Issues portal (#425/#426) - this is the ONLY place an issue gets
    // recorded straight from an exception: every exception type mapped by a
    // named handler above already has a clear, client-actionable meaning
    // (bad request, not found, not configured yet, upstream 502, etc.) and
    // isn't a platform bug worth an admin's triage attention. Reaching this
    // generic catch-all specifically means "something nobody anticipated",
    // which is exactly what the Issues portal exists to surface. Recording
    // is best-effort and MUST NEVER throw back out of an exception handler -
    // that would replace the original error response with a worse one.
    void recordIssue(Exception e) {
        // platform_issue is a TENANT-schema table (V70 under db/migration/tenant): there is no
        // control-plane copy. A request with no tenant (permitAll endpoints, tenant-less platform
        // admins, background threads without a scope) resolves to the public/control-plane
        // search_path where the relation does not exist, so the write could only fail with
        // "relation platform_issue does not exist". Skip it; the exception is already logged.
        if (!hasTenant()) {
            log.debug(
                    "Not recording platform issue for {}: no tenant context on this thread",
                    e.getClass().getSimpleName());
            return;
        }
        try {
            issueService.record(
                    IssueSource.EXCEPTION,
                    null,
                    IssueSeverity.ERROR,
                    e.getClass().getSimpleName(),
                    e.getMessage());
        } catch (Exception recordingFailure) {
            log.warn(
                    "Failed to record platform issue for unhandled exception (original exception"
                            + " still returned to caller)",
                    recordingFailure);
        }
    }

    // Every body below goes through ErrorResponseFactory: legacy members
    // (timestamp/status/error + extras) always, RFC 9457 members (type/title/
    // detail/instance) unless app.errors.problem-details.enabled=false. The
    // `type` slug comes from the exception's own class for platform
    // exceptions (AgentNotFoundException -> agent-not-found) and from the
    // HTTP status for JDK/Spring ones. 502/504 are answered as 503 (edgeSafe).
    ResponseEntity<Map<String, Object>> errorResponse(
            Throwable e, HttpStatus requested, String message) {
        HttpStatus status = edgeSafe(requested);
        return errors.build(status, message, ErrorResponseFactory.slugFor(e, status));
    }

    ResponseEntity<Map<String, Object>> errorResponse(HttpStatus requested, String message) {
        HttpStatus status = edgeSafe(requested);
        return errors.build(status, message, ErrorResponseFactory.slugFor(status));
    }

    ResponseEntity<Map<String, Object>> problem(
            HttpStatus requested, String message, String typeSlug) {
        return errors.build(edgeSafe(requested), message, typeSlug);
    }

    // Same shape as errorResponse, plus a stable machine-readable `code` for
    // handlers whose HTTP status alone doesn't disambiguate the cause (e.g.
    // the two 403s Live Vision can throw - see
    // handleVisionApprovalRequired's comment). The RFC 9457 `type` is derived
    // from the same code (VISION_PLAN_LOCKED -> ...:vision-plan-locked).
    ResponseEntity<Map<String, Object>> errorResponseWithCode(
            HttpStatus requested, String message, String code) {
        HttpStatus status = edgeSafe(requested);
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", code);
        return errors.build(status, message, ErrorResponseFactory.slugForCode(code), extensions);
    }
}
