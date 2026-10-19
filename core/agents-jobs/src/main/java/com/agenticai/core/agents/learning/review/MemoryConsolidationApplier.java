package com.agenticai.core.agents.learning.review;

import com.agenticai.core.agents.learning.LearningProposal;
import com.agenticai.core.agents.learning.LearningProposalApplier;
import com.agenticai.core.agents.learning.LearningProposalApplyException;
import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import com.agenticai.core.graphmemory.GraphMemoryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Approved CONSOLIDATION: tidies the person's remembered facts through the same service methods the Memory page
 * uses ({@code updateForSubject}, {@code forgetForSubject} and their shared-tier twins), so a fact is always
 * checked against its owner. A "merge" rewrites the kept fact with the approved (possibly edited) text and
 * forgets the others; a "drop" forgets. Nothing is touched unless every fact it names still exists and still
 * belongs to the person, so a proposal that went stale is refused, never half applied.
 *
 * <p>Like the Memory page, with {@code app.graph-memory.user-forget-sync.enabled} on, graph edges that only the
 * removed or rewritten wording supported are invalidated afterwards, best effort.
 */
@Component
@ConditionalOnProperty(name = "app.learning.proposals.enabled", havingValue = "true")
public class MemoryConsolidationApplier implements LearningProposalApplier {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationApplier.class);

    private final ObjectProvider<AgentMemoryService> agentMemory;
    private final ObjectProvider<TenantMemoryService> tenantMemory;
    private final ObjectProvider<GraphMemoryService> graph;
    private final boolean graphForgetSync;

    public MemoryConsolidationApplier(
            ObjectProvider<AgentMemoryService> agentMemory,
            ObjectProvider<TenantMemoryService> tenantMemory,
            ObjectProvider<GraphMemoryService> graph,
            @Value("${app.graph-memory.user-forget-sync.enabled:false}") boolean graphForgetSync) {
        this.agentMemory = agentMemory;
        this.tenantMemory = tenantMemory;
        this.graph = graph;
        this.graphForgetSync = graphForgetSync;
    }

    @Override
    public LearningProposalKind kind() {
        return LearningProposalKind.CONSOLIDATION;
    }

    @Override
    public void apply(LearningProposal proposal, String text) throws LearningProposalApplyException {
        ConsolidationPayload p;
        try {
            p = ConsolidationPayload.parse(proposal.getPayload());
        } catch (IllegalArgumentException e) {
            throw new LearningProposalApplyException(e.getMessage());
        }
        String subject = proposal.getSubject();
        boolean agentScope = MemoryFactRef.AGENT.equals(p.scope());
        // id -> current text, for the one tier this proposal is about.
        Map<UUID, String> tier = agentScope ? agentTier(subject, p.agentId()) : sharedTier(subject);
        for (UUID id : idsOf(p)) {
            if (!tier.containsKey(id)) {
                throw new LearningProposalApplyException(
                        "Some of these memories changed or were already removed. Reject this suggestion; the next review will look again.");
            }
        }
        // The person approved the wording they were shown: a fact edited or replaced since is stale.
        for (Map.Entry<UUID, String> h : p.hashes().entrySet()) {
            String live = tier.get(h.getKey());
            if (live != null && !h.getValue().equals(ConsolidationPayload.fingerprint(live))) {
                throw new LearningProposalApplyException(
                        "Some of these memories changed since this was suggested. Reject it; the next review will look again.");
            }
        }
        boolean merge = ConsolidationPayload.MERGE.equals(p.action());
        String merged = text == null ? "" : text.strip();
        if (merge) {
            if (merged.isBlank()) {
                throw new LearningProposalApplyException("The merged memory cannot be empty.");
            }
            if (SkillDraftScrubber.redactions(merged) > 0) {
                throw new LearningProposalApplyException(
                        "That text contains a redacted part, which would overwrite the real memory. Edit it to plain wording first.");
            }
        }
        Map<UUID, String> before = new LinkedHashMap<>(tier);
        if (merge && !merged.equals(tier.get(p.keepId()))) {
            boolean changed;
            try {
                changed = agentScope
                        ? requireAgent().updateForSubject(p.keepId(), subject, merged)
                        : requireTenant().updateFactForSubject(p.keepId(), subject, merged);
            } catch (RuntimeException e) {
                log.warn("Memory consolidation could not rewrite a fact: {}", e.getMessage());
                throw new LearningProposalApplyException("Could not update that memory right now. Try again in a moment.");
            }
            if (!changed) {
                throw new LearningProposalApplyException("That memory is gone. Reject this suggestion.");
            }
            tier.put(p.keepId(), merged);
        }
        for (UUID id : p.dropIds()) {
            boolean gone = agentScope
                    ? requireAgent().forgetForSubject(id, subject)
                    : requireTenant().forgetFactForSubject(id, subject);
            if (gone) {
                tier.remove(id);
            }
        }
        syncGraph(subject, agentScope ? p.agentId() : null, before, tier, p);
    }

    private void syncGraph(
            String subject, UUID tierAgentId, Map<UUID, String> before, Map<UUID, String> after, ConsolidationPayload p) {
        if (!graphForgetSync) {
            return;
        }
        try {
            GraphMemoryService g = graph.getIfAvailable();
            if (g == null) {
                return;
            }
            List<String> remaining = new ArrayList<>(after.values());
            List<UUID> changed = new ArrayList<>(p.dropIds());
            if (p.keepId() != null) {
                changed.add(p.keepId());
            }
            for (UUID id : changed) {
                String old = before.get(id);
                // Edges the surviving wording still supports stay valid: it is part of "remaining".
                if (old != null && !Objects.equals(old, after.get(id))) {
                    g.forgetFactText(subject, tierAgentId, old, remaining);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Graph forget-sync failed after a memory tidy-up - the memories themselves are changed", e);
        }
    }

    private static List<UUID> idsOf(ConsolidationPayload p) {
        List<UUID> ids = new ArrayList<>(p.dropIds());
        if (p.keepId() != null) {
            ids.add(p.keepId());
        }
        return ids;
    }

    private Map<UUID, String> agentTier(String subject, UUID agentId) throws LearningProposalApplyException {
        Map<UUID, String> out = new HashMap<>();
        for (AgentMemoryEntry e : requireAgent().listAllForSubject(subject)) {
            if (Objects.equals(e.agentId(), agentId)) {
                out.put(e.id(), e.content());
            }
        }
        return out;
    }

    private Map<UUID, String> sharedTier(String subject) throws LearningProposalApplyException {
        Map<UUID, String> out = new HashMap<>();
        for (TenantMemoryEntry e : requireTenant().listFactsForSubject(subject)) {
            out.put(e.id(), e.content());
        }
        return out;
    }

    private AgentMemoryService requireAgent() throws LearningProposalApplyException {
        AgentMemoryService s = agentMemory.getIfAvailable();
        if (s == null) {
            throw new LearningProposalApplyException("Memory is not available on this install.");
        }
        return s;
    }

    private TenantMemoryService requireTenant() throws LearningProposalApplyException {
        TenantMemoryService s = tenantMemory.getIfAvailable();
        if (s == null) {
            throw new LearningProposalApplyException("Memory is not available on this install.");
        }
        return s;
    }
}
