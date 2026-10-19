package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * A command describing ONE per-field configuration change to an agent, applied by {@link
 * AgentSettingsService} through the {@link AgentSettingsService.FacetApplier} registered for its
 * {@link Facet}. It replaces the ~40 near-identical {@code setX}/{@code isX} methods that each
 * repeated "require EDITOR access, check the edit-lock, mutate, save a versioned snapshot": that
 * sequence now exists once, and a new setting is a new patch record plus one applier case.
 *
 * <p>A patch carries only the new value; the human-readable change summary that goes into the
 * version history is produced by the applier, never by the client.
 */
public sealed interface AgentSettingsPatch {

    /** The settings area a patch belongs to; one applier is registered per facet. */
    enum Facet {
        GENERATION_PARAMS,
        TUNING,
        CACHING,
        CAPABILITIES,
        RUN_BUDGET,
        AUTONOMY,
        REASONING_DEPTH,
        SCOPE
    }

    /** The boolean capability switches on the agent. */
    enum CapabilityFlag {
        KNOWLEDGE_BASE,
        KNOWLEDGE_WRITE,
        SHARE_MEMORY_WITH_TENANT,
        BIOMETRIC_CONSENT
    }

    /** The two response-cache layers. */
    enum CacheKind {
        EXACT_MATCH,
        SEMANTIC
    }

    Facet facet();

    /** max tokens / temperature / topP plus the optional extra sampling controls. */
    record GenerationParams(AgentGenerationParamsUpdateRequest request)
            implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.GENERATION_PARAMS;
        }
    }

    /** SIMPLE (inferred params) vs MANUAL tuning; null resolves to MANUAL. */
    record Tuning(TuningMode mode) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.TUNING;
        }
    }

    record Caching(CacheKind kind, boolean enabled) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.CACHING;
        }
    }

    record Capability(CapabilityFlag flag, boolean enabled) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.CAPABILITIES;
        }
    }

    /** Per-agent run cap in USD; null clears it. */
    record RunBudget(BigDecimal usd) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.RUN_BUDGET;
        }
    }

    record Autonomy(AgentAutonomyTier tier) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.AUTONOMY;
        }
    }

    record Reasoning(ReasoningDepth depth) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.REASONING_DEPTH;
        }
    }

    /** Edge routing policy; null clears it (device preferences decide). */
    record EdgeRouting(EdgeRoutingPolicy policy) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.AUTONOMY;
        }
    }

    /** Knowledge collections the agent may search; null/empty = all. */
    record KnowledgeCollections(Set<UUID> ids) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.SCOPE;
        }
    }

    /** Code-intel repos the agent may read; null/empty = none. */
    record CodeIntelRepos(Set<String> ids) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.SCOPE;
        }
    }

    /** Collections the knowledge-write tool may write to; null/empty = none. */
    record KnowledgeWriteCollections(Set<UUID> ids) implements AgentSettingsPatch {
        @Override
        public Facet facet() {
            return Facet.SCOPE;
        }
    }
}
