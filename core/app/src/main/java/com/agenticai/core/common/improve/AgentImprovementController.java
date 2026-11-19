package com.agenticai.core.common.improve;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Improvement-suggestion surface (Tier-3): the admin-facing queue of LLM-distilled fixes for an
 * agent's recurring production failures.
 *
 * <ul>
 *   <li>{@code GET /api/agents/{agentId}/improvements} - list recent suggestions (all statuses).
 *   <li>{@code POST /api/agents/{agentId}/improvements/generate} - scan the agent's OPEN recurring
 *       issue clusters and distil new PENDING suggestions (bounded, deduped - see {@code
 *       AgentImprovementService}).
 *   <li>{@code POST /api/agents/{agentId}/improvements/{id}/accept} - apply the suggestion (prompt
 *       replace or procedure create+attach).
 *   <li>{@code POST /api/agents/{agentId}/improvements/{id}/dismiss} - park it.
 *   <li>{@code GET /api/agents/{agentId}/improvements/health} - this agent's live health/confidence
 *       snapshot, as last computed by {@link AgentSupervisorSweep} (or by a manual {@code generate}
 *       call, which recomputes it too) - {@code null} until the first sweep tick or manual generate
 *       has run for this agent.
 * </ul>
 *
 * <p>Sits under the same {@code /api/agents} umbrella as the rest of the agent surface, inheriting
 * the same SecurityConfig matchers.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/improvements")
public class AgentImprovementController {

    private final AgentImprovementService improvementService;
    private final AgentHealthScoreService healthScoreService;
    private final AgentService agentService;

    public AgentImprovementController(
            AgentImprovementService improvementService,
            AgentHealthScoreService healthScoreService,
            AgentService agentService) {
        this.improvementService = improvementService;
        this.healthScoreService = healthScoreService;
        this.agentService = agentService;
    }

    @GetMapping
    public ResponseEntity<List<ImprovementDtos.SuggestionResponse>> list(@PathVariable UUID agentId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return ListPaging.window(
            improvementService.listSuggestionsForAgent(agentId),
            limit, offset, 50, 500);
    }

    @PostMapping("/generate")
    public List<ImprovementDtos.SuggestionResponse> generate(@PathVariable UUID agentId) {
        // Before any work: generating spends LLM budget on this agent.
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        List<ImprovementDtos.SuggestionResponse> created = improvementService.generateSuggestionsForAgent(agentId);
        // Keep the manual "scan now" button and the scheduled sweep on the same
        // contract: a generate call also refreshes the health snapshot, so an
        // admin who scans by hand sees an up-to-date score without waiting for
        // the next AgentSupervisorSweep tick.
        healthScoreService.recompute(agentId, currentTenantId());
        return created;
    }

    @GetMapping("/health")
    public ResponseEntity<ImprovementDtos.HealthScoreResponse> health(@PathVariable UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return healthScoreService
                .getViewForAgent(agentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{suggestionId}/accept")
    public ImprovementDtos.SuggestionResponse accept(
            @PathVariable UUID agentId,
            @PathVariable UUID suggestionId,
            Authentication authentication) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        return improvementService.acceptForAgent(agentId, suggestionId, actor(authentication));
    }

    @PostMapping("/{suggestionId}/dismiss")
    public ImprovementDtos.SuggestionResponse dismiss(
            @PathVariable UUID agentId,
            @PathVariable UUID suggestionId,
            Authentication authentication) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        return improvementService.dismissForAgent(agentId, suggestionId, actor(authentication));
    }

    private static String actor(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }

    private static String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }

    @ExceptionHandler(ImprovementNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ImprovementNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
