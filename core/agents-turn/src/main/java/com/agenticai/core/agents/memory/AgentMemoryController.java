package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 *
 * <p>Memories written on a turn that handled untrusted content are listed with {@code trust =
 * QUARANTINED} and are never recalled until a person approves them ({@code POST
 * /{memoryId}/approve}) or rejects them ({@code POST /{memoryId}/reject}, or the plain delete). The
 * same permission bar applies as for delete: EDITOR for anyone's, VIEWER for the caller's own. The
 * tenant-wide shared pool is reviewed under {@code /shared}, which takes EDITOR because an approved
 * shared fact reaches every sharing agent.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/memories")
public class AgentMemoryController {

    private final AgentService agentService;
    private final ObjectProvider<AgentMemoryService> agentMemoryService;
    private final ObjectProvider<TenantMemoryService> tenantMemoryService;

    public AgentMemoryController(
            AgentService agentService, ObjectProvider<AgentMemoryService> agentMemoryService) {
        this(agentService, agentMemoryService, null);
    }

    @Autowired
    public AgentMemoryController(
            AgentService agentService,
            ObjectProvider<AgentMemoryService> agentMemoryService,
            ObjectProvider<TenantMemoryService> tenantMemoryService) {
        this.agentService = agentService;
        this.agentMemoryService = agentMemoryService;
        this.tenantMemoryService = tenantMemoryService;
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

    /**
     * Approves a QUARANTINED memory so it is recalled from now on; 404 when there is nothing to
     * approve.
     */
    @PostMapping("/{memoryId}/approve")
    public ResponseEntity<Void> approveMemory(
            @PathVariable UUID agentId, @PathVariable UUID memoryId, Principal principal) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        AgentMemoryService service = agentMemoryService.getIfAvailable();
        boolean approved = false;
        if (service != null) {
            if (hasAccess(agentId, PermissionLevel.EDITOR)) {
                approved = service.approve(agentId, memoryId);
            } else if (principal != null && StringUtils.hasText(principal.getName())) {
                approved = service.approve(agentId, memoryId, principal.getName());
            }
        }
        return approved ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Rejects (deletes) a quarantined memory; same rules and answer as the plain delete. */
    @PostMapping("/{memoryId}/reject")
    public ResponseEntity<Void> rejectMemory(
            @PathVariable UUID agentId, @PathVariable UUID memoryId, Principal principal) {
        return forgetMemory(agentId, memoryId, principal);
    }

    /** Shared-pool facts about a subject, quarantined ones included; EDITOR only. */
    @GetMapping("/shared")
    public List<TenantMemoryEntry> sharedMemories(
            @PathVariable UUID agentId,
            @RequestParam String subjectKey,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        TenantMemoryService service = tenantService();
        if (service == null) {
            return List.of();
        }
        List<TenantMemoryEntry> all = service.listFactsForSubject(subjectKey);
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + Math.max(1, Math.min(limit, 500)), all.size());
        return all.subList(from, to);
    }

    /**
     * Approves a quarantined shared fact; EDITOR. {@code subjectKey} is optional: without it the
     * approval is subject-agnostic (any subject's fact), with it the fact must be about that
     * subject.
     */
    @PostMapping("/shared/{memoryId}/approve")
    public ResponseEntity<Void> approveSharedMemory(
            @PathVariable UUID agentId,
            @PathVariable UUID memoryId,
            @RequestParam(required = false) String subjectKey) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        TenantMemoryService service = tenantService();
        boolean approved = service != null && service.approveFact(memoryId, subjectKey);
        return approved ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/shared/{memoryId}/reject")
    public ResponseEntity<Void> rejectSharedMemory(
            @PathVariable UUID agentId,
            @PathVariable UUID memoryId,
            @RequestParam String subjectKey) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        TenantMemoryService service = tenantService();
        if (service != null) {
            service.forgetFactForSubject(memoryId, subjectKey);
        }
        return ResponseEntity.noContent().build();
    }

    private TenantMemoryService tenantService() {
        return tenantMemoryService == null ? null : tenantMemoryService.getIfAvailable();
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
