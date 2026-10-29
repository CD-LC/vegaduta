package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentSettingsPatch} is the sealed command type {@link AgentSettingsService} dispatches on:
 * every record must report the facet its applier is registered under. These pin that mapping - a
 * patch routed to the wrong facet would apply the change to the wrong settings area.
 */
class AgentSettingsPatchTest {

    @Test
    void everyPatchReportsItsFacet() {
        assertEquals(AgentSettingsPatch.Facet.GENERATION_PARAMS,
            new AgentSettingsPatch.GenerationParams(null).facet());
        assertEquals(AgentSettingsPatch.Facet.TUNING,
            new AgentSettingsPatch.Tuning(TuningMode.SIMPLE).facet());
        assertEquals(AgentSettingsPatch.Facet.CACHING,
            new AgentSettingsPatch.Caching(AgentSettingsPatch.CacheKind.EXACT_MATCH, true).facet());
        assertEquals(AgentSettingsPatch.Facet.CAPABILITIES,
            new AgentSettingsPatch.Capability(AgentSettingsPatch.CapabilityFlag.KNOWLEDGE_BASE, true).facet());
        assertEquals(AgentSettingsPatch.Facet.RUN_BUDGET,
            new AgentSettingsPatch.RunBudget(new BigDecimal("1.50")).facet());
        assertEquals(AgentSettingsPatch.Facet.RUNTIME_LIMITS,
            new AgentSettingsPatch.RuntimeLimitsPatch(null).facet());
        assertEquals(AgentSettingsPatch.Facet.AUTONOMY,
            new AgentSettingsPatch.Autonomy(AgentAutonomyTier.SUPERVISED).facet());
        assertEquals(AgentSettingsPatch.Facet.REASONING_DEPTH,
            new AgentSettingsPatch.Reasoning(ReasoningDepth.CAREFUL).facet());
        // Edge routing is applied by the autonomy applier, deliberately not its own facet.
        assertEquals(AgentSettingsPatch.Facet.AUTONOMY,
            new AgentSettingsPatch.EdgeRouting(EdgeRoutingPolicy.EDGE_FIRST).facet());
        assertEquals(AgentSettingsPatch.Facet.SCOPE,
            new AgentSettingsPatch.KnowledgeCollections(Set.of(UUID.randomUUID())).facet());
        assertEquals(AgentSettingsPatch.Facet.SCOPE,
            new AgentSettingsPatch.CodeIntelRepos(Set.of("repo-1")).facet());
        assertEquals(AgentSettingsPatch.Facet.SCOPE,
            new AgentSettingsPatch.KnowledgeWriteCollections(Set.of(UUID.randomUUID())).facet());
    }

    @Test
    void recordsCarryTheirPayloadsVerbatim() {
        AgentSettingsPatch.Tuning tuning = new AgentSettingsPatch.Tuning(null);
        assertNull(tuning.mode());

        AgentSettingsPatch.Caching caching =
            new AgentSettingsPatch.Caching(AgentSettingsPatch.CacheKind.SEMANTIC, false);
        assertEquals(AgentSettingsPatch.CacheKind.SEMANTIC, caching.kind());
        assertFalse(caching.enabled());

        AgentSettingsPatch.Capability capability =
            new AgentSettingsPatch.Capability(AgentSettingsPatch.CapabilityFlag.BIOMETRIC_CONSENT, true);
        assertEquals(AgentSettingsPatch.CapabilityFlag.BIOMETRIC_CONSENT, capability.flag());

        AgentSettingsPatch.RunBudget budget = new AgentSettingsPatch.RunBudget(null);
        assertNull(budget.usd());

        AgentSettingsPatch.EdgeRouting edge = new AgentSettingsPatch.EdgeRouting(null);
        assertNull(edge.policy());
    }
}
