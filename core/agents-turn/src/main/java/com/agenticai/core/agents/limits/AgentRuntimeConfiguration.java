package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AgentRuntimeProperties} and the one {@link AgentRuntimeSettingsResolver}. The platform
 * values that live under other modules' keys are read here, once, from those keys with the defaults they
 * always had; the property classes the other modules own are read as beans (absent bean = that module's code
 * defaults, the same values).
 */
@Configuration
@EnableConfigurationProperties(AgentRuntimeProperties.class)
class AgentRuntimeConfiguration {

    @Bean
    AgentRuntimeSettingsResolver agentRuntimeSettingsResolver(
            AgentRuntimeProperties properties,
            ObjectProvider<ToolStepLimitProperties> stepLimits,
            ObjectProvider<ToolOutputProperties> toolOutput,
            ObjectProvider<ToolLoopProperties> toolLoop,
            TenantRuntimeLimitsService tenantLimits,
            AgentService agents,
            ObjectProvider<SettingsResolver> settings,
            // app.guardrails.max-tool-calls-per-turn: the base ceiling the autonomy tiers scale.
            @Value("${app.guardrails.max-tool-calls-per-turn:15}") int maxToolCallsPerTurn,
            // app.llm.http.buffered-turn-timeout: the wall-clock limit of a CAREFUL/RIGOROUS/PLANNED turn.
            @Value("${app.llm.http.buffered-turn-timeout:240s}") Duration bufferedTurnTimeout,
            // app.chat.continuation.max-passes: extra passes after a reply that stopped at an announced step.
            @Value("${app.chat.continuation.max-passes:8}") int continuationPasses) {
        AgentRuntimeSettingsResolver resolver = new AgentRuntimeSettingsResolver(
                properties,
                new AgentRuntimeSettingsResolver.PlatformKeys(
                        maxToolCallsPerTurn, bufferedTurnTimeout, continuationPasses),
                stepLimits.getIfAvailable(ToolStepLimitProperties::new),
                toolOutput.getIfAvailable(ToolOutputProperties::new),
                toolLoop.getIfAvailable(ToolLoopProperties::new),
                tenantLimits,
                agents);
        // The catalog reads this resolver for the platform values of the runtime limits, so it is asked lazily.
        resolver.useSettings(settings::getIfAvailable);
        return resolver;
    }
}
