package com.agenticai.core.agents.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.settings.SettingsRig;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingInvalidException;
import com.agenticai.core.settings.SettingRole;
import com.agenticai.core.settings.SettingScope;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code agent.steps.graceCalls}, {@code .supervisedDivisor}, {@code .supervisedMinSteps}, {@code .autonomousMultiplier}:
 * the numbers that turn the base tool-call ceiling into an agent's ceiling follow the agent's own or its tenant's
 * setting, {@code app.guardrails.step-limit.*} when there is none. Real resolver, real catalog, real store.
 */
class StepLimitSettingsTest {

    private static final String TENANT = "tenant-a";
    private final UUID agentId = UUID.randomUUID();
    private final UUID sibling = UUID.randomUUID();
    private final ToolStepLimitProperties platform = new ToolStepLimitProperties();
    private final AgentService agents = mock(AgentService.class);
    private final TenantRuntimeLimitsService tenantLimits = mock(TenantRuntimeLimitsService.class);
    private final SettingsRig rig = new SettingsRig(new StepLimitSettings(platform));

    @AfterEach
    void clear() {
        SettingsRig.clear();
    }

    private AgentRuntimeSettingsResolver resolver(boolean withCatalog) {
        when(tenantLimits.current()).thenReturn(RuntimeLimits.none());
        when(agents.getRuntimeLimits(agentId)).thenReturn(RuntimeLimits.none());
        when(agents.getRuntimeLimits(sibling)).thenReturn(RuntimeLimits.none());
        AgentRuntimeSettingsResolver r = new AgentRuntimeSettingsResolver(
                new AgentRuntimeProperties(),
                new AgentRuntimeSettingsResolver.PlatformKeys(15, Duration.ofSeconds(240), 8),
                platform,
                new ToolOutputProperties(),
                new ToolLoopProperties(),
                tenantLimits,
                agents);
        if (withCatalog) {
            r.useSettings(rig::resolver);
        }
        return r;
    }

    private AgentRuntimeSettings turn(AgentRuntimeSettingsResolver r, UUID id, AgentAutonomyTier tier) {
        when(agents.getAutonomyTier(id)).thenReturn(tier);
        return r.forAgent(id);
    }

    @Test
    void theEntriesAnswerThePropertyObjectTheGuardReadsAndRefuseValuesOutsideTheirBounds() {
        platform.setGraceCalls(7);
        platform.setSupervisedDivisor(3);
        platform.setSupervisedMinSteps(4);
        platform.setAutonomousMultiplier(2);
        java.util.Map<String, Object> expected = java.util.Map.of(
                StepLimitSettings.GRACE_CALLS, 7L,
                StepLimitSettings.SUPERVISED_DIVISOR, 3L,
                StepLimitSettings.SUPERVISED_MIN_STEPS, 4L,
                StepLimitSettings.AUTONOMOUS_MULTIPLIER, 2L);
        for (SettingDefinition d : new StepLimitSettings(platform).definitions()) {
            assertThat(d.platformValue()).as(d.key()).isEqualTo(expected.get(d.key()));
            assertThat(d.scopes()).containsExactlyInAnyOrder(SettingScope.TENANT, SettingScope.AGENT);
            // the tier numbers bound what an agent editor may do with maxToolSteps: only a workspace administrator moves them
            assertThat(d.minRole()).as(d.key() + " needs a workspace administrator").isEqualTo(SettingRole.TENANT_ADMIN);
            assertThat(d.min()).isNotNull();
            assertThat(d.max()).isNotNull();
            assertThatThrownBy(() -> d.normalize(d.max().longValue() + 1)).isInstanceOf(SettingInvalidException.class);
            assertThatThrownBy(() -> d.normalize(d.min().longValue() - 1)).isInstanceOf(SettingInvalidException.class);
        }
        assertThatThrownBy(() -> new StepLimitSettings(platform).definitions().get(1).normalize(0L))
                .as("a divisor of zero would divide the ceiling by nothing")
                .isInstanceOf(SettingInvalidException.class);
    }

    @Test
    void withNoOverrideEveryTierGetsExactlyWhatThePlatformGaveBefore() {
        AgentRuntimeSettingsResolver withCatalog = resolver(true);
        AgentRuntimeSettingsResolver without = resolver(false);
        for (AgentAutonomyTier tier : AgentAutonomyTier.values()) {
            SettingsRig.inTenant(TENANT, () -> {
                AgentRuntimeSettings a = turn(withCatalog, agentId, tier);
                AgentRuntimeSettings b = turn(without, agentId, tier);
                assertThat(a.maxToolSteps()).as(tier.name()).isEqualTo(b.maxToolSteps());
                assertThat(a.hasGraceCalls()).as("nothing differs from the platform, so nothing is carried").isFalse();
                assertThat(withCatalog.toolLoopRounds(a)).isEqualTo(without.toolLoopRounds(b));
            });
        }
        // the shipped numbers: half, floored at 3; as set; doubled
        assertThat(turn(without, agentId, AgentAutonomyTier.SUPERVISED).maxToolSteps()).isEqualTo(7);
        assertThat(turn(without, agentId, AgentAutonomyTier.SEMI_AUTONOMOUS).maxToolSteps()).isEqualTo(15);
        assertThat(turn(without, agentId, AgentAutonomyTier.FULLY_AUTONOMOUS).maxToolSteps()).isEqualTo(30);
    }

