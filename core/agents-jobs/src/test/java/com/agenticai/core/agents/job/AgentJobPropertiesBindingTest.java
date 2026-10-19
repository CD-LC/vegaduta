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
                                "app.agent-jobs.durability.step-journal-purge-interval", "30m",
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
        assertThat(props.getDurability().getStepJournalPurgeInterval())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(props.getDurability().isRequeueZeroWorkOrphans()).isTrue();
        assertThat(props.getOwnership().isEnabled()).isTrue();
        assertThat(props.getSchedule().getMaxPerTenant()).isEqualTo(9);
    }
}
