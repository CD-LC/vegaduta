package com.agenticai.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.security.EditLockedException;
import com.agenticai.core.security.TenantLimitExceededException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;

/**
 * DAST-2026-07-27 D5 - {@code POST /api/signup} (and any other JSON endpoint) with a wrong {@code
 * Content-Type} (e.g. {@code text/plain}) used to fall through to the advice chain's generic {@code
 * Exception.class} catch-all and return a 500, because {@link HttpMediaTypeNotSupportedException} -
 * thrown by Spring MVC before it even attempts to read the body, since no message converter
 * supports that media type for the target argument - had no dedicated handler in the chain. Only
 * the new handler is covered; the sibling {@code HttpMessageNotReadableException} handler
 * (unparseable JSON body, right Content-Type) already existed before this fix and is unchanged.
 *
 * <p>Extended for the Diagnostics build-out (2026-08): {@code handleUpstreamProvider} must record
 * through {@link DiagnosticService}'s per-provider fingerprint (never the generic
 * source=EXCEPTION/sourceId=null {@code recordIssue} that collapsed every provider's every failure
 * into one row - see {@code IssueSource#UPSTREAM_PROVIDER_FAILURE}'s javadoc), {@code
 * handleGuardrailViolation} must now record at all (it previously never did), and both must hand
 * the saved row's id to {@link AiDiagnosticAnalysisService}.
 */
class GlobalExceptionHandlerTest {

    // The single GlobalExceptionHandler is now an ordered chain of advices (see
    // FallbackExceptionHandler's javadoc); each test calls the advice that owns the handler.

    private PlatformIssueService platformIssueService;
    private DiagnosticService diagnosticService;
    private AiDiagnosticAnalysisService aiDiagnosticAnalysisService;
    private RequestBindingExceptionHandler binding;
    private ClientRequestExceptionHandler client;
    private UpstreamDependencyExceptionHandler upstream;
    private FallbackExceptionHandler fallback;

    @BeforeEach
    void setUp() {
        platformIssueService = mock(PlatformIssueService.class);
        diagnosticService = mock(DiagnosticService.class);
        aiDiagnosticAnalysisService = mock(AiDiagnosticAnalysisService.class);
        ErrorResponseFactory errors =
                new ErrorResponseFactory(true, ErrorResponseFactory.DEFAULT_TYPE_PREFIX);
        binding = new RequestBindingExceptionHandler(errors);
        client =
                new ClientRequestExceptionHandler(
                        diagnosticService, aiDiagnosticAnalysisService, errors);
        upstream =
                new UpstreamDependencyExceptionHandler(
                        platformIssueService,
                        diagnosticService,
                        aiDiagnosticAnalysisService,
                        errors);
        fallback = new FallbackExceptionHandler(platformIssueService, errors);
    }

