package com.agenticai.core.common;

import com.agenticai.core.admin.AdminUserNotFoundException;
import com.agenticai.core.agents.core.AgentInUseException;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.PromptVersionNotFoundException;
import com.agenticai.core.agents.tools.code.CustomCodeToolNotFoundException;
import com.agenticai.core.agents.tools.openapi.OpenApiToolNotFoundException;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSourceNotFoundException;
import com.agenticai.core.agents.tools.script.UserToolScriptNotFoundException;
import com.agenticai.core.agents.vision.VisionSessionNotFoundException;
import com.agenticai.core.approval.ApprovalRequestAlreadyDecidedException;
import com.agenticai.core.approval.ApprovalRequestNotFoundException;
import com.agenticai.core.audit.IssueNotFoundException;
import com.agenticai.core.common.quicktask.QuickTaskNotFoundException;
import com.agenticai.core.eventbus.webhook.WebhookSubscriptionNotFoundException;
import com.agenticai.core.knowledge.KnowledgeCollectionNotEmptyException;
import com.agenticai.core.knowledge.KnowledgeCollectionNotFoundException;
import com.agenticai.core.knowledge.KnowledgeDocumentNotFoundException;
import com.agenticai.core.knowledge.KnowledgeDuplicateDocumentException;
import com.agenticai.core.knowledge.KnowledgeIngestionJobNotFoundException;
import com.agenticai.core.knowledge.connector.KnowledgeConnectorNotFoundException;
import com.agenticai.core.mcp.McpServerRegistrationNotFoundException;
import com.agenticai.core.mcpserver.McpServerAccessGrantNotFoundException;
import com.agenticai.core.omnichannel.session.ChannelSessionNotFoundException;
import com.agenticai.core.platform.PlatformTenantNotFoundException;
import com.agenticai.core.providers.PlatformHostedLlmProviderNotFoundException;
import com.agenticai.core.rl.api.RlArmConflictException;
import com.agenticai.core.rl.api.RlArmNotFoundException;
import com.agenticai.core.secrets.SecretNotFoundException;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.templates.IllegalTemplateTransitionException;
import com.agenticai.core.templates.TemplateNotFoundException;
import com.agenticai.core.templates.TemplateRatingForbiddenException;
import com.agenticai.core.tenancy.DuplicateTenantException;
import com.agenticai.core.tenancy.TenantAlreadyErasedException;
import com.agenticai.core.tenancy.TenantNotFoundByIdException;
import com.agenticai.core.workflow.WorkflowNotFoundException;
import com.agenticai.core.workflow.WorkflowRunNotFoundException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Platform domain exceptions about a resource's existence or lifecycle state: 404 for an id that
 * does not exist (or, deliberately, one the caller may not learn exists - see the anti-enumeration
 * notes on individual handlers) and 409 for a well-formed request the resource's current state
 * refuses. Second advice of the ordered chain; see {@link FallbackExceptionHandler} for the
 * ordering rules.
 *
 * <p>Every type here extends {@code RuntimeException} directly and none is extended by another
 * handled type, so no handler in this class can shadow a more specific one in a later advice.
 */
@RestControllerAdvice
@Order(ResourceStateExceptionHandler.ORDER)
public class ResourceStateExceptionHandler {

    /** Resolution order of this advice within the chain (lower runs first). */
    static final int ORDER = 200;

    private final ExceptionHandlerSupport support;

    public ResourceStateExceptionHandler(ErrorResponseFactory errors) {
        this.support = new ExceptionHandlerSupport(errors, null);
    }

