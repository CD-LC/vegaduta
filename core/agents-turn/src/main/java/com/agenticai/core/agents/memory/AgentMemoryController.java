package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What an agent has remembered about a given subject (the {@code subjectKey} {@link
 * AgentController#chat} computes: a Keycloak principal name, or a WhatsApp phone number). Empty
 * list (not an error) when the "knowledge" profile is off, same graceful degradation as every other
 * {@code ObjectProvider}-backed optional feature.
 *
 * <p>Gated at VIEWER, same bar as reading the agent's config: what an agent has remembered is
 * internal detail, not something a RUNNER-only grantee (execute/chat only) should browse. Memories
 * are about people (customers, colleagues), so VIEWER covers only the caller's own memories:
 * reading or deleting what the agent remembers about someone else takes EDITOR on the agent (its
 * owner, an admin, an editor grant). A non-editor's delete of another subject's memory deletes
 * nothing and still answers 204, so it does not reveal whether the id exists. Members manage their
 * own memories across agents at {@code /api/memory}.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/memories")
public class AgentMemoryController {

    private final AgentService agentService;
    private final ObjectProvider<AgentMemoryService> agentMemoryService;

    public AgentMemoryController(
            AgentService agentService, ObjectProvider<AgentMemoryService> agentMemoryService) {
        this.agentService = agentService;
        this.agentMemoryService = agentMemoryService;
    }

    @GetMapping
    public List<AgentMemoryEntry> memories(
            @PathVariable UUID agentId, @RequestParam String subjectKey, Principal principal) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        if (!isCaller(subjectKey, principal)) {
            agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        }
        AgentMemoryService service = agentMemoryService.getIfAvailable();
        return service == null ? List.of() : service.listAll(agentId, subjectKey);
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> forgetMemory(
            @PathVariable UUID agentId, @PathVariable UUID memoryId, Principal principal) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        AgentMemoryService service = agentMemoryService.getIfAvailable();
        if (service != null) {
            if (hasAccess(agentId, PermissionLevel.EDITOR)) {
                service.forget(agentId, memoryId);
            } else if (principal != null && StringUtils.hasText(principal.getName())) {
                service.forget(agentId, memoryId, principal.getName());
            }
        }
        return ResponseEntity.noContent().build();
    }

    private static boolean isCaller(String subjectKey, Principal principal) {
        return principal != null
                && StringUtils.hasText(principal.getName())
                && principal.getName().equals(subjectKey);
    }

    private boolean hasAccess(UUID agentId, PermissionLevel level) {
        try {
            agentService.requireAccess(agentId, level);
            return true;
        } catch (ResourceAccessDeniedException e) {
            return false;
        }
    }
}
