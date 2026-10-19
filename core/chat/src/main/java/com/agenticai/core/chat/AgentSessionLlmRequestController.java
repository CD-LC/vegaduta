package com.agenticai.core.chat;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "What was sent to the model" for ONE agent chat session: the final prompt
 * (system blocks, history, user turn), sampling options and tool list of every
 * model call, oldest first. Backed by the in-memory {@link LlmRequestSnapshotStore}
 * - tenant-scoped by key, so a session id from another tenant returns empty.
 * The playground fetches it after each turn to render the Request Inspector.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/llm-requests")
public class AgentSessionLlmRequestController {

    @GetMapping
    public List<LlmRequestSnapshot> requests(@PathVariable UUID agentId, @PathVariable String sessionId) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return LlmRequestSnapshotStore.INSTANCE.list(tenantId, sessionId, agentId.toString(), null, null);
    }
}
