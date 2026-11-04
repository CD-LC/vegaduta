package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SELF_MODEL recall (ADR-0028 section 3): one short, platform-derived summary of what the agent is good and unreliable
 * at, rendered by {@link CompetenceSummary} from the {@code memory_competence} counters. It is TRUSTED because no model
 * and no user text is in it, only fixed words, platform task-class identifiers, counts and a Wilson bound; there is
 * nothing to inject through. It is off unless unified recall is enabled for the agent, the platform allows the {@code
 * self-model} kind, AND competence counting is enabled; below the minimum sample size it says nothing. It never throws.
 */
public class SelfModelRecallSource implements MemoryRecallSource {

    static final String WRITER = "competence_counters";
    private static final Set<MemoryKind> KINDS = Set.of(MemoryKind.SELF_MODEL);

    private final MemoryUnifiedProperties unified;
    private final MemoryUnifiedGate unifiedGate;
    private final CompetenceProperties properties;
    private final CompetenceTracker tracker;

    public SelfModelRecallSource(
            MemoryUnifiedProperties unified,
            MemoryUnifiedGate unifiedGate,
            CompetenceProperties properties,
            CompetenceTracker tracker) {
        this.unified = unified;
        this.unifiedGate = unifiedGate;
        this.properties = properties;
        this.tracker = tracker;
    }

    @Override
    public String name() {
        return "self-model-competence";
    }

    @Override
    public Set<MemoryKind> kinds() {
        return KINDS;
    }

    @Override
    public MemoryRecallResult recall(MemoryRecallRequest request) {
        try {
            if (request == null
                    || request.agentId() == null
                    || !request.wants(MemoryKind.SELF_MODEL)
                    || !unified.getKinds().contains(MemoryKind.SELF_MODEL)
                    || !unifiedGate.enabled(request.agentId())) {
                return MemoryRecallResult.empty("disabled");
            }
            if (TenantContext.current().map(TenantContext::getTenantId).filter(t -> !t.isBlank()).isEmpty()) {
                return MemoryRecallResult.empty("no-tenant");
            }
            List<CompetenceRow> rows = tracker.rows(request.agentId()); // empty while competence is off
            Optional<String> text = CompetenceSummary.render(rows, properties, request.tokenBudget());
            if (text.isEmpty()) {
                return MemoryRecallResult.empty(null);
            }
            MemoryItemView view = new MemoryItemView(
                    itemId(request.agentId()),
                    MemoryKind.SELF_MODEL,
                    text.get(),
                    0.5,
                    new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of(), WRITER));
            return new MemoryRecallResult(List.of(view), MemoryTokens.estimate(text.get()), 0, null);
        } catch (RuntimeException e) {
            return MemoryRecallResult.empty("error");
        }
    }

    /** A stable id per agent: the summary is derived, not a stored row. */
    static UUID itemId(UUID agentId) {
        return UUID.nameUUIDFromBytes(("self-model-competence:" + agentId).getBytes(StandardCharsets.UTF_8));
    }
}
