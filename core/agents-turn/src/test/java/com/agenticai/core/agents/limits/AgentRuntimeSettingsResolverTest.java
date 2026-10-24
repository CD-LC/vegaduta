package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.RuntimeLayer;
import com.agenticai.core.agents.runtime.RuntimeSetting;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Precedence of the runtime-limit layers (platform < tenant < agent < request), the autonomy-tier scaling of the
 * tool-step base, the bounds on write and on read, and the derived views (tool-output config, loop round cap).
 * Platform values are the shipped ones: 15 calls, a 240 s buffered turn, 20,000 characters, 8 continuation passes.
 */
class AgentRuntimeSettingsResolverTest {

    private static final UUID AGENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AgentRuntimeProperties properties = new AgentRuntimeProperties();
    private final ToolStepLimitProperties stepLimits = new ToolStepLimitProperties();
    private final ToolOutputProperties output = new ToolOutputProperties();
    private final ToolLoopProperties loop = new ToolLoopProperties();
    private final TenantRuntimeLimitsService tenantService = mock(TenantRuntimeLimitsService.class);
    private final AgentService agents = mock(AgentService.class);

    private AgentRuntimeSettingsResolver resolver() {
        return new AgentRuntimeSettingsResolver(
                properties,
                new AgentRuntimeSettingsResolver.PlatformKeys(15, Duration.ofSeconds(240), 8),
                stepLimits,
                output,
                loop,
                tenantService,
                agents);
    }

    private static final AgentAutonomyTier SEMI = AgentAutonomyTier.SEMI_AUTONOMOUS;

    @Test
    void withNothingOverriddenEveryValueIsTheShippedPlatformValue() {
        AgentRuntimeSettings s = resolver().platform(SEMI);

        assertEquals(15, s.maxToolSteps());
        assertEquals(Duration.ofSeconds(240), s.turnTimeout());
        assertEquals(Duration.ZERO, s.toolTimeout(), "no per-call limit unless one is configured");
        assertEquals(20_000, s.toolOutputMaxChars());
        assertEquals(3, s.retrievalTopK());
        assertEquals(0.0, s.retrievalMinScore());
        assertEquals(8, s.continuationPasses());
        for (RuntimeSetting setting : RuntimeSetting.values()) {
            assertEquals(RuntimeLayer.PLATFORM, s.layerOf(setting));
        }
        assertTrue(!s.turnTimeoutOverridden() && !s.hasToolTimeout());
    }

    @Test
    void theAutonomyTierScalesThePlatformCeilingAsItAlwaysDid() {
        assertEquals(7, resolver().platform(AgentAutonomyTier.SUPERVISED).maxToolSteps());
        assertEquals(15, resolver().platform(SEMI).maxToolSteps());
        assertEquals(30, resolver().platform(AgentAutonomyTier.FULLY_AUTONOMOUS).maxToolSteps());
    }

    @Test
    void tenantBeatsPlatformAndAgentBeatsTenant() {
        RuntimeLimits tenant = new RuntimeLimits(40, 300, 20, 30_000, 5, 0.2, 4);
        RuntimeLimits agent = new RuntimeLimits(60, 900, null, 50_000, 8, null, 2);

        AgentRuntimeSettings s = resolver().resolve(SEMI, tenant, agent, null);

        assertEquals(60, s.maxToolSteps());
        assertEquals(Duration.ofSeconds(900), s.turnTimeout());
        assertEquals(Duration.ofSeconds(20), s.toolTimeout(), "the agent left it unset: the tenant's value stands");
        assertEquals(50_000, s.toolOutputMaxChars());
        assertEquals(8, s.retrievalTopK());
        assertEquals(0.2, s.retrievalMinScore());
        assertEquals(2, s.continuationPasses());
        assertEquals(RuntimeLayer.AGENT, s.layerOf(RuntimeSetting.MAX_TOOL_STEPS));
        assertEquals(RuntimeLayer.TENANT, s.layerOf(RuntimeSetting.TOOL_TIMEOUT_SECONDS));
        assertEquals(RuntimeLayer.TENANT, s.layerOf(RuntimeSetting.RETRIEVAL_MIN_SCORE));
        assertTrue(s.turnTimeoutOverridden());
        assertTrue(s.hasToolTimeout());
    }

