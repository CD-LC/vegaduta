package com.agenticai.core.common;

import com.agenticai.core.agents.appbuilder.AppBuilderTokenInvalidException;
import com.agenticai.core.agents.appbuilder.AppBuilderTokenMissingException;
import com.agenticai.core.agents.appbuilder.AppBuilderValidationException;
import com.agenticai.core.billing.gateway.InvalidWebhookSignatureException;
import com.agenticai.core.tenancy.CompGrantMalformedBodyException;
import com.agenticai.core.tenancy.CompGrantTokenInvalidException;
import com.agenticai.core.tenancy.CompGrantTokenMissingException;
import com.agenticai.core.tenancy.CompGrantValidationException;
import com.agenticai.core.tenancy.MasterKeyInvalidException;
import com.agenticai.core.tenancy.MasterKeyMissingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Rejected service-to-service credentials and signatures: the platform master key, the app-builder
 * and comp-grant {@code X-Service-Token}s (401 when absent, 403 when wrong, both fail closed), the
 * post-auth comp-grant body checks (400) and invalid payment-webhook signatures (403). Every 403 here
 * is logged: a stream of them can mean someone is probing the endpoint. Ordered right after {@link
 * ClientRequestExceptionHandler}; see {@link FallbackExceptionHandler} for the ordering rules. Every
 * type handled here extends {@code RuntimeException} directly.
 */
@RestControllerAdvice
@Order(ServiceCredentialExceptionHandler.ORDER)
public class ServiceCredentialExceptionHandler {

    /** Resolution order of this advice within the chain (lower runs first). */
    static final int ORDER = 310;

    private static final Logger log = LoggerFactory.getLogger(ServiceCredentialExceptionHandler.class);

    private final ExceptionHandlerSupport support;

    public ServiceCredentialExceptionHandler(ErrorResponseFactory errors) {
        this.support = new ExceptionHandlerSupport(errors, null);
    }

    // Same 401-missing/403-invalid split as CompGrantTokenMissingException/
    // CompGrantTokenInvalidException just below - a dedicated master-key
    // secret gating the tenant-deletion control, not the comp-grant one.
    @ExceptionHandler(MasterKeyMissingException.class)
    public ResponseEntity<Map<String, Object>> handleMasterKeyMissing(MasterKeyMissingException e) {
        return errorResponse(e, HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(MasterKeyInvalidException.class)
    public ResponseEntity<Map<String, Object>> handleMasterKeyInvalid(MasterKeyInvalidException e) {
        log.warn("Rejected tenant-deletion request with an invalid master key");
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(AppBuilderTokenMissingException.class)
    public ResponseEntity<Map<String, Object>> handleAppBuilderTokenMissing(
            AppBuilderTokenMissingException e) {
        return errorResponse(e, HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(AppBuilderTokenInvalidException.class)
    public ResponseEntity<Map<String, Object>> handleAppBuilderTokenInvalid(
            AppBuilderTokenInvalidException e) {
        log.warn("Rejected app-builder generate-files call with an invalid X-Service-Token");
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(AppBuilderValidationException.class)
    public ResponseEntity<Map<String, Object>> handleAppBuilderValidation(
            AppBuilderValidationException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // Academy Fast-Track Scholarship one-click fulfilment - service-token auth
    // on POST /api/tenant/comp-grant. 401 when no X-Service-Token was presented
    // (or the server has no token configured - fail closed); 403 when one was
    // presented but didn't match. Split mirrors the "no credential vs. bad
    // credential" distinction the contract asks for.
    @ExceptionHandler(CompGrantTokenMissingException.class)
    public ResponseEntity<Map<String, Object>> handleCompGrantTokenMissing(
            CompGrantTokenMissingException e) {
        return errorResponse(e, HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(CompGrantTokenInvalidException.class)
    public ResponseEntity<Map<String, Object>> handleCompGrantTokenInvalid(
            CompGrantTokenInvalidException e) {
        // Logged, same posture as the webhook-signature rejections above - a
        // stream of these can mean someone is probing the comp-grant endpoint.
        log.warn("Rejected comp-grant call with an invalid X-Service-Token");
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    // DAST-2026-07-27 D4 fix - CompGrantController now parses/validates its body
    // manually, AFTER authenticate() has already passed (see that controller's
    // javadoc for why: @Valid @RequestBody argument resolution used to run before
    // the method body, letting an unauthenticated caller learn the endpoint's
    // schema). These two mirror the messages the old @Valid/HttpMessageNotReadable
    // path produced, but can now only ever be reached post-auth.
    @ExceptionHandler(CompGrantMalformedBodyException.class)
    public ResponseEntity<Map<String, Object>> handleCompGrantMalformedBody(
            CompGrantMalformedBodyException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(CompGrantValidationException.class)
    public ResponseEntity<Map<String, Object>> handleCompGrantValidation(
            CompGrantValidationException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidWebhookSignature(
            InvalidWebhookSignatureException e) {
        // 403, same posture as TwilioWebhookSignatureException: deliberately
        // logged, since a stream of these could mean someone is probing
        // Stage 4's /webhook/razorpay endpoint rather than an honest
        // misconfiguration.
        log.warn("Rejected inbound webhook with an invalid Razorpay signature: {}", e.getMessage());
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            Throwable e, HttpStatus requested, String message) {
        return support.errorResponse(e, requested, message);
    }
}
