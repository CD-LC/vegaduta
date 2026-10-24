package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.limits.RuntimeLimitsSettings;
import com.agenticai.core.settings.SettingBackend;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingSource;
import com.agenticai.core.settings.SettingValue;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.settings.SettingsService;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The settings catalog inside the real application context: every module's contributor is collected, the platform
 * values come from the real application.yml through the real property beans, the runtime-limit backend is registered
 * and the API routes are mapped.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SettingsWiringIT extends AbstractContextIT {

    @Autowired private SettingsRegistry registry;
    @Autowired private SettingsResolver resolver;
    @Autowired private SettingsService service;
    @Autowired private List<SettingBackend> backends;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyModulesEntriesAreCollected() {
        // A sanity range, not an exact count: the floor catches a contributor that was not collected, the ceiling a
        // contributor registered twice. The catalog grows with every module that publishes settings (48 on 2026-10-23).
        assertThat(registry.all().size()).isBetween(30, 80);
        assertThat(registry.find("agent.limits.maxToolSteps")).isPresent();
        assertThat(registry.find("delegation.maxBranches")).as("agents-interop contributor").isPresent();
        assertThat(registry.find("quality.judge.model")).as("quality contributor").isPresent();
        assertThat(registry.find("guardrails.failMode")).as("agents-turn contributor").isPresent();
        assertThat(registry.find("knowledge.search.candidatePoolMax")).as("knowledge contributor").isPresent();
        assertThat(registry.find("memory.graph.recallMaxFacts")).as("graphmemory contributor").isPresent();
        assertThat(registry.find("rl.policy.ucb1ExplorationConstant")).as("rl contributor").isPresent();
        assertThat(registry.find("providers.credential.rejectedCooldown")).as("providers contributor").isPresent();
        assertThat(backends).hasAtLeastOneElementOfType(RuntimeLimitsSettings.class);
    }

    @Test
    void platformValuesComeFromTheRealYmlThroughThePropertyBeans() {
        SettingValue v = resolver.get("chat.finish.maxContinuations", SettingContext.tenant());
        assertThat(v.value()).isEqualTo(2L);
        assertThat(v.source()).isEqualTo(SettingSource.PLATFORM);
        assertThat(resolver.getInt("chat.retry.maxAttempts", SettingContext.tenant())).isEqualTo(2);
        assertThat(resolver.getInt("knowledge.search.candidatePoolMultiplier", SettingContext.tenant())).isEqualTo(4);
        assertThat(resolver.getInt("memory.graph.recallMaxPaths", SettingContext.tenant())).isEqualTo(200);
        assertThat(resolver.getDuration("providers.credential.rejectedCooldown", SettingContext.tenant()))
            .isEqualTo(java.time.Duration.ofHours(6));
        assertThat(resolver.getEnum("guardrails.failMode", SettingContext.tenant(), com.agenticai.core.guardrails.GuardrailFailMode.class))
            .isEqualTo(com.agenticai.core.guardrails.GuardrailFailMode.CLOSED);
        // no TenantContext on this thread: platform values only, and the runtime-limit backend is not asked
        assertThat(resolver.get("agent.limits.maxToolSteps", SettingContext.tenant()).value()).isEqualTo(15L);
    }

    @Test
    void theApiRoutesAreMapped() {
        List<String> routes = mappings.getHandlerMethods().keySet().stream()
            .map(Object::toString).collect(Collectors.toList());
        assertThat(routes).anyMatch(r -> r.contains("/api/settings/catalog"));
        assertThat(routes).anyMatch(r -> r.contains("/api/settings/effective"));
        assertThat(routes).anyMatch(r -> r.contains("/api/settings/tenant/{key}"));
        assertThat(routes).anyMatch(r -> r.contains("/api/settings/{key}/history"));
        assertThat(routes).anyMatch(r -> r.contains("/api/agents/{agentId}/settings/{key}"));
        assertThat(service).isNotNull();
    }
}
