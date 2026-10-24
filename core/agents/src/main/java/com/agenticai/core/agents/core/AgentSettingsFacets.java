package com.agenticai.core.agents.core;

import com.agenticai.core.agents.core.AgentSettingsPatch.CacheKind;
import com.agenticai.core.agents.core.AgentSettingsPatch.Facet;
import com.agenticai.core.agents.core.AgentSettingsService.FacetApplier;
import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@link FacetApplier}s behind {@link AgentSettingsService}: one per {@link Facet}. Each only
 * mutates the entity and returns the version-history summary; access, edit-lock and the versioned
 * save are {@link AgentSettingsService#apply}'s job. Summaries and side effects (the schema
 * refresh after a scope or generation change) are exactly those of the per-field methods they
 * replaced.
 */
final class AgentSettingsFacets {

    private AgentSettingsFacets() {}

    static List<FacetApplier> all(
            GenerationParamsInferenceService inference,
            AgentModelResolver modelResolver,
            AgentSchemaService schemaService) {
        return List.of(
                new GenerationParamsApplier(schemaService),
                new TuningApplier(inference, modelResolver),
                new CachingApplier(),
                new CapabilitiesApplier(schemaService),
                new RunBudgetApplier(),
                new RuntimeLimitsApplier(),
                new AutonomyApplier(),
                new ReasoningDepthApplier(),
                new ScopeApplier(schemaService));
    }

    /**
     * V331 extra sampling controls: null = unchanged, empty stopSequences = clear, {@code
     * clearSamplingParams=true} resets all five first.
     */
    static void applySamplingUpdate(Agent agent, AgentGenerationParamsUpdateRequest request) {
        Integer topK = agent.getTopK();
        List<String> stops = agent.getStopSequences();
        Double freq = agent.getFrequencyPenalty();
        Double pres = agent.getPresencePenalty();
        Integer seed = agent.getSeed();
        if (Boolean.TRUE.equals(request.clearSamplingParams())) {
            topK = null;
            stops = List.of();
            freq = null;
            pres = null;
            seed = null;
        }
        if (request.topK() != null) topK = request.topK();
        if (request.stopSequences() != null) stops = request.stopSequences();
        if (request.frequencyPenalty() != null) freq = request.frequencyPenalty();
        if (request.presencePenalty() != null) pres = request.presencePenalty();
        if (request.seed() != null) seed = request.seed();
        agent.setSamplingParams(topK, stops, freq, pres, seed);
    }

    private static String toggle(boolean enabled, String what) {
        return (enabled ? "Enabled" : "Disabled") + " " + what;
    }

    private static IllegalArgumentException unsupported(AgentSettingsPatch patch) {
        return new IllegalArgumentException("Unsupported settings patch " + patch);
    }

