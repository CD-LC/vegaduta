package com.agenticai.core.agents.version;

import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.agents.dto.AgentResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for agent configuration versions.
 *
 * <p>Thin by design, the same way {@code AgentJobController} is: every access
 * check, every gate and every state decision lives in {@link AgentVersionService},
 * so any non-HTTP caller added later gets identical enforcement.
 *
 * <p>A SEPARATE controller from {@code AgentController} rather than four more
 * methods on it, mirroring {@code AgentJobController}/{@code AgentGuardrailController}
 * /{@code AgentShareController}: this is a self-contained sub-resource with its own
 * service and its own package, and {@code AgentController} is already ~700 lines
 * covering chat, attachments, tools, memory and every per-field PATCH.
 *
 * <p>{@code /api/agents/{agentId}/versions} sits alongside the existing
 * {@code /api/agents/{agentId}/prompt/versions}, which is untouched and stays the
 * prompt-only history. The two are additive, not alternatives - see migration
 * V212's header for why prompt history was not migrated into this table.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/versions")
public class AgentVersionController {

    private final AgentVersionService versionService;

    public AgentVersionController(AgentVersionService versionService) {
        this.versionService = versionService;
    }

    /**
     * Every stored configuration of this agent, newest first.
     *
     * <p>Read-only, so - like {@code AgentController#listPromptVersions} - it is
     * deliberately NOT gated by the FREE-tier edit-lock: a locked agent's
     * configuration history stays viewable, matching the "chat, run, and delete
     * are still available" posture the edit-lock message itself promises for
     * read-only surfaces.
     */
    @GetMapping
    public ResponseEntity<List<AgentVersionResponse>> list(@PathVariable UUID agentId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            versionService.list(agentId),
            limit, offset, 50, 500);
    }

    /** One stored configuration, including the full decoded bundle. Same read-only gating as {@link #list}. */
    @GetMapping("/{versionId}")
    public AgentVersionResponse get(@PathVariable UUID agentId, @PathVariable UUID versionId) {
        return versionService.get(agentId, versionId);
    }

    /**
     * C1 publish - snapshots the agent's CURRENT draft as a new immutable
     * version, with the tenant's change note attached. Byte-identical
     * re-publishes are deduped server-side ({@code createdNew=false} and no
     * new row), so a double-click or retried request never pads history.
     */
    @PostMapping
    public AgentPublishResponse publish(
        @PathVariable UUID agentId, @Valid @RequestBody(required = false) AgentVersionPublishRequest request
    ) {
        return versionService.publish(agentId, request == null ? null : request.changeNote());
    }

    /**
     * C1 promote - {@code {"label":"PRODUCTION"}} (or {@code STAGING}) pointed
     * at this version, through the same gate/audit path as {@link #setLabel}.
     * Exists alongside the older PUT /label as the action-shaped alias the
     * versioning UX uses.
     */
    @PostMapping("/{versionId}/promote")
    public AgentVersionResponse promote(
        @PathVariable UUID agentId,
        @PathVariable UUID versionId,
        @Valid @RequestBody AgentVersionPromoteRequest request
    ) {
        return versionService.setLabel(
            agentId, versionId, request.label(),
            Boolean.TRUE.equals(request.force()), request.reason());
    }

    /**
     * C1 rollback - moves PRODUCTION back to the previous version. A label
     * move, never a new snapshot and never a draft write. 409 when the
     * agent has no earlier version to point at.
     */
    @PostMapping("/rollback")
    public AgentVersionResponse rollback(@PathVariable UUID agentId) {
        return versionService.rollback(agentId);
    }

    /**
     * C1 diff - field-level bundle diff between two stored versions,
     * addressed by ordinal ({@code ?from=3&to=7}). This is what the
     * promote/rollback confirm dialog renders.
     */
    @GetMapping("/diff")
    public Map<String, Object> diff(
        @PathVariable UUID agentId, @RequestParam int from, @RequestParam int to
    ) {
        return versionService.diff(agentId, from, to);
    }

    /**
     * Makes a past configuration current, and returns the agent as it now stands.
     *
     * <p>POST, not PUT: this is not an idempotent write of a resource at this URL.
     * Each call appends a further version row for the restored state (which is
     * what makes a restore undoable), so calling it twice is meaningfully
     * different from calling it once - the second call is a no-op on the AGENT but
     * still a distinct event. Same shape and same edit-lock gating as
     * {@code POST /api/agents/{agentId}/prompt/versions/{versionId}/revert}.
     */
    @PostMapping("/{versionId}/restore")
    public AgentResponse restore(@PathVariable UUID agentId, @PathVariable UUID versionId) {
        return versionService.restore(agentId, versionId);
    }

    /**
     * Points {@code staging} or {@code production} at this version, or clears a
     * label from it with a null {@code label}.
     *
     * <p>PUT, not PATCH: the body fully replaces this version's label, and sending
     * the same body twice leaves the same state - genuinely idempotent, unlike
     * {@link #restore}. Mutating, so it takes EDITOR plus the edit-lock (see
     * {@link AgentVersionService#setLabel}).
     *
     * <p>PRODUCTION assignment additionally passes through the eval gate
     * (V260): a tenant that configured a gate gets a 412 + gate report unless
     * a qualifying eval run passed for this exact configuration - or the
     * caller sends {@code force=true} + {@code reason} and holds MANAGE_AGENTS,
     * which is audited as an override.
     */
    @PutMapping("/{versionId}/label")
    public AgentVersionResponse setLabel(
        @PathVariable UUID agentId,
        @PathVariable UUID versionId,
        @Valid @RequestBody AgentVersionLabelUpdateRequest request
    ) {
        return versionService.setLabel(
            agentId, versionId, request.label(),
            Boolean.TRUE.equals(request.force()), request.reason());
    }

    /**
     * Controller-local 404 mapping, resolved BEFORE the app-wide
     * {@code GlobalExceptionHandler} - see {@link AgentVersionNotFoundException}'s
     * javadoc for why that advice's {@code @ExceptionHandler(Exception.class)}
     * catch-all makes this necessary rather than merely tidy, and
     * {@code GuardrailRuleController} for the same pattern already in use.
     *
     * <p>The body shape matches {@code GlobalExceptionHandler#errorResponse}
     * exactly ({@code timestamp}/{@code status}/{@code error}) so clients see one
     * error contract across the API, not two.
     */
    @ExceptionHandler(AgentVersionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleVersionNotFound(AgentVersionNotFoundException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.NOT_FOUND.value());
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 412 for a promote the eval gate refused. The body IS the gate report
     * ({@code gate.*}) - which run, which verdict, why it did not qualify - so
     * the console can render "what do I need to fix" straight off the failure
     * without a second call.
     */
    @ExceptionHandler(PromotionGateBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleGateBlocked(PromotionGateBlockedException e) {
        AgentPromotionGate.GateDecision decision = e.getDecision();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.PRECONDITION_FAILED.value());
        body.put("error", e.getMessage());
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("reason", decision.reason());
        gate.put("qualifyingRunId", decision.qualifyingRunId());
        gate.put("verdict", decision.verdict());
        gate.put("overallScore", decision.overallScore());
        gate.put("runFinishedAt", decision.runFinishedAt());
        body.put("gate", gate);
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(body);
    }

    /** 409 for a rollback with no earlier version to point production at. */
    @ExceptionHandler(VersionRollbackException.class)
    public ResponseEntity<Map<String, Object>> handleRollbackConflict(VersionRollbackException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 403 for a force-override attempt without the MANAGE_AGENTS scope. */
    @ExceptionHandler(PromotionGateForceDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForceDenied(PromotionGateForceDeniedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
