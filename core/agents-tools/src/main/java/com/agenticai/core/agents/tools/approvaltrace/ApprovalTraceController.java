package com.agenticai.core.agents.tools.approvaltrace;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only: what the agent did after an approval was decided. The decision fields need nothing
 * more than {@code GET /api/approvals/{id}} does; the tool calls are the agent's own, so seeing
 * them needs viewer access to that agent.
 */
@RestController
@RequestMapping("/api/approval-traces")
public class ApprovalTraceController {

    private final ApprovalTraceService service;
    private final AgentService agentService;

    public ApprovalTraceController(ApprovalTraceService service, AgentService agentService) {
        this.service = service;
        this.agentService = agentService;
    }

    @GetMapping("/{requestId}")
    public ApprovalTraceView get(@PathVariable UUID requestId) {
        service.conversationOf(requestId)
                .ifPresent(c -> agentService.requireAccess(c.agentId(), PermissionLevel.VIEWER));
        return service.trace(requestId);
    }
}
