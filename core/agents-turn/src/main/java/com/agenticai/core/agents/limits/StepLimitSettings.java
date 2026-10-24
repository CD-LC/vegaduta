package com.agenticai.core.agents.limits;

import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingRole;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The four numbers that decide how many tool calls a turn gets once the base ceiling is known, as catalog entries
 * ({@code agent.steps.*}): how many calls past the ceiling are answered with the budget-exhausted result before the
 * turn is stopped, and how the agent's autonomy tier scales the ceiling (a supervised agent gets the ceiling divided,
 * not below a floor; a fully autonomous one gets it multiplied). They read {@code app.guardrails.step-limit.*}, the
 * same {@link ToolStepLimitProperties} object the guard reads.
 *
 * <p>Use sites: {@link AgentRuntimeSettingsResolver#forAgent} applies the tier numbers (and carries the grace on the
 * settings of the turn); {@code AgentToolGuard} reads the grace from those settings when it builds the guard of a
 * turn. The base ceiling itself is {@code agent.limits.maxToolSteps}.
 *
 * <p>Each key needs a workspace administrator ({@code minRole TENANT_ADMIN}): these numbers define how far a supervised
 * agent's ceiling is tightened, so an agent editor, who may change {@code agent.limits.maxToolSteps} for an agent, must not
 * be able to loosen the tier itself.
 *
 * <p>Medium risk, not security: raising the multiplier lets a fully autonomous agent make more calls, which is the
 * same trade as raising {@code agent.limits.maxToolSteps}; the bounds below keep the product far inside what the
 * round backstop and the run budget already bound.
 */
@Component
public class StepLimitSettings implements SettingsContributor {

    static final String GRACE_CALLS = "agent.steps.graceCalls";
    static final String SUPERVISED_DIVISOR = "agent.steps.supervisedDivisor";
    static final String SUPERVISED_MIN_STEPS = "agent.steps.supervisedMinSteps";
    static final String AUTONOMOUS_MULTIPLIER = "agent.steps.autonomousMultiplier";

    private static final String GROUP = "agent-limits";
    private static final String ANCHOR = "agent-runtime-limits";

    private final ObjectProvider<ToolStepLimitProperties> limits;

    @Autowired
    public StepLimitSettings(ObjectProvider<ToolStepLimitProperties> limits) {
        this.limits = limits;
    }

    /** Over a given properties object (tests, embedded use). */
    public StepLimitSettings(ToolStepLimitProperties properties) {
        this.limits = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                        java.util.Map.of("stepLimits", properties))
                .getBeanProvider(ToolStepLimitProperties.class);
    }

    private ToolStepLimitProperties limits() {
        return limits.getIfAvailable(ToolStepLimitProperties::new);
    }

    @Override
    public List<SettingDefinition> definitions() {
        return List.of(
                def(GRACE_CALLS, 0, 50, SettingRisk.MEDIUM, "app.guardrails.step-limit.grace-calls")
                        .platform(() -> limits().getGraceCalls())
                        .build(),
                def(SUPERVISED_DIVISOR, 1, 10, SettingRisk.LOW, "app.guardrails.step-limit.supervised-divisor")
                        .platform(() -> limits().getSupervisedDivisor())
                        .build(),
                def(SUPERVISED_MIN_STEPS, 1, 50, SettingRisk.LOW, "app.guardrails.step-limit.supervised-min-steps")
                        .platform(() -> limits().getSupervisedMinSteps())
                        .build(),
                def(AUTONOMOUS_MULTIPLIER, 1, 5, SettingRisk.MEDIUM, "app.guardrails.step-limit.autonomous-multiplier")
                        .platform(() -> limits().getAutonomousMultiplier())
                        .build());
    }

    private static SettingDefinition.Builder def(String key, int min, int max, SettingRisk risk, String property) {
        return SettingDefinition.builder(key, GROUP, SettingType.INT)
                .unit(SettingUnit.COUNT)
                .range(min, max)
                .risk(risk)
                .scopes(SettingScope.TENANT, SettingScope.AGENT)
                // An agent editor must not be able to undo the tighter ceiling of a supervised agent (for example one the
                // trifecta enforcement downgraded to SUPERVISED) or widen the grace past it: a workspace administrator decides.
                .minRole(SettingRole.TENANT_ADMIN)
                .property(property)
                .docsAnchor(ANCHOR);
    }
}
