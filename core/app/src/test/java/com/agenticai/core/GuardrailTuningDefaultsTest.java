package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.approval.ApprovalExpiryProperties;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The guardrail scan windows and judge settings ({@code app.guardrails.judge|jailbreak|toxicity|tool-verifier}),
 * the approval expiry ({@code app.approvals.expiry}) and the agent-job constants that became settings are
 * bound from the REAL application.yml, and what the yml says equals the code defaults, so the two cannot
 * drift. Behaviour-preserving defaults are pinned to the values that used to be hard-coded.
 */
class GuardrailTuningDefaultsTest {

    private static Binder binderOverApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void theGuardrailTuningYmlBindsAndEqualsTheOldHardCodedValues() throws Exception {
        GuardrailTuningProperties p =
            binderOverApplicationYml().bind("app.guardrails", GuardrailTuningProperties.class).get();
        GuardrailTuningProperties code = new GuardrailTuningProperties();

        assertThat(p.getJudge().getMaxInputChars()).isEqualTo(code.getJudge().getMaxInputChars()).isEqualTo(4000);
        assertThat(p.getJudge().getTruncationMarker()).isEqualTo(code.getJudge().getTruncationMarker());
        assertThat(p.getJudge().getTemperature()).as("empty = inherit the agent's own").isNull();
        assertThat(p.getJudge().getMaxTokens()).isNull();
        assertThat(p.getJudge().getModel()).isNullOrEmpty();
        assertThat(p.getJudge().overridesCall()).isFalse();
        assertThat(p.getJailbreak().getMaxScannedChars()).isEqualTo(16_000);
        assertThat(p.getToxicity().getBlockScore()).isEqualTo(60);
        assertThat(p.getToolVerifier().getUnavailableReason()).isEqualTo(code.getToolVerifier().getUnavailableReason());
        assertThat(p.getToolVerifier().getFailMode()).isEqualTo(GuardrailTuningProperties.ToolVerifier.FailMode.INHERIT);
        assertThat(p.getSupervised().isGateReadOnly())
            .as("the base value gates every call of a supervised agent; only the personal profile turns it off")
            .isEqualTo(code.getSupervised().isGateReadOnly())
            .isTrue();
    }

    @Test
    void theApprovalExpiryYmlBindsAndIsOffByDefault() throws Exception {
        ApprovalExpiryProperties fromYml =
            binderOverApplicationYml().bind("app.approvals.expiry", ApprovalExpiryProperties.class).get();
        ApprovalExpiryProperties code = new ApprovalExpiryProperties();

        assertThat(fromYml.isEnabled()).isFalse();
        assertThat(fromYml.getTtl()).isEqualTo(Duration.ZERO);
        assertThat(fromYml.getSweepInterval()).isEqualTo(code.getSweepInterval());
        assertThat(fromYml.getInitialDelay()).isEqualTo(code.getInitialDelay());
        assertThat(fromYml.getBatchSize()).isEqualTo(code.getBatchSize());
        assertThat(fromYml.getExpiredReason()).isEqualTo(code.getExpiredReason());
    }

    @Test
    void theAgentJobConstantsThatBecameSettingsKeepTheirValues() throws Exception {
        AgentJobProperties fromYml = new AgentJobProperties();
        binderOverApplicationYml().bind("app.agent-jobs", Bindable.ofInstance(fromYml));
        AgentJobProperties code = new AgentJobProperties();

        assertThat(fromYml.getQuiesceWorkerWait()).isEqualTo(code.getQuiesceWorkerWait()).isEqualTo(Duration.ofSeconds(3));
        assertThat(fromYml.getApproval().getReplayGrace())
            .isEqualTo(code.getApproval().getReplayGrace())
            .isEqualTo(Duration.ofSeconds(90));
        assertThat(fromYml.getApproval().getExpiredFailureMessage())
            .isEqualTo(code.getApproval().getExpiredFailureMessage());
    }
}
