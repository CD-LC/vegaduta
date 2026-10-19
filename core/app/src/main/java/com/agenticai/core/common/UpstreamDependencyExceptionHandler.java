package com.agenticai.core.common;

import static com.agenticai.core.common.ExceptionHandlerSupport.edgeSafe;
import static com.agenticai.core.common.ExceptionHandlerSupport.truncate;

import com.agenticai.core.admin.keycloak.KeycloakAdminException;
import com.agenticai.core.admin.keycloak.KeycloakUserConflictException;
import com.agenticai.core.admin.keycloak.KeycloakUserValidationException;
import com.agenticai.core.agents.appbuilder.AppBuilderGenerationException;
import com.agenticai.core.agents.decision.JevCredentialValidationException;
import com.agenticai.core.agents.decision.JevNotConfiguredException;
import com.agenticai.core.agents.decision.JevUpstreamException;
import com.agenticai.core.agents.hosted.FreeTierProviderPoolEmptyException;
import com.agenticai.core.agents.tools.code.ToolSandboxUnavailableException;
import com.agenticai.core.agents.vision.VisionDisabledException;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.billing.gateway.PaymentGatewayException;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.knowledge.FeatureUnavailableException;
import com.agenticai.core.knowledge.SidecarCommunicationException;
import com.agenticai.core.knowledge.connector.ConnectorFetchException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.providers.ProviderModelCatalogEmptyException;
import com.agenticai.core.providers.capability.CapabilityCredentialValidationException;
import com.agenticai.core.providers.capability.CapabilityNotConfiguredException;
import com.agenticai.core.providers.capability.CapabilityUpstreamException;
import com.agenticai.core.providers.capability.UnknownCapabilityException;
import com.agenticai.core.sdlc.E2bApiException;
import com.agenticai.core.sdlc.E2bNotConfiguredException;
import com.agenticai.core.tenancy.TenantProvisioningException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Failures of something the platform depends on rather than of the request: an outbound call (LLM
 * provider, sidecar, tool sandbox, channel API, Keycloak, payment gateway, E2B, capability
 * provider) that failed (502, answered as 503 - see {@link ExceptionHandlerSupport#edgeSafe}), a
 * provider or feature that is not configured/ready yet (400/503), the database being unreachable
 * (503) and optimistic-lock contention (409). Fourth advice of the ordered chain; see {@link
 * FallbackExceptionHandler} for the ordering rules.
 *
 * <p>Two handled hierarchies are kept whole here on purpose, because Spring picks the most specific
 * handler only WITHIN one advice: the capability registry's {@code UnknownCapabilityException} /
 * {@code CapabilityCredentialValidationException} / {@code CapabilityNotConfiguredException} /
 * {@code CapabilityUpstreamException} together with the Jev subclasses of the last three, and
 * {@code KeycloakAdminException} together with its two subclasses {@code
 * KeycloakUserConflictException} and {@code KeycloakUserValidationException}. {@code
 * DataAccessResourceFailureException} and the two optimistic-lock types are Spring/JPA types no
 * platform exception extends.
 */
@RestControllerAdvice
@Order(UpstreamDependencyExceptionHandler.ORDER)
public class UpstreamDependencyExceptionHandler {

    /** Resolution order of this advice within the chain (lower runs first). */
    static final int ORDER = 400;

    private static final Logger log =
            LoggerFactory.getLogger(UpstreamDependencyExceptionHandler.class);

    private final PlatformIssueService issueService;

    /**
     * Diagnostics build-out (2026-08) - both nullable, null-safe at every use: with {@code
     * diagnosticService} absent, {@code handleUpstreamProvider} degrades to its pre-build-out
     * behavior (generic {@link #recordIssue}) rather than failing an exception handler over an
     * observability wire.
     */
    private final DiagnosticService diagnosticService;

    private final AiDiagnosticAnalysisService aiDiagnosticAnalysisService;

    /** Body/content-type builder: RFC 9457 + legacy members, or legacy only. */
    private final ErrorResponseFactory errors;

    private final ExceptionHandlerSupport support;

    public UpstreamDependencyExceptionHandler(
            PlatformIssueService issueService,
            DiagnosticService diagnosticService,
            AiDiagnosticAnalysisService aiDiagnosticAnalysisService,
            ErrorResponseFactory errors) {
        this.issueService = issueService;
        this.diagnosticService = diagnosticService;
        this.aiDiagnosticAnalysisService = aiDiagnosticAnalysisService;
        this.errors = errors;
        this.support = new ExceptionHandlerSupport(errors, issueService);
    }

    // 4 LOW-severity backend audit findings, item 3/4: AssistService and
    // WorkflowGraphGeneratorService's resolveModelFor previously fell back
    // silently to an Anthropic-only default model id whenever a non-Anthropic
    // provider's catalog was empty, re-introducing the exact wrong-provider-
    // model 404 both methods exist to avoid (see either service's javadoc).
    // 503, same "the platform itself isn't ready to serve this specific
    // request right now" reasoning as FreeTierProviderPoolEmptyException just
    // below - the tenant already has an active, valid BYOK credential for
    // this provider, the catalog cache is just empty right now.
    @ExceptionHandler(ProviderModelCatalogEmptyException.class)
    public ResponseEntity<Map<String, Object>> handleProviderModelCatalogEmpty(
            ProviderModelCatalogEmptyException e) {
        log.warn("Provider model catalog was empty when a model id was needed: {}", e.getMessage());
        return errorResponse(e, HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    // Tenant Jev BYOK (core/agents/decision) - same three-way split the Sarvam
    // pair above uses: "key rejected on activation" -> 400, "Jev-dependent call
    // with no key resolved" -> 400, "upstream TypeSafe call itself failed" ->
    // 502 (our server made an outbound call that failed, same posture as
    // SarvamApiException/E2bApiException).
    @ExceptionHandler(JevCredentialValidationException.class)
    public ResponseEntity<Map<String, Object>> handleJevCredentialValidation(
            JevCredentialValidationException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(JevNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleJevNotConfigured(JevNotConfiguredException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(JevUpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleJevUpstream(JevUpstreamException e) {
        log.warn("Jev upstream call failed: {}", e.getMessage(), e);
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    // Generic capability registry (core/providers/capability +
    // core/agents/decision) - the /api/capabilities/** counterparts of the Jev
    // trio above: unknown capability/provider -> 400, rejected credential ->
    // 400, capability call with nothing configured -> 400, upstream failure on
    // a fail-visible surface -> 502. The Jev exceptions extend these types;
    // their handlers above stay because they name the brand in the log line.
    @ExceptionHandler(UnknownCapabilityException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownCapability(
            UnknownCapabilityException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(CapabilityCredentialValidationException.class)
    public ResponseEntity<Map<String, Object>> handleCapabilityCredentialValidation(
            CapabilityCredentialValidationException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(CapabilityNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleCapabilityNotConfigured(
            CapabilityNotConfiguredException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(CapabilityUpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleCapabilityUpstream(
            CapabilityUpstreamException e) {
        log.warn("Capability upstream call failed: {}", e.getMessage(), e);
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    // SDLC automation sandbox scaffold (#849) - same "not configured yet"
    // 400 vs "outbound call to E2B failed" 502 split as the Sarvam pair
    // just above.
    @ExceptionHandler(E2bNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleE2bNotConfigured(E2bNotConfiguredException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(E2bApiException.class)
    public ResponseEntity<Map<String, Object>> handleE2bApi(E2bApiException e) {
        log.warn("E2B API call failed: {}", e.getMessage(), e);
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(FreeTierProviderPoolEmptyException.class)
    public ResponseEntity<Map<String, Object>> handleFreeTierPoolEmpty(
            FreeTierProviderPoolEmptyException e) {
        // 503: the platform itself isn't ready to serve this request (no
        // hosted LLM provider configured yet) - a platform-operator gap, not
        // a client mistake, worth logging since every occurrence means real
        // FREE-tier users are being turned away.
        log.warn("FREE-tier chat request rejected: no hosted LLM provider is enabled");
        return errorResponse(e, HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(UpstreamProviderException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamProvider(UpstreamProviderException e) {
        // 502: our server made an upstream call (to the LLM provider) that failed.
        // upstreamStatus (the real provider status - 400 for a billing/invalid-
        // request error, 429 for rate limiting, etc.) rides along as its own
        // field for clients that need to distinguish cases programmatically,
        // rather than remapping it to a "matching" status here - provider
        // status codes don't map cleanly onto our own API's semantics (their
        // 400 isn't necessarily our client's fault).
        //
        // Direct feedback ("proper meaningful messages to consumer, not raw
        // http responses...even if there is an issue from llm response") -
        // "error" now carries UpstreamProviderException#getConsumerMessage()
        // (a short, plain-language sentence, see ProviderErrorTranslator
        // #classify), NOT e.getMessage() - that one still holds the raw
        // "HTTP 429: rate_limit_exceeded: ..." technical detail, which is
        // logged here (this handler previously logged nothing at all, unlike
        // every other 502-mapped handler in this class) and stays out of the
        // response body entirely.
        // Direct feedback ("Log all LLM provider exceptions and application
        // errors. Store sufficient context for future analysis... Ensure
        // these logs are accessible for future review and troubleshooting.")
        // - `, e` on this log line (previously just the message) means
        // Logback prints the full stack trace beneath it, and the request's
        // requestId/tenantId/userId ride along automatically via the MDC
        // pattern in application.yml (see RequestCorrelationFilter/
        // TenantContextFilter). recordIssue additionally makes this
        // reviewable from the Issues portal UI, not just raw server logs -
        // every other upstream-call-failed handler in this class
        // (SidecarCommunicationException, GoogleApiException, etc.) already
        // logs with cause; this one previously didn't and never recorded an
        // issue at all, unlike the generic catch-all below.
        log.warn(
                "Upstream LLM provider call failed (status {}): {}",
                e.getUpstreamStatus(),
                e.getMessage(),
                e);
        // Diagnostics build-out (2026-08): recorded per-PROVIDER via
        // DiagnosticService (IssueSource.UPSTREAM_PROVIDER_FAILURE, sourceId =
        // provider name), replacing the generic recordIssue(e) whose
        // source=EXCEPTION/sourceId=null fingerprint collapsed EVERY provider's
        // EVERY failure into one indistinguishable row - see IssueSource's
        // javadoc for the Vertex-403 incident that motivated this. The saved
        // row's id is then handed to AiDiagnosticAnalysisService for the async
        // LLM triage + notification step; no per-turn ChatClient exists at this
        // handler layer, and a null client is that service's documented
        // "skip the LLM analysis silently" input (see analyzeAsync's javadoc),
        // so the recording itself is never blocked on one being available.
        recordUpstreamProviderIssue(e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("upstreamStatus", e.getUpstreamStatus());
        // A misconfigured hosted-pool model (confirmed in production: a stored
        // model id with one stray leading space made every platform-hosted turn
        // fail with Google's 404 NOT_FOUND) was previously invisible from the
        // UI - "error" is deliberately a generic, plain-language sentence, so
        // the provider's OWN text ("model not found", "insufficient quota", ...)
        // only ever reached the server log. Added as a SEPARATE field so the
        // existing consumer-facing contract is untouched: clients that only read
        // "error" behave exactly as before, while an operator/admin surface can
        // show this one to make a misconfiguration diagnosable without shell
        // access. Safe to expose - ProviderErrorTranslator is the only producer
        // of this message and it redacts credential-shaped substrings (see
        // ProviderErrorTranslator#redactSecrets); truncated because an upstream
        // error body can be arbitrarily large.
        body.put("providerMessage", truncate(e.getMessage(), 500));
        // Deterministic client-side recovery chain (Selected Model -> Platform
        // LLM -> Edge AI -> user-facing error): false for every ordinary
        // per-provider failure, true only when the WHOLE platform-hosted pool
        // is exhausted (see UpstreamProviderException#isEdgeAiRecoverable /
        // HostedPoolExhaustedException) - the client is expected to attempt
        // on-device Edge AI before rendering this as an error whenever it's true.
        body.put("edgeAiRecoverable", e.isEdgeAiRecoverable());
        HttpStatus status = edgeSafe(HttpStatus.BAD_GATEWAY);
        return errors.build(
                status, e.getConsumerMessage(), ErrorResponseFactory.slugFor(e, status), body);
    }

    @ExceptionHandler(ConnectorFetchException.class)
    public ResponseEntity<Map<String, Object>> handleConnectorFetch(ConnectorFetchException e) {
        log.warn("Connector fetch failure: {}", e.getMessage());
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /**
     * A capability that needs the optional Python sidecar was asked for while it is switched off
     * ({@code app.sidecar.enabled=false}). Not a bad gateway - nothing was attempted - so 503 with the
     * message that says what to enable. More specific than the {@link SidecarCommunicationException}
     * handler below, which it extends.
     */
    @ExceptionHandler(FeatureUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleFeatureUnavailable(FeatureUnavailableException e) {
        log.info("Feature unavailable: {}", e.getMessage());
        return errorResponse(e, HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(SidecarCommunicationException.class)
    public ResponseEntity<Map<String, Object>> handleSidecarCommunication(
            SidecarCommunicationException e) {
        // 502 for the same reason as UpstreamProviderException: our server made
        // an outbound call (to the Python sidecar, not an LLM provider this time)
        // that failed - most commonly the sidecar simply isn't running yet.
        // Logged here (with cause, for the full stack trace) since the other
        // upstream-style handlers don't log either - this one gets an explicit
        // log because sidecar failures are new and less understood in the
        // field than provider failures at this point.
        log.warn("Sidecar communication failure: {}", e.getMessage(), e);
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    // Custom Code Tools (com.agenticai.core.agents.tools.code). 502 for exactly the
    // same reason as SidecarCommunicationException directly above: our server
    // made an outbound call - to the tool-sandbox microservice - that failed,
    // most commonly because that service isn't running. Critically, this is
    // NEVER "the tenant's code is bad": the sandbox returns validation and
    // execution VERDICTS as HTTP 200 bodies, so reaching this handler always
    // means a genuine transport/auth/service fault, and telling the tenant to
    // retry is the correct advice.
    @ExceptionHandler(ToolSandboxUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleToolSandboxUnavailable(
            ToolSandboxUnavailableException e) {
        log.warn("Tool sandbox unavailable: {}", e.getMessage());
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(ChannelDeliveryException.class)
    public ResponseEntity<Map<String, Object>> handleChannelDelivery(ChannelDeliveryException e) {
        // 502, same reasoning as UpstreamProviderException/SidecarCommunicationException:
        // our server made an outbound call to a third-party channel API that failed.
        log.warn("Channel delivery failure ({}): {}", e.getChannelName(), e.getMessage(), e);
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    // Admin-created user collides with an existing one (409) or fails the
    // realm's User Profile validation (400) - both are things the CALLER can
    // fix, so they map to 4xx with the clean messages those exceptions carry,
    // not the raw upstream JSON a generic 502 surfaces. Real prod failures
    // observed 2026-10-01: "User exists with same email" and username
    // error-invalid-length both reached tenant admins as an inscrutable 502.
    @ExceptionHandler(KeycloakUserConflictException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloakUserConflict(
            KeycloakUserConflictException e) {
        log.warn("Keycloak user creation conflict: {}", e.getMessage());
        return errorResponse(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(KeycloakUserValidationException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloakUserValidation(
            KeycloakUserValidationException e) {
        log.warn("Keycloak user creation rejected: {}", e.getMessage());
        return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(KeycloakAdminException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloakAdmin(KeycloakAdminException e) {
        // 502: our server made an upstream call (to Keycloak's Admin REST API)
        // that failed - same class of failure as UpstreamProviderException/
        // SidecarCommunicationException/ChannelDeliveryException. The message
        // may end with an operator-only hint ("If this is 403, confirm the
        // service account has...") - keep it in the log, off the response
        // body, since a tenant admin can do nothing with it.
        log.warn("Keycloak admin API failure: {}", e.getMessage(), e);
        String message = e.getMessage();
        if (message != null) {
            int hintAt = message.indexOf(". If this is 403,");
            if (hintAt >= 0) {
                message = message.substring(0, hintAt) + ".";
            }
        }
        return errorResponse(e, HttpStatus.BAD_GATEWAY, message);
    }

    @ExceptionHandler(TenantProvisioningException.class)
    public ResponseEntity<Map<String, Object>> handleTenantProvisioning(
            TenantProvisioningException e) {
        // 502: by the time this is thrown, TenantProvisioningService has
        // already compensated (deleted the orphaned Keycloak user, dropped
        // the orphaned schema) and marked the Tenant row PROVISIONING_FAILED
        // - logged with the full cause chain here since "why did signup
        // fail" is exactly the kind of thing worth a stack trace for.
        log.error("Tenant provisioning failed: {}", e.getMessage(), e);
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(AppBuilderGenerationException.class)
    public ResponseEntity<Map<String, Object>> handleAppBuilderGeneration(
            AppBuilderGenerationException e) {
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentGateway(PaymentGatewayException e) {
        // 502: our server made an outbound call (to Razorpay's API) that
        // failed - same class of failure as KeycloakAdminException/
        // SidecarCommunicationException. During Stage 3's signup flow this
        // never actually reaches a controller (TenantProvisioningService
        // catches it and rethrows as TenantProvisioningException, already
        // mapped below); this handler exists for Stage 4+'s billing
        // endpoints, which call PaymentGatewayClient directly.
        log.warn("Payment gateway call failed: {}", e.getMessage(), e);
        return errorResponse(e, HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    // 503: the platform-wide app.vision.enabled flag is off - the platform
    // itself isn't serving vision right now, same reasoning as
    // FreeTierProviderPoolEmptyException.
    @ExceptionHandler(VisionDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleVisionDisabled(VisionDisabledException e) {
        return errorResponse(e, HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    // JAVA-007: Tenant, Agent, WorkflowDefinition, StudioApp and
    // PaymentTransaction carry @Version. A concurrent writer that loses the
    // race gets a 409 (reload and retry) instead of silently overwriting the
    // winner's columns. Not recorded as a platform issue - it is expected
    // contention, not a bug.
    @ExceptionHandler({
        org.springframework.dao.OptimisticLockingFailureException.class,
        jakarta.persistence.OptimisticLockException.class
    })
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(Exception e) {
        log.info("Concurrent modification rejected: {}", e.getMessage());
        return errorResponse(
                e,
                HttpStatus.CONFLICT,
                "This item was changed by someone else while you were editing it. Reload and try"
                        + " again.");
    }

    // #815 Governance follow-up (Fail-safe: safe-default behavior when a
    // dependency is down) - DB unavailable. Spring's JDBC exception
    // translation wraps "can't get/use a connection" failures (pool
    // exhausted, Postgres actually down, network partition) as this type
    // (CannotGetJdbcConnectionException, HikariCP's pool-timeout failure,
    // etc. all extend it) - distinct from DataIntegrityViolationException
    // and friends, which are real query-level failures against a working
    // DB and correctly stay unmapped, falling through to the generic
    // handler below. Before this handler existed, a DB outage looked
    // identical to any other unanticipated bug: an opaque 500 and an
    // Issues-portal row with no hint it was infrastructure, not application
    // logic. 503 (Service Unavailable) is the correct status - it tells a
    // well-behaved client "retry later," which a 500 doesn't - and the
    // clearly-labeled issue lets an admin immediately recognize a DB outage
    // instead of triaging it as a mystery exception.
    @ExceptionHandler(org.springframework.dao.DataAccessResourceFailureException.class)
    public ResponseEntity<Map<String, Object>> handleDatabaseUnavailable(
            org.springframework.dao.DataAccessResourceFailureException e) {
        log.error("Database appears to be unavailable", e);
        try {
            issueService.record(
                    IssueSource.EXCEPTION,
                    null,
                    IssueSeverity.ERROR,
                    "Database unavailable",
                    "DataAccessResourceFailureException: " + e.getMessage());
        } catch (Exception recordingFailure) {
            log.warn("Failed to record platform issue for DB-unavailable event", recordingFailure);
        }
        return errorResponse(
                e,
                HttpStatus.SERVICE_UNAVAILABLE,
                "The platform is temporarily unavailable. Please try again shortly.");
    }

    /**
     * Diagnostics build-out (2026-08) - {@code handleUpstreamProvider}'s recording path: a
     * per-provider fingerprint via {@link DiagnosticService} (see {@code
     * IssueSource#UPSTREAM_PROVIDER_FAILURE}'s javadoc for why the generic {@link #recordIssue}
     * fingerprint was wrong for this exception specifically), then the saved row's id handed to
     * {@link AiDiagnosticAnalysisService#analyzeAsync}. With no {@code DiagnosticService} wired
     * (older/partial contexts, direct construction), degrades to the exact pre-build-out {@link
     * #recordIssue} call rather than silently recording nothing. Best-effort like every recording
     * here - both delegates are internally non-throwing, and {@code analyzeAsync} with a null
     * {@code ChatClient} is its documented silent skip.
     */
    private void recordUpstreamProviderIssue(UpstreamProviderException e) {
        if (diagnosticService == null) {
            recordIssue(e);
            return;
        }
        com.agenticai.core.audit.PlatformIssue issue =
                diagnosticService.recordUpstreamProviderFailure(e);
        if (issue != null && aiDiagnosticAnalysisService != null) {
            // Same "unknown-provider" normalization DiagnosticService itself applies.
            String providerName =
                    e.getProviderName() == null || e.getProviderName().isBlank()
                            ? "unknown-provider"
                            : e.getProviderName();
            aiDiagnosticAnalysisService.analyzeAsync(
                    null,
                    issue.getId(),
                    IssueSource.UPSTREAM_PROVIDER_FAILURE,
                    providerName,
                    e.getMessage(),
                    null);
        }
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            Throwable e, HttpStatus requested, String message) {
        return support.errorResponse(e, requested, message);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus requested, String message) {
        return support.errorResponse(requested, message);
    }

    private void recordIssue(Exception e) {
        support.recordIssue(e);
    }
}