    @Test
    void platformRaisedIllegalArgumentKeepsItsClientFacingMessage() {
        ResponseEntity<Map<String, Object>> response =
                fallback.handleIllegalArgument(
                        new IllegalArgumentException("Collection cannot be its own parent"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Collection cannot be its own parent", response.getBody().get("error"));
    }

    @Test
    void libraryRaisedIllegalArgumentDoesNotEchoInternalDetail() {
        IllegalArgumentException fromJdk;
        try {
            UUID.fromString("not-a-uuid-and-some-internal-detail");
            throw new AssertionError("expected IAE");
        } catch (IllegalArgumentException e) {
            fromJdk = e;
        }

        ResponseEntity<Map<String, Object>> response = fallback.handleIllegalArgument(fromJdk);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid request.", response.getBody().get("error"));
        verifyNoInteractions(platformIssueService);
    }

    @Test
    void optimisticLockFailureIsA409() {
        ResponseEntity<Map<String, Object>> response =
                upstream.handleOptimisticLock(
                        new org.springframework.orm.ObjectOptimisticLockingFailureException(
                                "com.agenticai.core.agents.core.Agent", UUID.randomUUID()));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(409, response.getBody().get("status"));
        verifyNoInteractions(platformIssueService);
    }

    @Test
    void wrongContentTypeReturns415NotAGeneric500() {
        HttpMediaTypeNotSupportedException e =
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map<String, Object>> response = binding.handleUnsupportedMediaType(e);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertEquals(415, response.getBody().get("status"));
    }

    @Test
    void responseStatusExceptionKeepsItsOwnStatusAndIsNotRecordedAsAnIssue() {
        // Regression: the Exception.class catch-all shadowed Spring's default
        // resolver, so a controller's 400 came back as a 500 "Something went
        // wrong" (Contacts page, prod 2026-09-24).
        org.springframework.web.server.ResponseStatusException e =
                new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "channel is required");

        ResponseEntity<Map<String, Object>> response = fallback.handleResponseStatus(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().get("status"));
        assertEquals("channel is required", response.getBody().get("error"));
        verifyNoInteractions(platformIssueService);
    }

    @Test
    void a503CausedByADataStoreOutageIsAnsweredWithoutAnIssueRecord() {
        // Redis-backed stores (Agent Lab, vision sessions) answer a deliberate 503 while Redis is
        // down.
        // That is infrastructure: the client still gets the 503, but there must be no per-request
        // Issues-portal row (and no ERROR with a stack trace) flooding during the outage.
        org.springframework.web.server.ResponseStatusException e =
                new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Agent Lab is temporarily unavailable. Try again shortly.",
                        new org.springframework.data.redis.RedisConnectionFailureException(
                                "Unable to connect to Redis"));

        ResponseEntity<Map<String, Object>> response = fallback.handleResponseStatus(e);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                "Agent Lab is temporarily unavailable. Try again shortly.",
                response.getBody().get("error"));
        verifyNoInteractions(platformIssueService);
    }

    @Test
    void anyOther503ResponseStatusExceptionIsStillRecordedAsAnIssue() {
        org.springframework.web.server.ResponseStatusException e =
                new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "down");

        com.agenticai.core.security.TenantContext.set(
                com.agenticai.core.security.TenantContext.of("t1", "u", java.util.Set.of()));
        try {
            fallback.handleResponseStatus(e);
        } finally {
            com.agenticai.core.security.TenantContext.clear();
        }

        verify(platformIssueService)
                .record(eq(IssueSource.EXCEPTION), isNull(), any(), anyString(), any());
    }

    @Test
    void unexpectedExceptionWithoutTenantIsNotRecordedAsIssue() {
        ResponseEntity<Map<String, Object>> response =
                fallback.handleUnexpected(new IllegalStateException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        verifyNoInteractions(platformIssueService);
    }

    @Test
    void responseStatusAnnotatedExceptionsAreAnsweredWithTheirDeclaredStatus() {
        ResponseEntity<Map<String, Object>> conflict =
                fallback.handleUnexpected(
                        new com.agenticai.core.quality.dataset.EvalRunConflictException(
                                "A run is already active"));
        ResponseEntity<Map<String, Object>> missing =
                fallback.handleUnexpected(
                        new com.agenticai.core.quality.dataset.EvalDatasetNotFoundException(
                                java.util.UUID.randomUUID()));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("A run is already active", conflict.getBody().get("error"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        verifyNoInteractions(platformIssueService);
    }

    @Test
    void missingRequiredHeaderIs400() throws NoSuchMethodException {
        java.lang.reflect.Method m = Object.class.getMethod("toString");
        org.springframework.core.MethodParameter p =
                new org.springframework.core.MethodParameter(m, -1);
        ResponseEntity<Map<String, Object>> response =
                binding.handleMissingRequestHeader(
                        new org.springframework.web.bind.MissingRequestHeaderException(
                                "X-Device-Token", p));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(
                "Required header 'X-Device-Token' is missing.", response.getBody().get("error"));
    }

    @Test
    void unreadableBodyStillReturns400() {
        org.springframework.http.converter.HttpMessageNotReadableException e =
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "bad json", mock(org.springframework.http.HttpInputMessage.class));

        ResponseEntity<Map<String, Object>> response = binding.handleUnreadableBody(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().get("status"));
    }

    // --- Diagnostics build-out (2026-08) wiring ---------------------------

    @Test
    void upstreamProviderFailureIsRecordedPerProviderNotThroughTheGenericCatchAllFingerprint() {
        UpstreamProviderException e =
                new UpstreamProviderException(
                        403,
                        "The LLM provider rejected the request (HTTP 403): permission denied",
                        "The AI provider rejected our credentials.",
                        "GOOGLE_VERTEX");
        PlatformIssue issue = mock(PlatformIssue.class);
        UUID issueId = UUID.randomUUID();
        when(issue.getId()).thenReturn(issueId);
        when(diagnosticService.recordUpstreamProviderFailure(e)).thenReturn(issue);

        ResponseEntity<Map<String, Object>> response = upstream.handleUpstreamProvider(e);

        // The response contract is unchanged apart from the edge-safe status: consumer message in
        // "error".
        // 503, not 502: Cloudflare replaces origin 502s (see ExceptionHandlerSupport#edgeSafe).
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("The AI provider rejected our credentials.", response.getBody().get("error"));
        // Recorded via the per-provider fingerprint...
        verify(diagnosticService).recordUpstreamProviderFailure(e);
        // ...NEVER via the generic source=EXCEPTION/sourceId=null path that
        // collapsed every provider's every failure into one identical row.
        verifyNoInteractions(platformIssueService);
        // The saved row's id is handed to the async triage step. The ChatClient
        // argument is null - no per-turn client exists at the handler layer,
        // and null is analyzeAsync's documented "skip the LLM step" input.
        verify(aiDiagnosticAnalysisService)
                .analyzeAsync(
                        isNull(),
                        eq(issueId),
                        eq(IssueSource.UPSTREAM_PROVIDER_FAILURE),
                        eq("GOOGLE_VERTEX"),
                        anyString(),
                        isNull());
    }

    @Test
    void upstreamProviderRecordingFailureNeverTriggersAnalysisOrBreaksTheResponse() {
        // DiagnosticService's contract on an internal recording failure is
        // "return null, never throw" - the handler must then skip the triage
        // hand-off (there is no row to enrich) and still answer normally.
        UpstreamProviderException e =
                new UpstreamProviderException(
                        429, "HTTP 429: rate_limit_exceeded", "The AI provider is busy.", "OPENAI");
        when(diagnosticService.recordUpstreamProviderFailure(e)).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = upstream.handleUpstreamProvider(e);

        // 503, not 502: Cloudflare replaces origin 502s (see ExceptionHandlerSupport#edgeSafe).
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verifyNoInteractions(aiDiagnosticAnalysisService);
    }

    @Test
    void guardrailViolationIsNowRecordedPerToolAndHandedToTriage() {
        GuardrailViolationException e =
                new GuardrailViolationException("web_search", "blocked by guardrail");
        PlatformIssue issue = mock(PlatformIssue.class);
        UUID issueId = UUID.randomUUID();
        when(issue.getId()).thenReturn(issueId);
        when(diagnosticService.recordGuardrailViolation(e)).thenReturn(issue);

        ResponseEntity<Map<String, Object>> response = client.handleGuardrailViolation(e);

        // The response contract is unchanged: 422 with the exception message.
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        verify(diagnosticService).recordGuardrailViolation(e);
        verify(platformIssueService, never()).record(any(), any(), any(), any(), any());
        verify(aiDiagnosticAnalysisService)
                .analyzeAsync(
                        isNull(),
                        eq(issueId),
                        eq(IssueSource.GUARDRAIL_FALSE_POSITIVE),
                        eq("web_search"),
                        anyString(),
                        isNull());
    }

    // --- A2-PR3 (narrowed scope) - FREE-tier edit-lock HTTP contract -------

    @Test
    void editLockedExceptionReturns423WithStructuredLockBody() {
        Instant editableUntil = Instant.parse("2026-08-01T00:00:00Z");
        EditLockedException e =
                new EditLockedException(
                        "Editing is a Premium feature.",
                        "free_tier_grace_window_expired",
                        editableUntil);

        ResponseEntity<Map<String, Object>> response = client.handleEditLocked(e);

        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertEquals(423, response.getBody().get("status"));
        assertEquals("Editing is a Premium feature.", response.getBody().get("error"));
        assertEquals("free_tier_grace_window_expired", response.getBody().get("lockedReason"));
        // ISO-8601 on the wire, per the "no string-matching" DTO contract -
        // a frontend consumer can bind this straight into an Instant/Date.
        assertEquals("2026-08-01T00:00:00Z", response.getBody().get("editableUntil"));
    }

    @Test
    void editLockedExceptionWithNullEditableUntilStillReturns423() {
        EditLockedException e =
                new EditLockedException(
                        "Editing is a Premium feature.", "free_tier_grace_window_expired", null);

        ResponseEntity<Map<String, Object>> response = client.handleEditLocked(e);

        assertEquals(HttpStatus.LOCKED, response.getStatusCode());
        assertEquals(null, response.getBody().get("editableUntil"));
    }

    @Test
    void unrelatedTenantLimitExceededCauseStillReturns403NotHijackedByEditLockHandler() {
        // A genuinely different TenantLimitExceededException cause (e.g. a
        // seat/agent-count ceiling) - NOT the EditLockedException subclass -
        // must keep hitting the plain handler and its flat 403 contract.
        TenantLimitExceededException e =
                new TenantLimitExceededException("Your plan allows up to 3 team members.");

        ResponseEntity<Map<String, Object>> response = client.handleTenantLimitExceeded(e);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, response.getBody().get("status"));
        assertEquals("Your plan allows up to 3 team members.", response.getBody().get("error"));
        // The 403 body is the plain errorResponse() shape - no lockedReason/
        // editableUntil keys leak in from the edit-lock contract.
        assertEquals(false, response.getBody().containsKey("lockedReason"));
        assertEquals(false, response.getBody().containsKey("editableUntil"));
    }

    @Test
    void wrongHttpVerbReturns405WithJsonBodyNotA500() {
        // Regression: GET /api/templates (POST-only path) fell into the
        // Exception.class catch-all and answered 500.
        org.springframework.web.HttpRequestMethodNotSupportedException e =
                new org.springframework.web.HttpRequestMethodNotSupportedException(
                        "GET", List.of("POST"));

        ResponseEntity<Map<String, Object>> response = binding.handleMethodNotSupported(e);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(405, response.getBody().get("status"));
        org.junit.jupiter.api.Assertions.assertFalse(
                String.valueOf(response.getBody().get("error")).isBlank());
        assertEquals(
                java.util.Set.of(org.springframework.http.HttpMethod.POST),
                response.getHeaders().getAllow());
        verifyNoInteractions(platformIssueService);
    }

    // --- Admin create-user error mapping (2026-10-01 prod failures) --------
    // POST /api/admin/users used to surface Keycloak's routine rejections
    // (duplicate email/username 409, User Profile validation 400) as a 502
    // carrying the raw upstream JSON and an internal service-account hint.

    @Test
    void keycloakUserConflictReturns409WithCleanMessage() {
        com.agenticai.core.admin.keycloak.KeycloakUserConflictException e =
                new com.agenticai.core.admin.keycloak.KeycloakUserConflictException(
                        "A user with this email already exists.", null);

        ResponseEntity<Map<String, Object>> response = upstream.handleKeycloakUserConflict(e);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("A user with this email already exists.", response.getBody().get("error"));
    }

    @Test
    void keycloakUserValidationReturns400WithCleanMessage() {
        com.agenticai.core.admin.keycloak.KeycloakUserValidationException e =
                new com.agenticai.core.admin.keycloak.KeycloakUserValidationException(
                        "Username must be between 3 and 255 characters.", null);

        ResponseEntity<Map<String, Object>> response = upstream.handleKeycloakUserValidation(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(
                "Username must be between 3 and 255 characters.", response.getBody().get("error"));
    }

    @Test
    void genericKeycloakAdminFailureKeeps502ButDropsTheOperatorHintFromTheBody() {
        com.agenticai.core.admin.keycloak.KeycloakAdminException e =
                new com.agenticai.core.admin.keycloak.KeycloakAdminException(
                        "Keycloak admin API call failed (set user enabled): HTTP 500 -"
                                + " {\"error\":\"x\"}. If this is 403, confirm the agentic-ai-core"
                                + " service account has the realm-management client's"
                                + " manage-users/view-users roles assigned.");

        ResponseEntity<Map<String, Object>> response = upstream.handleKeycloakAdmin(e);

        // 503, not 502: Cloudflare replaces origin 502s (see ExceptionHandlerSupport#edgeSafe).
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                "Keycloak admin API call failed (set user enabled): HTTP 500 - {\"error\":\"x\"}.",
                response.getBody().get("error"));
    }

    @Test
    void upstreamFailureStatusesAreMappedToOneCloudflarePassesThrough() {
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                ExceptionHandlerSupport.edgeSafe(HttpStatus.BAD_GATEWAY));
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                ExceptionHandlerSupport.edgeSafe(HttpStatus.GATEWAY_TIMEOUT));
        assertEquals(
                HttpStatus.BAD_REQUEST, ExceptionHandlerSupport.edgeSafe(HttpStatus.BAD_REQUEST));
        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ExceptionHandlerSupport.edgeSafe(HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
