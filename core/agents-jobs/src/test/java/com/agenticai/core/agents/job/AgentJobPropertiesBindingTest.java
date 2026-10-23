package com.agenticai.core.agents.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The {@code trigger.*} and {@code durability.*} blocks moved out of {@link AgentJobProperties}
 * into their own files; the {@code app.agent-jobs.*} keys must still bind exactly as before.
 */
class AgentJobPropertiesBindingTest {

    @Test
    void nestedBlocksStillBindUnderTheirOriginalKeys() {
        MapConfigurationPropertySource source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "app.agent-jobs.max-concurrent", "7",
                                "app.agent-jobs.trigger.enabled", "false",
                                "app.agent-jobs.trigger.max-fires-per-window", "33",
                                "app.agent-jobs.trigger.sustained-window", "2h",
                                "app.agent-jobs.trigger.max-goal-chars", "123",
                                "app.agent-jobs.durability.max-attempts", "5",
                                "app.agent-jobs.durability.step-journal-enabled", "true",
                                "app.agent-jobs.ownership.enabled", "true",
                                "app.agent-jobs.schedule.max-per-tenant", "9"));

        AgentJobProperties props = new AgentJobProperties();
        new Binder(source).bind("app.agent-jobs", Bindable.ofInstance(props));

        assertThat(props.getMaxConcurrent()).isEqualTo(7);
        assertThat(props.getTrigger().isEnabled()).isFalse();
        assertThat(props.getTrigger().getMaxFiresPerWindow()).isEqualTo(33);
        assertThat(props.getTrigger().getSustainedWindow()).isEqualTo(Duration.ofHours(2));
        assertThat(props.getTrigger().getMaxGoalChars()).isEqualTo(123);
        assertThat(props.getTrigger().getWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.getDurability().getMaxAttempts()).isEqualTo(5);
        assertThat(props.getDurability().isStepJournalEnabled()).isTrue();
        assertThat(props.getDurability().isRequeueZeroWorkOrphans()).isTrue();
        assertThat(props.getOwnership().isEnabled()).isTrue();
        assertThat(props.getSchedule().getMaxPerTenant()).isEqualTo(9);
    }

    @Test
    void theQuiesceWaitAndReplayGraceBindUnderTheirKeys() {
        AgentJobProperties props = new AgentJobProperties();
        new Binder(new MapConfigurationPropertySource(Map.of(
                "app.agent-jobs.quiesce-worker-wait", "10s",
                "app.agent-jobs.approval.replay-grace", "5m",
                "app.agent-jobs.approval.expired-failure-message", "expired {label}")))
            .bind("app.agent-jobs", Bindable.ofInstance(props));

        assertThat(props.getQuiesceWorkerWait()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.getApproval().getReplayGrace()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.getApproval().getExpiredFailureMessage()).isEqualTo("expired {label}");
    }

    @Test
    void theConstantsThatBecameSettingsKeepTheirOldValuesAsDefaults() {
        AgentJobProperties props = new AgentJobProperties();

        assertThat(props.getQuiesceWorkerWait()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.getApproval().getReplayGrace()).isEqualTo(Duration.ofSeconds(90));
        assertThat(props.getApproval().getExpiredFailureMessage()).contains("expired").contains("{label}");
    }
}