    @Test
    void theTenantBaseIsScaledByTheTierButTheAgentsOwnCeilingIsNot() {
        RuntimeLimits tenant = RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, 40);
        RuntimeLimits agent = RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, 40);

        assertEquals(20, resolver().resolve(AgentAutonomyTier.SUPERVISED, tenant, null, null).maxToolSteps());
        assertEquals(80, resolver().resolve(AgentAutonomyTier.FULLY_AUTONOMOUS, tenant, null, null).maxToolSteps());
        assertEquals(40, resolver().resolve(AgentAutonomyTier.SUPERVISED, null, agent, null).maxToolSteps());
        assertEquals(40, resolver().resolve(AgentAutonomyTier.FULLY_AUTONOMOUS, null, agent, null).maxToolSteps());
    }

    @Test
    void aRequestCapOnlyTightens() {
        RuntimeLimits agent = RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, 40);

        AgentRuntimeSettings lower = resolver().resolve(SEMI, null, agent, RuntimeLimits.capToolSteps(5));
        AgentRuntimeSettings higher = resolver().resolve(SEMI, null, agent, RuntimeLimits.capToolSteps(100));

        assertEquals(5, lower.maxToolSteps());
        assertEquals(RuntimeLayer.REQUEST, lower.layerOf(RuntimeSetting.MAX_TOOL_STEPS));
        assertEquals(40, higher.maxToolSteps(), "a job asking for 100 cannot raise the agent's 40");
        assertEquals(RuntimeLayer.AGENT, higher.layerOf(RuntimeSetting.MAX_TOOL_STEPS));
    }

    @Test
    void aRequestCapTightensAnUnlimitedToolTimeoutAndRaisesTheMinScore() {
        RuntimeLimits cap = RuntimeLimits.none()
                .with(RuntimeSetting.TOOL_TIMEOUT_SECONDS, 30)
                .with(RuntimeSetting.RETRIEVAL_MIN_SCORE, 0.4);
        RuntimeLimits agent = RuntimeLimits.none().with(RuntimeSetting.RETRIEVAL_MIN_SCORE, 0.1);

        AgentRuntimeSettings s = resolver().resolve(SEMI, null, agent, cap);

        assertEquals(Duration.ofSeconds(30), s.toolTimeout());
        assertEquals(0.4, s.retrievalMinScore());
    }

    @Test
    void valuesStoredOutsideTheBoundsAreClampedWhenRead() {
        properties.getBounds().setMaxToolSteps(new AgentRuntimeProperties.Range(1, 100));
        RuntimeLimits tooMuch = new RuntimeLimits(5_000, 1, 99_999, 10, 999, 7.0, 99);

        AgentRuntimeSettings s = resolver().resolve(SEMI, null, tooMuch, null);

        assertEquals(100, s.maxToolSteps());
        assertEquals(Duration.ofSeconds(10), s.turnTimeout(), "bounds floor is 10 s");
        assertEquals(Duration.ofSeconds(1800), s.toolTimeout());
        assertEquals(500, s.toolOutputMaxChars());
        assertEquals(50, s.retrievalTopK());
        assertEquals(1.0, s.retrievalMinScore());
        assertEquals(16, s.continuationPasses());
    }

    @Test
    void aSubSecondPlatformTurnTimeoutKeepsItsPrecision() {
        AgentRuntimeSettingsResolver fast = new AgentRuntimeSettingsResolver(
                properties,
                new AgentRuntimeSettingsResolver.PlatformKeys(15, Duration.ofMillis(600), 8),
                stepLimits, output, loop, tenantService, agents);

        assertEquals(Duration.ofMillis(600), fast.platform(SEMI).turnTimeout(),
                "a test or demo profile may configure a turn timeout below a second");
    }

    @Test
    void theOperatorsPlatformValuesAreNeverClamped() {
        AgentRuntimeSettingsResolver wide = new AgentRuntimeSettingsResolver(
                properties,
                new AgentRuntimeSettingsResolver.PlatformKeys(1_000, Duration.ofSeconds(5), 30),
                stepLimits, output, loop, tenantService, agents);

        AgentRuntimeSettings s = wide.platform(SEMI);

        assertEquals(1_000, s.maxToolSteps());
        assertEquals(Duration.ofSeconds(5), s.turnTimeout());
        assertEquals(30, s.continuationPasses());
    }

    @Test
    void forAgentReadsTheTenantAndAgentLayersAndTheTier() {
        when(agents.getAutonomyTier(AGENT)).thenReturn(AgentAutonomyTier.SUPERVISED);
        when(tenantService.current()).thenReturn(RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, 40));
        when(agents.getRuntimeLimits(AGENT)).thenReturn(RuntimeLimits.none().with(RuntimeSetting.RETRIEVAL_TOP_K, 9));

        AgentRuntimeSettings s = resolver().forAgent(AGENT);

        assertEquals(20, s.maxToolSteps(), "tenant base 40, halved for SUPERVISED");
        assertEquals(9, s.retrievalTopK());
    }

    @Test
    void aTenantLayerThatCannotBeReadFallsBackToThePlatform() {
        when(agents.getAutonomyTier(AGENT)).thenReturn(SEMI);
        when(tenantService.current()).thenThrow(new IllegalStateException("no schema"));
        when(agents.getRuntimeLimits(AGENT)).thenReturn(RuntimeLimits.none());

        assertEquals(15, resolver().forAgent(AGENT).maxToolSteps());
    }

    @Test
    void validateNamesEveryOffendingSettingAndItsRange() {
        RuntimeLimits bad = new RuntimeLimits(0, 5, null, null, 51, 1.5, -1);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> resolver().validate(bad));

        String message = e.getReason();
        assertEquals(400, e.getStatusCode().value());
        assertTrue(message.contains("maxToolSteps must be between 1 and 500 (got 0)"), message);
        assertTrue(message.contains("turnTimeoutSeconds must be between 10 and 3600 (got 5)"), message);
        assertTrue(message.contains("retrievalTopK must be between 1 and 50 (got 51)"), message);
        assertTrue(message.contains("retrievalMinScore must be between 0.0 and 1.0 (got 1.5)"), message);
        assertTrue(message.contains("continuationPasses must be between 0 and 16 (got -1)"), message);
    }

    @Test
    void validateAcceptsTheBoundsThemselvesAndAnEmptyLayer() {
        resolver().validate(new RuntimeLimits(500, 3600, 1800, 200_000, 50, 1.0, 16));
        resolver().validate(new RuntimeLimits(1, 10, 1, 500, 1, 0.0, 0));
        resolver().validate(RuntimeLimits.none());
        resolver().validate(null);
    }

    @Test
    void theToolOutputConfigTakesTheTurnsCapAsTheDefaultAndKeepsPerToolCaps() {
        output.getPerTool().put("read_file", 90_000);
        AgentRuntimeSettings s = resolver().resolve(SEMI, null, RuntimeLimits.none().with(RuntimeSetting.TOOL_OUTPUT_MAX_CHARS, 1_000), null);

        ToolOutputProperties forTurn = resolver().toolOutput(s);

        assertEquals(1_000, forTurn.limitFor("web_search"));
        assertEquals(90_000, forTurn.limitFor("read_file"), "the operator's per-tool cap is kept");
        assertEquals(forTurn.getTruncationMarker(), output.getTruncationMarker());
        assertEquals(20_000, output.getMaxChars(), "the shared platform bean is untouched");
        assertSame(output, resolver().toolOutput(resolver().platform(SEMI)), "no override, no copy");
    }

    @Test
    void theRoundCapNeverFallsBelowThePlatformsAndRisesWithAHigherCeiling() {
        int platformRounds = loop.effectiveMaxRounds(15, stepLimits.getAutonomousMultiplier(), stepLimits.getGraceCalls());
        AgentRuntimeSettings small = resolver().resolve(SEMI, null, RuntimeLimits.capToolSteps(3), null);
        AgentRuntimeSettings big = resolver().resolve(SEMI, null, RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, 200), null);

        assertEquals(platformRounds, resolver().toolLoopRounds(small));
        assertEquals(200 + stepLimits.getGraceCalls() + 1, resolver().toolLoopRounds(big));
    }

    @Test
    void anExplicitRoundCapStillWins() {
        loop.setMaxRoundsPerInvocation(12);
        AgentRuntimeSettings big = resolver().resolve(SEMI, null, RuntimeLimits.none().with(RuntimeSetting.MAX_TOOL_STEPS, 200), null);

        assertEquals(12, resolver().toolLoopRounds(big));
    }
}
