package com.agenticai.core.agents.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentResponse#from} is the entity-to-DTO mapping every GET/PATCH response goes through.
 * These pin that it copies the agent's fields verbatim, honours the editable flag and access-level
 * pass-through, and collapses an empty {@link com.agenticai.core.agents.runtime.RuntimeLimits} to
 * null (the API omits the object when the agent overrides nothing).
 */
class AgentResponseTest {

    private static Agent agent() {
        return new Agent(
                "Helper",
                "be helpful",
                ProviderName.ANTHROPIC,
                "claude-opus-4-8",
                2048,
                0.5,
                0.9,
                true,
                null,
                ReasoningDepth.CAREFUL,
                TuningMode.MANUAL);
    }

    @Test
    void fromCopiesTheAgentFieldsVerbatim() {
        Agent a = agent();

        AgentResponse r = AgentResponse.from(a);

        assertEquals("Helper", r.name());
        assertEquals("be helpful", r.systemPrompt());
        assertEquals(ProviderName.ANTHROPIC, r.providerName());
        assertEquals("claude-opus-4-8", r.model());
        assertEquals(2048, r.maxTokens());
        assertEquals(0.5, r.temperature());
        assertEquals(0.9, r.topP());
        assertEquals(TuningMode.MANUAL, r.tuningMode());
        assertTrue(r.knowledgeBaseEnabled());
        assertFalse(r.sarvamEnabled());
        assertFalse(r.e2bEnabled());
        assertEquals(ReasoningDepth.CAREFUL, r.reasoningDepth());
        assertEquals(a.getCreatedAt(), r.createdAt());
        assertEquals(a.getUpdatedAt(), r.updatedAt());
        assertTrue(r.editable(), "the no-flag overload is for internal callers - always editable");
        assertNull(r.modalities());
        assertNull(r.accessLevel());
    }

    @Test
    void theEditableFlagAndAccessLevelPassThrough() {
        Agent a = agent();

        assertFalse(AgentResponse.from(a, false).editable());
        assertTrue(AgentResponse.from(a, true).editable());
        assertEquals("RUNNER", AgentResponse.from(a, true, null, "RUNNER").accessLevel());
        assertEquals("VIEWER", AgentResponse.from(a, false, null, "VIEWER").accessLevel());
    }

    @Test
    void anAgentWithNoRuntimeLimitOverridesOmitsTheObject() {
        assertNull(
                AgentResponse.from(agent()).runtimeLimits(),
                "empty overrides serialize as an absent field, not an all-null object");
    }

    @Test
    void runtimeLimitsAreCarriedWhenTheAgentOverridesAny() {
        Agent a = agent();
        a.setRuntimeLimits(
                new com.agenticai.core.agents.runtime.RuntimeLimits(
                        5, null, null, null, null, null, null));

        AgentResponse r = AgentResponse.from(a);

        assertEquals(5, r.runtimeLimits().getMaxToolSteps());
        // Clearing them again returns the response to the omitted state.
        a.setRuntimeLimits(null);
        assertNull(AgentResponse.from(a).runtimeLimits());
    }

    @Test
    void modalityAndFallbackFieldsDefaultToNull() {
        AgentResponse r = AgentResponse.from(agent());

        assertNull(r.fallbackProviderName());
        assertNull(r.fallbackModel());
        assertEquals(0, r.fallbackTriggeredCount());
        assertNull(r.lastFallbackAt());
        assertNull(r.voiceModelProvider());
        assertNull(r.videoModelProvider());
        assertNull(r.ttsProvider());
        assertNull(r.inputSchemaJson());
        assertNull(r.outputSchemaJson());
        assertNull(r.createdBy());
        assertNull(r.updatedBy());
        assertNull(r.economyProviderName());
        assertNull(r.costRoutingJevThreshold());
        assertNull(r.edgeRoutingPolicy());
    }
}
