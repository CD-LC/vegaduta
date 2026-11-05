package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryFactSignalsReader;
import com.agenticai.core.agents.memory.MemoryFactSignalsReader.Signals;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ConsolidationFactSource} over the two fact stores. This adapter is the single place in the
 * consolidation package that touches {@link AgentMemoryService} and {@link TenantMemoryService},
 * and it calls only their {@code list*} methods - the write surface never crosses the port.
 * Services are optional so the jobs shape starts whether or not memory beans are present.
 *
 * <p>Facts already tombstoned are left out (their rows stay, only recall hides them), so a forgotten
 * fact is not planned, and tombstoned again, every night, and does not inflate the circuit breaker's
 * denominator. Importance, pin and authorship come from {@link MemoryFactSignalsReader} (read only).
 */
@Component
public class FactStoreConsolidationSource implements ConsolidationFactSource {

    private final ObjectProvider<AgentMemoryService> agentMemory;
    private final ObjectProvider<TenantMemoryService> tenantMemory;
    private final ObjectProvider<MemoryFactSignalsReader> signals;

    public FactStoreConsolidationSource(
            ObjectProvider<AgentMemoryService> agentMemory,
            ObjectProvider<TenantMemoryService> tenantMemory,
            ObjectProvider<MemoryFactSignalsReader> signals) {
        this.agentMemory = agentMemory;
        this.tenantMemory = tenantMemory;
        this.signals = signals;
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
        MemoryFactSignalsReader reader = signals.getIfAvailable();
        Set<String> forgotten = reader == null ? Set.of() : reader.forgottenKeys(subjectKey);
        AgentMemoryService agent = agentMemory.getIfAvailable();
        if (agent != null) {
            Map<UUID, Signals> sig = reader == null ? Map.of() : reader.agentSignals(subjectKey);
            for (AgentMemoryEntry m : agent.listAllForSubject(subjectKey)) {
                if (forgotten.contains(MemoryFactSignalsReader.tombstoneKey(false, m.agentId(), m.content()))) {
                    continue;
                }
                Signals s = sig.getOrDefault(m.id(), Signals.NEUTRAL);
                out.add(new Fact(
                        Fact.AGENT,
                        m.id(),
                        m.agentId(),
                        m.content(),
                        m.trust(),
                        m.useCount(),
                        m.createdAt(),
                        m.lastUsedAt(),
                        s.importance(),
                        s.pinned(),
                        s.personAuthored()));
            }
        }
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (tenant != null) {
            Map<UUID, Signals> sig = reader == null ? Map.of() : reader.sharedSignals(subjectKey);
            for (TenantMemoryEntry m : tenant.listFactsForSubject(subjectKey)) {
                if (forgotten.contains(MemoryFactSignalsReader.tombstoneKey(true, null, m.content()))) {
                    continue;
                }
                Signals s = sig.getOrDefault(m.id(), Signals.NEUTRAL);
                out.add(new Fact(
                        Fact.SHARED,
                        m.id(),
                        m.createdByAgentId(),
                        m.content(),
                        m.trust(),
                        m.useCount(),
                        m.createdAt(),
                        m.lastUsedAt(),
                        s.importance(),
                        s.pinned(),
                        s.personAuthored()));
            }
        }
        return out;
    }
}
