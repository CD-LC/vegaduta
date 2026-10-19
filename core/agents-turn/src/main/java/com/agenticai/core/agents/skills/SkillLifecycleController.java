package com.agenticai.core.agents.skills;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Review actions on a custom skill: approve / reject / archive a draft, bind it to agents, and read
 * its version history. The list, detail, import, export, edit and delete halves of the same
 * contract live on {@link SkillController}. Every write is workspace-admin only for the reason
 * given there: a skill's description is injected into the system prompt of every agent it reaches.
 */
@RestController
public class SkillLifecycleController {

    /** Each requested agent costs an access check; the cap keeps one request bounded. */
    static final int MAX_AGENTS_PER_REQUEST = 500;

    private final SkillLifecycleService lifecycle;
    private final AgentService agentService;

    public SkillLifecycleController(SkillLifecycleService lifecycle, AgentService agentService) {
        this.lifecycle = lifecycle;
        this.agentService = agentService;
    }

    public record RejectRequest(String note) {}

    public record AgentsRequest(List<UUID> agentIds) {}

    /**
     * {@code agentIds}: every agent whose allow-list now names the skill. {@code unrestrictedAgentIds}:
     * requested agents that have no allow-list and therefore already see every enabled skill, so
     * nothing was changed for them.
     */
    public record AgentsResponse(List<UUID> agentIds, List<UUID> unrestrictedAgentIds) {}

    public record VersionSummary(
            int version, String note, String createdBy, Instant createdAt, String contentSha256) {
        static VersionSummary from(SkillVersion v) {
            return new VersionSummary(
                    v.getVersion(), v.getNote(), v.getCreatedBy(), v.getCreatedAt(), v.getContentSha256());
        }
    }

    // ------------------------------------------------------------------ transitions

    @PostMapping("/api/skills/{id}/approve")
    public SkillController.SkillSummaryResponse approve(
            @PathVariable UUID id, Authentication authentication) {
        SkillController.requireWorkspaceAdmin(authentication);
        return summary(lifecycle.approve(id));
    }

    @PostMapping("/api/skills/{id}/reject")
    public SkillController.SkillSummaryResponse reject(
            @PathVariable UUID id,
            @RequestBody(required = false) RejectRequest request,
            Authentication authentication) {
        SkillController.requireWorkspaceAdmin(authentication);
        return summary(lifecycle.reject(id, request == null ? null : request.note()));
    }

    @PostMapping("/api/skills/{id}/archive")
    public SkillController.SkillSummaryResponse archive(
            @PathVariable UUID id, Authentication authentication) {
        SkillController.requireWorkspaceAdmin(authentication);
        return summary(lifecycle.archive(id));
    }

    @PostMapping("/api/skills/{id}/agents")
    public AgentsResponse setAgents(
            @PathVariable UUID id,
            @RequestBody AgentsRequest request,
            Authentication authentication) {
        SkillController.requireWorkspaceAdmin(authentication);
        List<UUID> requested = request == null || request.agentIds() == null ? List.of() : request.agentIds();
        if (requested.size() > MAX_AGENTS_PER_REQUEST) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "At most " + MAX_AGENTS_PER_REQUEST + " agents can be bound in one request.");
        }
        for (UUID agentId : requested) {
            // The same edit lock as PUT /api/agents/{id}/skills.
            agentService.assertEditable(agentService.requireAccess(agentId, PermissionLevel.EDITOR));
        }
        SkillLifecycleService.AgentBinding binding = lifecycle.setAgents(id, requested);
        return new AgentsResponse(binding.agentIds(), binding.unrestricted());
    }

    // ------------------------------------------------------------------ history

    @GetMapping("/api/skills/{id}/versions")
    public ResponseEntity<List<VersionSummary>> versions(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        return ListPaging.window(
                lifecycle.versions(id).stream().map(VersionSummary::from).toList(),
                limit,
                offset,
                20,
                50);
    }

    private static SkillController.SkillSummaryResponse summary(
            SkillLifecycleService.Reviewed reviewed) {
        return SkillController.SkillSummaryResponse.from(reviewed.metadata(), reviewed.view());
    }

    @ExceptionHandler(SkillNotFoundException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(SkillNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SkillController.SkillWriteForbiddenException.class)
    ResponseEntity<Map<String, Object>> handleForbidden(
            SkillController.SkillWriteForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