    @Test
    void aTenantsAutonomousMultiplierAppliesToItsFullyAutonomousAgentsOnly() {
        rig.tenant(TENANT, StepLimitSettings.AUTONOMOUS_MULTIPLIER, 4);
        AgentRuntimeSettingsResolver r = resolver(true);

        SettingsRig.inTenant(TENANT, () -> {
            assertThat(turn(r, agentId, AgentAutonomyTier.FULLY_AUTONOMOUS).maxToolSteps()).isEqualTo(60);
            assertThat(turn(r, sibling, AgentAutonomyTier.SEMI_AUTONOMOUS).maxToolSteps()).isEqualTo(15);
        });
        SettingsRig.inTenant("tenant-b", () -> assertThat(turn(r, agentId, AgentAutonomyTier.FULLY_AUTONOMOUS).maxToolSteps())
                .as("another tenant keeps the platform multiplier")
                .isEqualTo(30));
    }

    @Test
    void anAgentsOwnSupervisedFloorAndDivisorBeatItsTenantsAndSiblingsKeepTheTenants() {
        rig.tenant(TENANT, StepLimitSettings.SUPERVISED_DIVISOR, 5)
                .agent(TENANT, agentId, StepLimitSettings.SUPERVISED_DIVISOR, 1)
                .agent(TENANT, agentId, StepLimitSettings.SUPERVISED_MIN_STEPS, 20);
        AgentRuntimeSettingsResolver r = resolver(true);

        SettingsRig.inTenant(TENANT, () -> {
            // 15 / 1 = 15, floored at the agent's 20
            assertThat(turn(r, agentId, AgentAutonomyTier.SUPERVISED).maxToolSteps()).isEqualTo(20);
            // 15 / 5 = 3, floored at the platform's 3
            assertThat(turn(r, sibling, AgentAutonomyTier.SUPERVISED).maxToolSteps()).isEqualTo(3);
        });
    }

    @Test
    void theGraceOfTheTurnIsTheAgentsThenTheTenantsAndTheToolLoopBackstopOutlastsIt() {
        rig.tenant(TENANT, StepLimitSettings.GRACE_CALLS, 20).agent(TENANT, agentId, StepLimitSettings.GRACE_CALLS, 40);
        AgentRuntimeSettingsResolver r = resolver(true);

        SettingsRig.inTenant(TENANT, () -> {
            AgentRuntimeSettings own = turn(r, agentId, AgentAutonomyTier.SEMI_AUTONOMOUS);
            AgentRuntimeSettings tenants = turn(r, sibling, AgentAutonomyTier.SEMI_AUTONOMOUS);
            assertThat(own.graceCalls()).isEqualTo(40);
            assertThat(tenants.graceCalls()).isEqualTo(20);
            // the round cap is calls + grace + 1, so the loop cannot end the turn inside the grace the guard grants
            assertThat(r.toolLoopRounds(own)).isGreaterThanOrEqualTo(15 + 40 + 1);
            assertThat(r.toolLoopRounds(tenants)).isGreaterThanOrEqualTo(15 + 20 + 1);
        });
        SettingsRig.inTenant("tenant-b", () -> assertThat(turn(r, agentId, AgentAutonomyTier.SEMI_AUTONOMOUS).hasGraceCalls())
                .isFalse());
    }

    @Test
    void aTenantMayLowerTheGraceBelowThePlatformAndTheBackstopStaysAtLeastThePlatforms() {
        rig.tenant(TENANT, StepLimitSettings.GRACE_CALLS, 0);
        AgentRuntimeSettingsResolver r = resolver(true);
        AgentRuntimeSettingsResolver platformOnly = resolver(false);

        SettingsRig.inTenant(TENANT, () -> {
            AgentRuntimeSettings s = turn(r, agentId, AgentAutonomyTier.SEMI_AUTONOMOUS);
            assertThat(s.graceCalls()).isZero();
            assertThat(r.toolLoopRounds(s)).as("no agent is stopped sooner by the backstop than before")
                    .isGreaterThanOrEqualTo(platformOnly.toolLoopRounds(turn(platformOnly, agentId, AgentAutonomyTier.SEMI_AUTONOMOUS)));
        });
    }

    @Test
    void aSettingsOutageNeverStopsATurn() {
        AgentRuntimeSettingsResolver r = resolver(false);
        r.useSettings(() -> {
            throw new IllegalStateException("settings store down");
        });
        SettingsRig.inTenant(TENANT, () -> assertThat(turn(r, agentId, AgentAutonomyTier.FULLY_AUTONOMOUS).maxToolSteps()).isEqualTo(30));
    }
}
