package com.agenticai.core.common;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.core.AgentModelNotSupportedException;
import com.agenticai.core.agents.core.TenantUnavailableException;
import com.agenticai.core.agents.face.BiometricConsentNotEnabledException;
import com.agenticai.core.agents.modality.UnsupportedAttachmentTypeException;
import com.agenticai.core.agents.spendcap.SpendCapReachedException;
import com.agenticai.core.agents.tools.code.CustomCodeToolsDisabledException;
import com.agenticai.core.agents.turn.AttachmentModalityNotSupportedException;
import com.agenticai.core.agents.turn.AttachmentPayloadTooLargeException;
import com.agenticai.core.agents.turn.AttachmentTooLargeException;
import com.agenticai.core.agents.turn.MalformedAttachmentException;
import com.agenticai.core.agents.vision.VisionApprovalRequiredException;
import com.agenticai.core.agents.vision.VisionNotAllowedForPlanException;
import com.agenticai.core.agents.vision.VisionQuotaExceededException;
import com.agenticai.core.approval.ApprovalNotAuthorizedException;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.billing.commerce.ProductCatalogService;
import com.agenticai.core.chat.gateway.LlmGatewayLimitExceededException;
import com.agenticai.core.common.quicktask.QuickTaskNotReadyException;
import com.agenticai.core.devices.DeviceException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.knowledge.InvalidKnowledgeUploadException;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.session.InboundRateLimitedException;
import com.agenticai.core.providers.NoActiveProviderException;
import com.agenticai.core.providers.ProviderNotActiveException;
import com.agenticai.core.providers.validation.ProviderNotSupportedException;
import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.rl.api.RlArmRejectedException;
import com.agenticai.core.secrets.SecretWriteForbiddenException;
import com.agenticai.core.security.EditLockedException;
import com.agenticai.core.security.ImpersonationReadOnlyException;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.sharing.InvalidGranteeException;
import com.agenticai.core.sharing.ShareRateLimitExceededException;
import com.agenticai.core.templates.TemplateImportLimitException;
import com.agenticai.core.templates.TemplateNotReadyException;
import com.agenticai.core.templates.TemplateSubmissionLimitException;
import com.agenticai.core.tenancy.SignupRateLimitExceededException;
import com.agenticai.core.tenancy.TenantUpgradeException;
import com.agenticai.core.voice.LiveKitNotConfiguredException;
import com.agenticai.core.voice.VoiceCallController;
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
 * Platform domain exceptions the CALLER can act on: request validation (400), missing or rejected
 * credentials (401/403), plan ceilings and budgets (402/403/423), payload shape (413/415),
 * guardrail verdicts (422) and abuse/rate caps (429). Third advice of the ordered chain; service
 * credentials live in {@link ServiceCredentialExceptionHandler} and agent/workflow execution
 * verdicts in {@link ExecutionPolicyExceptionHandler}, right after it. See {@link
 * FallbackExceptionHandler} for the ordering rules.
 *
 * <p>The one handled hierarchy in this class is kept whole here on purpose: {@code
 * TenantLimitExceededException} (403) and its two strict subclasses {@code
 * PerUserBudgetExhaustedException} (402) and {@code EditLockedException} (423). Spring resolves the
 * most specific handler WITHIN one advice, so all three must live in the same class - were the
 * superclass handler in an earlier advice than a subclass handler, the subclass would never reach
 * its own handler. Nothing else here is extended by another handled type.
 */
@RestControllerAdvice
@Order(ClientRequestExceptionHandler.ORDER)
public class ClientRequestExceptionHandler {

    /** Resolution order of this advice within the chain (lower runs first). */
    static final int ORDER = 300;

    private static final Logger log = LoggerFactory.getLogger(ClientRequestExceptionHandler.class);

    /**
     * Diagnostics build-out (2026-08) - both nullable, null-safe at every use: with {@code
     * diagnosticService} absent, {@code handleGuardrailViolation} degrades to its pre-build-out
     * behavior (no issue at all) rather than failing an exception handler over an observability
     * wire.
     */
    private final DiagnosticService diagnosticService;

    private final AiDiagnosticAnalysisService aiDiagnosticAnalysisService;