    private record GenerationParamsApplier(AgentSchemaService schemaService)
            implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.GENERATION_PARAMS;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (!(patch instanceof AgentSettingsPatch.GenerationParams p)) throw unsupported(patch);
            AgentGenerationParamsUpdateRequest request = p.request();
            agent.setGenerationParams(request.maxTokens(), request.temperature(), request.topP());
            applySamplingUpdate(agent, request);
            schemaService.refresh(agent);
            return "Changed generation params (maxTokens/temperature/topP/sampling)";
        }
    }

    private record TuningApplier(
            GenerationParamsInferenceService inference, AgentModelResolver modelResolver)
            implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.TUNING;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (!(patch instanceof AgentSettingsPatch.Tuning p)) throw unsupported(patch);
            TuningMode resolved = p.mode() != null ? p.mode() : TuningMode.MANUAL;
            agent.setTuningMode(resolved);
            if (resolved == TuningMode.SIMPLE) {
                // Switching TO SIMPLE re-infers from the immutable system prompt (and heals a null
                // model on the way); switching TO MANUAL only flips the flag.
                GenerationParamsInferenceService.InferredParams inferred =
                        inference.infer(
                                agent.getSystemPrompt(),
                                agent.getProviderName(),
                                modelResolver.resolveEffectiveModel(agent));
                agent.setGenerationParams(
                        inferred.maxTokens(), inferred.temperature(), inferred.topP());
            }
            // One version row covers both the mode flag and the params it re-inferred.
            return "Changed tuning mode to " + resolved;
        }
    }

    private record CachingApplier() implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.CACHING;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (!(patch instanceof AgentSettingsPatch.Caching p)) throw unsupported(patch);
            if (p.kind() == CacheKind.SEMANTIC) {
                agent.setSemanticResponseCachingEnabled(p.enabled());
                return toggle(p.enabled(), "semantic response caching");
            }
            agent.setResponseCachingEnabled(p.enabled());
            return toggle(p.enabled(), "exact-match response caching");
        }
    }

    private record CapabilitiesApplier(AgentSchemaService schemaService) implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.CAPABILITIES;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (!(patch instanceof AgentSettingsPatch.Capability p)) throw unsupported(patch);
            boolean enabled = p.enabled();
            switch (p.flag()) {
                case KNOWLEDGE_BASE -> {
                    agent.setKnowledgeBaseEnabled(enabled);
                    schemaService.refresh(agent);
                    return toggle(enabled, "knowledge base");
                }
                case KNOWLEDGE_WRITE -> {
                    agent.setKnowledgeWriteEnabled(enabled);
                    return toggle(enabled, "knowledge write tool");
                }
                case SHARE_MEMORY_WITH_TENANT -> {
                    agent.setShareMemoryWithTenant(enabled);
                    return toggle(enabled, "tenant-shared memory");
                }
                case BIOMETRIC_CONSENT -> {
                    agent.setBiometricConsentEnabled(enabled);
                    return (enabled ? "Granted" : "Withdrew") + " biometric consent";
                }
                default -> throw unsupported(patch);
            }
        }
    }

    private record RunBudgetApplier() implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.RUN_BUDGET;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (!(patch instanceof AgentSettingsPatch.RunBudget p)) throw unsupported(patch);
            agent.setRunBudgetUsd(p.usd());
            return "Changed run cap";
        }
    }

    private record RuntimeLimitsApplier() implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.RUNTIME_LIMITS;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (!(patch instanceof AgentSettingsPatch.RuntimeLimitsPatch p)) throw unsupported(patch);
            agent.setRuntimeLimits(p.limits());
            return p.limits() == null || p.limits().isEmpty()
                    ? "Cleared runtime limit overrides"
                    : "Changed runtime limits";
        }
    }

    /** Autonomy tier and the server-declared edge routing policy share the autonomy facet. */
    private record AutonomyApplier() implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.AUTONOMY;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (patch instanceof AgentSettingsPatch.Autonomy p) {
                agent.setAutonomyTier(p.tier());
                return "Changed autonomy tier to " + p.tier();
            }
            if (patch instanceof AgentSettingsPatch.EdgeRouting p) {
                agent.setEdgeRoutingPolicy(p.policy());
                return p.policy() == null
                        ? "Cleared edge routing policy (device preferences decide)"
                        : "Changed edge routing policy to " + p.policy();
            }
            throw unsupported(patch);
        }
    }

    private record ReasoningDepthApplier() implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.REASONING_DEPTH;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (!(patch instanceof AgentSettingsPatch.Reasoning p)) throw unsupported(patch);
            agent.setReasoningDepth(p.depth());
            return "Changed reasoning depth to " + p.depth();
        }
    }

    /** Which knowledge collections, code-intel repos and write collections the agent may reach. */
    private record ScopeApplier(AgentSchemaService schemaService) implements FacetApplier {
        @Override
        public Facet facet() {
            return Facet.SCOPE;
        }

        @Override
        public String apply(Agent agent, AgentSettingsPatch patch) {
            if (patch instanceof AgentSettingsPatch.KnowledgeCollections p) {
                Set<UUID> ids = p.ids();
                agent.setAllowedKnowledgeCollectionIds(ids);
                schemaService.refresh(agent);
                return ids == null || ids.isEmpty()
                        ? "Cleared knowledge collection scope (all collections searchable)"
                        : "Changed knowledge collection scope (" + ids.size() + " collections)";
            }
            if (patch instanceof AgentSettingsPatch.CodeIntelRepos p) {
                Set<String> ids = p.ids();
                agent.setAllowedCodeIntelRepoIds(ids);
                return ids == null || ids.isEmpty()
                        ? "Revoked all code-intel repo access"
                        : "Changed code-intel repo access (" + ids.size() + " repos)";
            }
            if (patch instanceof AgentSettingsPatch.KnowledgeWriteCollections p) {
                Set<UUID> ids = p.ids();
                agent.setAllowedKnowledgeWriteCollectionIds(ids);
                return ids == null || ids.isEmpty()
                        ? "Revoked all knowledge-write collection access"
                        : "Changed knowledge-write collection access (" + ids.size() + " collections)";
            }
            throw unsupported(patch);
        }
    }
}
