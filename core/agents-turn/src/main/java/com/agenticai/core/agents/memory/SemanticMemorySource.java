package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The existing long-term stores as a {@link MemoryRecallSource}: the agent's own facts
 * ({@code agent_memory}) and the tenant-shared facts ({@code tenant_memory}) as SEMANTIC items.
 * Read-only over the services' own queries, which already restrict to {@code trust = 'TRUSTED'};
 * a row that nevertheless arrives QUARANTINED is dropped here as well. No backfill and no schema
 * change: nothing is copied into a new table.
 *
 * <p>Not wired into the chat prompt: the only caller is the unified-memory composer, which does
 * nothing while {@code app.memory.unified.enabled} is false.
 */
@Component
public class SemanticMemorySource implements MemoryRecallSource {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemorySource.class);

    static final int DEFAULT_TOP_K = 5;

    private final ObjectProvider<AgentMemoryService> agentMemory;
    private final ObjectProvider<TenantMemoryService> tenantMemory;

    public SemanticMemorySource(
            ObjectProvider<AgentMemoryService> agentMemory, ObjectProvider<TenantMemoryService> tenantMemory) {
        this.agentMemory = agentMemory;
        this.tenantMemory = tenantMemory;
    }

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public Set<MemoryKind> kinds() {
        return Set.of(MemoryKind.SEMANTIC);
    }

    @Override
    public MemoryRecallResult recall(MemoryRecallRequest request) {
        if (request == null || !request.wants(MemoryKind.SEMANTIC)) {
            return MemoryRecallResult.empty(null);
        }
        // Fail closed: no tenant bound, or no subject to isolate by, means nothing is recalled.
        if (TenantContext.current().isEmpty()
                || request.agentId() == null
                || request.subjectKey() == null
                || request.subjectKey().isBlank()
                || request.queryText() == null
                || request.queryText().isBlank()) {
            return MemoryRecallResult.empty(null);
        }
        int topK = request.maxItems() > 0 ? request.maxItems() : DEFAULT_TOP_K;
        AgentMemoryService agent = agentMemory.getIfAvailable();
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (agent == null && tenant == null) {
            return MemoryRecallResult.empty("unavailable");
        }
        List<MemoryItemView> items = new ArrayList<>();
        try {
            if (agent != null) {
                for (AgentMemoryEntry e :
                        agent.recall(request.agentId(), request.subjectKey(), request.queryText(), topK)) {
                    add(items, e.id(), e.content(), e.distance(), e.trust(), e.sourceTaint(), "agent_memory");
                }
            }
            if (tenant != null) {
                for (TenantMemoryEntry e : tenant.recall(request.subjectKey(), request.queryText(), topK)) {
                    add(items, e.id(), e.content(), e.distance(), e.trust(), e.sourceTaint(), "tenant_memory");
                }
            }
        } catch (RuntimeException e) {
            log.warn("Semantic memory recall failed; returning no items", e);
            return MemoryRecallResult.empty("error");
        }
        return new MemoryRecallResult(items, 0, 0, null);
    }

    private static void add(
            List<MemoryItemView> into,
            java.util.UUID id,
            String content,
            double distance,
            MemoryTrust trust,
            List<String> taint,
            String writer) {
        if (trust != MemoryTrust.TRUSTED || content == null || content.isBlank()) {
            return;
        }
        double score = Math.max(0.0, Math.min(1.0, 1.0 - distance));
        into.add(
                new MemoryItemView(
                        id,
                        MemoryKind.SEMANTIC,
                        content,
                        score,
                        new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, taint, writer)));
    }
}