    /** Body/content-type builder: RFC 9457 + legacy members, or legacy only. */
    private final ErrorResponseFactory errors;

    private final ExceptionHandlerSupport support;

    public ClientRequestExceptionHandler(
            DiagnosticService diagnosticService,
            AiDiagnosticAnalysisService aiDiagnosticAnalysisService,
            ErrorResponseFactory errors) {
        this.diagnosticService = diagnosticService;
        this.aiDiagnosticAnalysisService = aiDiagnosticAnalysisService;
        this.errors = errors;
        this.support = new ExceptionHandlerSupport(errors, null);
    }

    @ExceptionHandler(ProviderValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ProviderValidationException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ProviderNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleNotSupported(ProviderNotSupportedException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ProviderNotActiveException.class)
    public ResponseEntity<Map<String, Object>> handleNotActive(ProviderNotActiveException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // Ask-AI helper (AssistService) hitting "nothing active at all" - same
    // client-actionable shape as ProviderNotActiveException just above, kept
    // as a separate exception type since it isn't about any one provider.
    @ExceptionHandler(NoActiveProviderException.class)
    public ResponseEntity<Map<String, Object>> handleNoActiveProvider(NoActiveProviderException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // Self-hosted voice/video calling (Phase 1, self-reliance initiative) -
    // same "not configured yet" 400 shape as every other *NotConfiguredException
    // above; LIVEKIT_API_KEY/LIVEKIT_API_SECRET unset in this deployment.
    @ExceptionHandler(LiveKitNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleLiveKitNotConfigured(
            LiveKitNotConfiguredException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(VoiceCallController.VoiceTurnAudioEmptyException.class)
    public ResponseEntity<Map<String, Object>> handleVoiceTurnAudioEmpty(
            VoiceCallController.VoiceTurnAudioEmptyException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // core/secrets (the tenant vault) - writing a TENANT-scoped secret without
    // workspace-admin rights. 403, not 404, and deliberately the opposite
    // posture to ResourceAccessDeniedException just above: every member can
    // already SEE a shared secret's name in the vault list, so there is no
    // existence secret left to protect and a 404 would only disguise a
    // permission problem as a bug. See the exception's own javadoc.
    @ExceptionHandler(SecretWriteForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleSecretWriteForbidden(
            SecretWriteForbiddenException e) {
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    // core/templates - the per-tenant-per-day submission abuse cap. 429.
    @ExceptionHandler(TemplateSubmissionLimitException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateSubmissionLimit(
            TemplateSubmissionLimitException e) {
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    // core/templates - the per-user-per-day import abuse cap
    // (TemplateImportRateLimiter). 429, same posture as the submission cap.
    @ExceptionHandler(TemplateImportLimitException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateImportLimit(
            TemplateImportLimitException e) {
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    // core/templates - a submission that fails the published standard. 422 with the WHOLE report
    // (every blocker + advisory + detected requirements) so the UI can show all problems at once.
    @ExceptionHandler(TemplateNotReadyException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateNotReady(TemplateNotReadyException e) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", "TEMPLATE_NOT_READY");
        extensions.put("report", e.getReport());
        return errors.build(
                HttpStatus.UNPROCESSABLE_ENTITY,
                e.getMessage(),
                ErrorResponseFactory.slugFor(e, HttpStatus.UNPROCESSABLE_ENTITY),
                extensions);
    }

    // core/sharing - the target of a share-grant isn't a valid same-tenant
    // recipient (unknown email, or a different tenant's user). 403, not 404:
    // the caller here is always the resource's own owner/admin, so there is
    // no resource-existence secret to protect - see InvalidGranteeException's
    // own javadoc for what the generic message still hides.
    @ExceptionHandler(InvalidGranteeException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidGrantee(InvalidGranteeException e) {
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    // core/sharing - the per-caller fixed-window cap on the share-grant
    // surface (ShareRateLimiter: grant creation + grant resolution). 429; the
    // message stays generic (never the window, limit, or count) - see the
    // exception's own javadoc.
    @ExceptionHandler(ShareRateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleShareRateLimitExceeded(
            ShareRateLimitExceededException e) {
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    // core/omnichannel - the per-sender / per-channel turn cap every inbound channel shares
    // (InboundRateLimiter, raised from ChannelSessionService#resolveOrCreate). 429 with Retry-After;
    // the message is the constant friendly text, never the limit or the count.
    @ExceptionHandler(InboundRateLimitedException.class)
    public ResponseEntity<Map<String, Object>> handleInboundRateLimited(
            InboundRateLimitedException e) {
        ResponseEntity<Map<String, Object>> base =
                errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        return ResponseEntity.status(base.getStatusCode())
                .headers(base.getHeaders())
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(base.getBody());
    }

    // core/billing commerce - the per-tenant-per-day cap on the PUBLIC
    // product-feed checkout (the one unauthenticated payment-link writer).
    // 429, same posture as the other abuse caps above.
    @ExceptionHandler(ProductCatalogService.FeedCheckoutLimitException.class)
    public ResponseEntity<Map<String, Object>> handleFeedCheckoutLimit(
            ProductCatalogService.FeedCheckoutLimitException e) {
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    // core/rl arm management (POST/DELETE /api/rl/agents/*/policies/*/arms).
    // These three exist because the ORIGINAL failure here was silence: a
    // MODEL_ROUTE policy sat enabled with zero arms in production, training
    // nothing, and nothing ever said so. Every rejection from that surface is
    // therefore a specific, operator-readable 4xx rather than a swallowed
    // no-op - RlArmRejectedException's own messages are written for a human
    // and are safe to show verbatim (they never echo the request body).
    @ExceptionHandler(RlArmRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRlArmRejected(RlArmRejectedException e) {
        // 400: the caller is authorized and the agent exists - the proposed arm
        // config itself is unusable (malformed JSON, no "model" field, a model
        // this tenant's provider can't serve, a platform-pool agent that would
        // never consult the arm).
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(com.agenticai.core.tenancy.account.ReauthRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleReauthRequired(
            com.agenticai.core.tenancy.account.ReauthRequiredException e) {
        // 401 + stable code: the client re-authenticates (prompt=login&max_age=0) and retries.
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", com.agenticai.core.tenancy.account.ReauthRequiredException.CODE);
        extensions.put("max_age", e.getMaxAgeSeconds());
        return errors.build(
                HttpStatus.UNAUTHORIZED,
                e.getMessage(),
                ErrorResponseFactory.slugForCode(
                        com.agenticai.core.tenancy.account.ReauthRequiredException.CODE),
                extensions);
    }

    // Stage 4 (Chat file upload) - both are client-actionable "this upload
    // can't be used as-is" states (wrong file type / too big / model doesn't
    // accept this modality), same 400 posture as ProviderValidationException
    // above, not server bugs.
    @ExceptionHandler(UnsupportedAttachmentTypeException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedAttachmentType(
            UnsupportedAttachmentTypeException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AttachmentTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleAttachmentTooLarge(
            AttachmentTooLargeException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // Multimodal playground contract (inline chat attachments) - three
    // DISTINCT statuses by design, per the shared frontend contract: 413 for
    // over-count/over-size, 415 for "this model can't read that kind of
    // file" (message names the model and what it does support), 400 for
    // structurally-broken input (bad base64 / missing fields). Deliberately
    // separate from the Stage-4 multipart pair above, whose long-standing
    // 400 posture the existing upload UI already depends on.
    @ExceptionHandler(AttachmentPayloadTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleAttachmentPayloadTooLarge(
            AttachmentPayloadTooLargeException e) {
        return errorResponse(e, HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler(AttachmentModalityNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleAttachmentModalityNotSupported(
            AttachmentModalityNotSupportedException e) {
        return errorResponse(e, HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler(MalformedAttachmentException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedAttachment(
            MalformedAttachmentException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // Closes the provider/model-mismatch crash (e.g. providerName=GEMINI
    // saved alongside a leftover Anthropic model string) that previously
    // only surfaced as an opaque upstream 404 at actual chat time - see
    // AgentModelNotSupportedException's own javadoc for the full story.
    @ExceptionHandler(AgentModelNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleAgentModelNotSupported(
            AgentModelNotSupportedException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // #815 Governance follow-up (Fail-safe: platform + per-tenant kill
    // switch) - the omnichannel-webhook counterpart of TenantContextFilter's
    // own 403 for the identical rejection on the JWT path (see
    // TenantUnavailableException's javadoc). Deliberately not logged here at
    // warn/error: an operator who just flipped the kill switch will see a
    // burst of these on every inbound channel message for as long as it's
    // on, by design - that's expected noise, not an incident.
    @ExceptionHandler(TenantUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleTenantUnavailable(
            TenantUnavailableException e) {
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    // #848 (LLM gateway seam) - a call rejected BEFORE it reached the LLM
    // provider (monthly budget cap or per-minute rate limit) - 429, distinct
    // from UpstreamProviderException's 502 (which means the call DID reach
    // the provider and failed there).
    @ExceptionHandler(LlmGatewayLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleLlmGatewayLimitExceeded(
            LlmGatewayLimitExceededException e) {
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(InvalidKnowledgeUploadException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidUpload(
            InvalidKnowledgeUploadException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // 400, not 502/503: nothing failed. The operator simply never set
    // TOOL_SANDBOX_ENABLED=true on this deployment, and the message says so.
    @ExceptionHandler(CustomCodeToolsDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleCustomCodeToolsDisabled(
            CustomCodeToolsDisabledException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ApprovalNotAuthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleApprovalNotAuthorized(
            ApprovalNotAuthorizedException e) {
        // 403: the request is real and still pending, but this Approval
        // node was configured "adminOnly" and the caller isn't a
        // tenant-admin - a permissions problem, not a not-found/conflict one.
        log.warn("Rejected approval decision: {}", e.getMessage());
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(ChannelConfigurationException.class)
    public ResponseEntity<Map<String, Object>> handleChannelConfiguration(
            ChannelConfigurationException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(com.agenticai.core.omnichannel.whatsapp.WhatsAppSendBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleWhatsAppSendBlocked(
            com.agenticai.core.omnichannel.whatsapp.WhatsAppSendBlockedException e) {
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DeviceException.class)
    public ResponseEntity<Map<String, Object>> handleDeviceException(DeviceException e) {
        // One exception type covers unknown/expired pairing codes, unknown
        // device tokens, revoked devices, and unknown command ids - all of
        // them client-actionable "this credential/id isn't valid" states,
        // not server bugs, so 400 across the board rather than a mix of
        // 400/401/404 that a device client would have to special-case.
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(QuickTaskNotReadyException.class)
    public ResponseEntity<Map<String, Object>> handleQuickTaskNotReady(
            QuickTaskNotReadyException e) {
        // Same class of client-actionable "one prerequisite isn't set up
        // yet" state as TwilioNotConfiguredException (see handleStatusMapped) - 400, not
        // a server bug.
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 P2 (paid top-up / overage) -
    // a per-USER dollar budget was exhausted. Distinct from the plain
    // TenantLimitExceededException just below (this is a strict subclass, so
    // this MORE-SPECIFIC handler wins by Spring's @ExceptionHandler resolution,
    // regardless of declaration order): 402 Payment Required, not 403, because
    // this ceiling is PAYABLE - the structured body carries the modality, the
    // effective cap, spend so far, reset time, and where to top up so the UI
    // can turn a blocked turn into a "top up to continue" flow rather than a
    // dead end. Not logged/recorded as an issue: hitting a budget is expected
    // operation, not a platform fault.
    @ExceptionHandler(PerUserBudgetExhaustedException.class)
    public ResponseEntity<Map<String, Object>> handlePerUserBudgetExhausted(
            PerUserBudgetExhaustedException e) {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("modality", e.getModality());
        budget.put("capUsd", e.getCapUsd());
        budget.put("spentUsd", e.getSpentUsd());
        budget.put("resetSeconds", e.getResetSeconds());

        Map<String, Object> topUp = new LinkedHashMap<>();
        topUp.put("available", true);
        topUp.put("optionsUrl", "/api/budget/top-up/options");
        topUp.put("purchaseUrl", "/api/budget/top-up");
        topUp.put("confirmUrl", "/api/budget/top-up/confirm");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("budget", budget);
        body.put("topUp", topUp);
        return errors.build(
                HttpStatus.PAYMENT_REQUIRED,
                e.getMessage(),
                ErrorResponseFactory.slugFor(e, HttpStatus.PAYMENT_REQUIRED),
                body);
    }

    // Monthly hosted-pool spend cap. 402 like the per-user budget above (the more specific
    // subclass handler wins); flat contract body the web and admin UIs key on:
    // error=spend_cap_reached plus the cap, the month-to-date spend and the reset time.
    @ExceptionHandler(SpendCapReachedException.class)
    public ResponseEntity<Map<String, Object>> handleSpendCapReached(SpendCapReachedException e) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("error", SpendCapReachedException.ERROR_CODE);
        extensions.put("message", e.getMessage());
        extensions.put("capUsd", e.getCapUsd());
        extensions.put("monthToDateUsd", e.getMonthToDateUsd());
        extensions.put("resetsAt", e.getResetsAt().toString());
        return errors.build(
                HttpStatus.PAYMENT_REQUIRED,
                e.getMessage(),
                ErrorResponseFactory.slugFor(e, HttpStatus.PAYMENT_REQUIRED),
                extensions);
    }

    // Read-only "view as user": a side-effecting service refused to act.
    @ExceptionHandler(ImpersonationReadOnlyException.class)
    public ResponseEntity<Map<String, Object>> handleImpersonationReadOnly(
            ImpersonationReadOnlyException e) {
        String message =
                "View-as is read-only: this action is not available while viewing as a user.";
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("error", ImpersonationReadOnlyException.ERROR_CODE);
        extensions.put("message", message);
        return errors.build(
                HttpStatus.FORBIDDEN,
                message,
                ErrorResponseFactory.slugFor(e, HttpStatus.FORBIDDEN),
                extensions);
    }

    @ExceptionHandler(TenantLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTenantLimitExceeded(
            TenantLimitExceededException e) {
        // 403: the request is well-formed and the caller is authorized to
        // manage users/agents in general - it's specifically this plan's
        // seat/agent ceiling that says no, a client-actionable "upgrade your
        // plan" state rather than a permissions or server-bug case.
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    // A2-PR3 (narrowed scope) - FREE-tier edit-lock, thrown by
    // AgentService#assertEditable/WorkflowService#assertEditable via
    // EditLockPolicy. Distinct from the plain TenantLimitExceededException
    // just above (this is a strict subclass, so this MORE-SPECIFIC handler
    // wins by Spring's @ExceptionHandler resolution, regardless of
    // declaration order): 423 Locked, not 403, because the resource itself -
    // not the caller's permission to act on resources of this kind in
    // general - is what's temporarily read-only, and because 423 carries a
    // structured body (lockedReason/editableUntil) a client can bind to
    // directly instead of parsing the English message to tell this apart
    // from an unrelated seat/agent-count 403. Unrelated
    // TenantLimitExceededException causes (seat ceilings, agent-count
    // ceilings, etc.) are NOT affected - they still hit the handler above
    // and keep returning 403.
    @ExceptionHandler(EditLockedException.class)
    public ResponseEntity<Map<String, Object>> handleEditLocked(EditLockedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lockedReason", e.getLockedReason());
        body.put(
                "editableUntil",
                e.getEditableUntil() == null ? null : e.getEditableUntil().toString());
        return errors.build(
                HttpStatus.LOCKED,
                e.getMessage(),
                ErrorResponseFactory.slugFor(e, HttpStatus.LOCKED),
                body);
    }

    // Live Vision - the shared vision-session contract's status
    // mapping (see VisionSessionService's javadoc for the check order).
    // 429: a per-user daily allowance (frames or watch-minutes) ran out -
    // same "come back later" semantics as SignupRateLimitExceededException,
    // with the message carrying the live configured cap.
    @ExceptionHandler(VisionQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleVisionQuotaExceeded(
            VisionQuotaExceededException e) {
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    // 403: the plan lacks plan_limits.live_vision_allowed - same
    // client-actionable "upgrade your plan" posture as
    // TenantLimitExceededException above. `code` is what lets a client
    // reliably tell this 403 apart from VisionApprovalRequiredException's
    // identical status below, without parsing either exception's English
    // message - see that handler's comment for the full picture, including
    // the interim frontend-side signal CameraPanel.tsx actually reads today.
    @ExceptionHandler(VisionNotAllowedForPlanException.class)
    public ResponseEntity<Map<String, Object>> handleVisionNotAllowedForPlan(
            VisionNotAllowedForPlanException e) {
        return errorResponseWithCode(HttpStatus.FORBIDDEN, e.getMessage(), "VISION_PLAN_LOCKED");
    }

    // 403: the tenant's approval policy still requires human approval for
    // camera.capture.start - actionable by a tenant admin (loosen the
    // override), not by upgrading, so the message differs but the status
    // matches the "you specifically may not do this yet" family.
    // `code: VISION_APPROVAL_REQUIRED` is the stable, structured signal a
    // client should key on to distinguish this from
    // VisionNotAllowedForPlanException's identical 403 status (both land in
    // CameraPanel's watchErrorMessage()). As of this change,
    // web/app/lib/api.ts's toJsonQuietOn helper (out of scope for this fix)
    // only threads `error`/`status` through to callers for this endpoint's
    // quiet-error path and drops any other body field, `code` included - so
    // VisionSessionService additionally stamps a matching prefix onto this
    // exception's own message as the signal CameraPanel actually reads
    // right now. Once api.ts forwards `code` too, CameraPanel can switch to
    // reading it directly and that message prefix can be dropped.
    @ExceptionHandler(VisionApprovalRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleVisionApprovalRequired(
            VisionApprovalRequiredException e) {
        return errorResponseWithCode(
                HttpStatus.FORBIDDEN, e.getMessage(), "VISION_APPROVAL_REQUIRED");
    }

    // 403: Agent.biometricConsentEnabled is false - actionable by a tenant
    // admin (PATCH /api/agents/{id}/biometric-consent), same posture as
    // VisionNotAllowedForPlanException above.
    @ExceptionHandler(BiometricConsentNotEnabledException.class)
    public ResponseEntity<Map<String, Object>> handleBiometricConsentNotEnabled(
            BiometricConsentNotEnabledException e) {
        return errorResponse(e, HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(SignupRateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleSignupRateLimit(
            SignupRateLimitExceededException e) {
        return errorResponse(e, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(TenantUpgradeException.class)
    public ResponseEntity<Map<String, Object>> handleTenantUpgrade(TenantUpgradeException e) {
        // 400: a client mistake (targeting FREE, or the plan already active) - not a server bug.
        return errorResponse(e, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(GuardrailViolationException.class)
    public ResponseEntity<Map<String, Object>> handleGuardrailViolation(
            GuardrailViolationException e) {
        // 422: the request itself was fine, but a tool's output tripped a
        // content-safety guardrail and was withheld - distinct from a 4xx
        // client-input problem or a 5xx server failure. Logged for the same
        // reason TwilioWebhookSignatureException's rejections are - a stream
        // of these is worth an operator's attention.
        log.warn("Guardrail violation: {}", e.getMessage());
        // Diagnostics build-out (2026-08): previously recorded NO PlatformIssue
        // at all - now a per-TOOL, WARNING-severity row via DiagnosticService
        // (IssueSource.GUARDRAIL_FALSE_POSITIVE, sourceId = tool name), plus
        // the async triage hand-off; same shape and reasoning as
        // handleUpstreamProvider's recording just above.
        if (diagnosticService != null) {
            com.agenticai.core.audit.PlatformIssue issue =
                    diagnosticService.recordGuardrailViolation(e);
            if (issue != null && aiDiagnosticAnalysisService != null) {
                // Same "unknown-tool" normalization DiagnosticService itself applies.
                String toolName =
                        e.getToolName() == null || e.getToolName().isBlank()
                                ? "unknown-tool"
                                : e.getToolName();
                aiDiagnosticAnalysisService.analyzeAsync(
                        null,
                        issue.getId(),
                        IssueSource.GUARDRAIL_FALSE_POSITIVE,
                        toolName,
                        e.getMessage(),
                        null);
            }
        }
        return errorResponse(e, HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
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
}
