package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ConsolidationFactSource} over the two fact stores. This adapter is the single place in the
 * consolidation package that touches {@link AgentMemoryService} and {@link TenantMemoryService},
 * and it calls only their {@code list*} methods - the write surface never crosses the port.
 * Services are optional so the jobs shape starts whether or not memory beans are present.
 */
@Component
public class FactStoreConsolidationSource implements ConsolidationFactSource {

    private final ObjectProvider<AgentMemoryService> agentMemory;
    private final ObjectProvider<TenantMemoryService> tenantMemory;

    public FactStoreConsolidationSource(
            ObjectProvider<AgentMemoryService> agentMemory, ObjectProvider<TenantMemoryService> tenantMemory) {
        this.agentMemory = agentMemory;
        this.tenantMemory = tenantMemory;
    }

    @Override
    public List<String> subjects(int limit) {
        Set<String> subjects = new LinkedHashSet<>();
        AgentMemoryService agent = agentMemory.getIfAvailable();
        if (agent != null) {
            subjects.addAll(agent.listSubjects(limit));
        }
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (tenant != null && subjects.size() < limit) {
            subjects.addAll(tenant.listFactSubjects(limit));
        }
        return subjects.size() > limit ? new ArrayList<>(subjects).subList(0, limit) : List.copyOf(subjects);
    }

    @Override
    public List<Fact> facts(String subjectKey) {
        List<Fact> out = new ArrayList<>();
        AgentMemoryService agent = agentMemory.getIfAvailable();
        if (agent != null) {
            for (AgentMemoryEntry m : agent.listAllForSubject(subjectKey)) {
                out.add(new Fact(
                        Fact.AGENT,
                        m.id(),
                        m.agentId(),
                        m.content(),
                        m.trust(),
                        m.useCount(),
                        m.createdAt(),
                        m.lastUsedAt()));
            }
        }
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (tenant != null) {
            for (TenantMemoryEntry m : tenant.listFactsForSubject(subjectKey)) {
                out.add(new Fact(
                        Fact.SHARED,
                        m.id(),
                        m.createdByAgentId(),
                        m.content(),
                        m.trust(),
                        m.useCount(),
                        m.createdAt(),
                        m.lastUsedAt()));
            }
        }
        return out;
    }
}