    @ExceptionHandler(IssueNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleIssueNotFound(IssueNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(WebhookSubscriptionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWebhookSubscriptionNotFound(
            WebhookSubscriptionNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PlatformHostedLlmProviderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleHostedLlmProviderNotFound(
            PlatformHostedLlmProviderNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PlatformTenantNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePlatformTenantNotFound(
            PlatformTenantNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AgentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAgentNotFound(AgentNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    // core/sharing (cross-user resource sharing foundation) - deliberately
    // 404, not 403: see ResourceAccessDeniedException's own javadoc for the
    // anti-enumeration reasoning, same posture as AgentNotFoundException
    // just above for a tenant-isolation violation.
    @ExceptionHandler(ResourceAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleResourceAccessDenied(
            ResourceAccessDeniedException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    // core/templates (community template gallery) - a template the caller
    // isn't entitled to see (a non-approved template requested by a non-owner)
    // or one that genuinely doesn't exist. 404, not 403: same anti-enumeration
    // posture as ResourceAccessDeniedException above - a request for another
    // member's draft, the moderation queue, or a made-up id are all
    // indistinguishable. See TemplateNotFoundException's own javadoc.
    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateNotFound(TemplateNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    // core/templates - an illegal lifecycle move (e.g. approving something that
    // was never submitted). 409: the template exists and the caller could see
    // it, but its current state conflicts with the requested transition. Safe
    // to be specific here - the caller already passed the visibility check.
    @ExceptionHandler(IllegalTemplateTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalTemplateTransition(
            IllegalTemplateTransitionException e) {
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    // core/templates - a member rating their own marketplace template. 409:
    // only ever thrown for an APPROVED template, which the caller can already
    // see publicly, so the refusal can say exactly why (no enumeration risk).
    @ExceptionHandler(TemplateRatingForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateRatingForbidden(
            TemplateRatingForbiddenException e) {
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    // Master-Roadmap-2026.md item #5 (prompt-template versioning) - revert
    // target no longer exists (bad id, or belongs to a different agent).
    @ExceptionHandler(PromptVersionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePromptVersionNotFound(
            PromptVersionNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    // Unlimited-plan agent lifecycle (edit-model/delete) - a hard DELETE that
    // hit the DB-level FK guard on channel_session/channel_default_agent
    // (see AgentService#delete's javadoc). 409: the request is well-formed,
    // the agent exists, but the current state (still bound to a channel)
    // conflicts with deleting it.
    @ExceptionHandler(AgentInUseException.class)
    public ResponseEntity<Map<String, Object>> handleAgentInUse(AgentInUseException e) {
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RlArmConflictException.class)
    public ResponseEntity<Map<String, Object>> handleRlArmConflict(RlArmConflictException e) {
        // 409, same shape as AgentInUseException above: well-formed request,
        // but an arm with that key already exists on the policy.
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RlArmNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRlArmNotFound(RlArmNotFoundException e) {
        // 404 - and, because RL tables are schema-per-tenant, this is also the
        // answer another tenant's arm id gets. Indistinguishable from absent,
        // deliberately.
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AdminUserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAdminUserNotFound(
            AdminUserNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(McpServerRegistrationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMcpRegistrationNotFound(
            McpServerRegistrationNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(McpServerAccessGrantNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMcpServerAccessGrantNotFound(
            McpServerAccessGrantNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(UserToolScriptNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserToolScriptNotFound(
            UserToolScriptNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    // "Import from OpenAPI" (OpenApiToolSourceController) - same 404 shape as
    // the two sibling tenant-tool features above. Parse/SSRF/credential
    // refusals from that feature are OpenApiToolException, which extends
    // IllegalArgumentException on purpose and lands in the generic 400 below.
    @ExceptionHandler(OpenApiToolSourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOpenApiToolSourceNotFound(
            OpenApiToolSourceNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(OpenApiToolNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOpenApiToolNotFound(
            OpenApiToolNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(KnowledgeCollectionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledgeCollectionNotFound(
            KnowledgeCollectionNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(KnowledgeCollectionNotEmptyException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledgeCollectionNotEmpty(
            KnowledgeCollectionNotEmptyException e) {
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SecretNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleSecretNotFound(SecretNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(KnowledgeDuplicateDocumentException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledgeDuplicateDocument(
            KnowledgeDuplicateDocumentException e) {
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(KnowledgeIngestionJobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledgeIngestionJobNotFound(
            KnowledgeIngestionJobNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    // Stage 5 (KB multi-modal storage) - previously KnowledgeDocumentService's
    // withDocument()/getEntity() only ever threw this internally (from
    // KnowledgeIngestionService/KnowledgeIngestionJobService, never fed a
    // caller-controlled id), so it had no handler here and fell through to
    // the generic Exception.class -> 500 catch-all below. KnowledgeController
    // #downloadOriginalFile is the first endpoint that resolves a raw,
    // caller-supplied documentId directly, so a nonexistent one now needs to
    // surface as a real 404, not an unhandled-exception 500.
    @ExceptionHandler(KnowledgeDocumentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledgeDocumentNotFound(
            KnowledgeDocumentNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(KnowledgeConnectorNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledgeConnectorNotFound(
            KnowledgeConnectorNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CustomCodeToolNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCustomCodeToolNotFound(
            CustomCodeToolNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ApprovalRequestNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleApprovalRequestNotFound(
            ApprovalRequestNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ApprovalRequestAlreadyDecidedException.class)
    public ResponseEntity<Map<String, Object>> handleApprovalAlreadyDecided(
            ApprovalRequestAlreadyDecidedException e) {
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ChannelSessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleChannelSessionNotFound(
            ChannelSessionNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(QuickTaskNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleQuickTaskNotFound(
            QuickTaskNotFoundException e) {
        // #606 follow-up ("generate an ID of a task... any point of time...
        // view it") - GET /api/quick-task/{taskId} for an id that doesn't
        // exist, or belongs to a different user (ownership-scoped lookup,
        // see QuickTaskNotFoundException's javadoc) - a real 404, not a
        // client-configuration problem like QuickTaskNotReadyException above.
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DuplicateTenantException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateTenant(DuplicateTenantException e) {
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    // Platform-admin "delete tenant completely" control (2026-08-07). Note:
    // this handler wiring was briefly reverted as unrelated scope creep for a
    // different task, then restored - the controller/exception classes these
    // map already exist in this shared working tree regardless of this
    // handler's presence (see docs/AGENT-COORDINATION-2026-07-18.md /
    // Maintainer note: this is a DIFFERENT session's in-progress feature, flagged
    // rather than owned here), so mapping them to correct HTTP statuses is
    // strictly safer than leaving them fall through to a generic 500 - it
    // does not add new capability, only correct status codes for exceptions
    // that are thrown either way.
    @ExceptionHandler(TenantNotFoundByIdException.class)
    public ResponseEntity<Map<String, Object>> handleTenantNotFoundById(
            TenantNotFoundByIdException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TenantAlreadyErasedException.class)
    public ResponseEntity<Map<String, Object>> handleTenantAlreadyErased(
            TenantAlreadyErasedException e) {
        return errorResponse(e, HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(VisionSessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleVisionSessionNotFound(
            VisionSessionNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowNotFound(WorkflowNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(WorkflowRunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWorkflowRunNotFound(
            WorkflowRunNotFoundException e) {
        return errorResponse(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            Throwable e, HttpStatus requested, String message) {
        return support.errorResponse(e, requested, message);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus requested, String message) {
        return support.errorResponse(requested, message);
    }
}
