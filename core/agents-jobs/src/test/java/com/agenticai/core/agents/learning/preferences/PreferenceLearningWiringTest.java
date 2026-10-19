package com.agenticai.core.agents.learning.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.skills.PreferenceSignalGateway;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The loop exists only when both it and the proposal store are switched on, and then it hooks the turn. */
class PreferenceLearningWiringTest {

    @Configuration
    @Import(PreferenceLearningService.class)
    static class Wiring {
        @Bean
        PreferenceSignalRepository preferenceSignalRepository() {
            return mock(PreferenceSignalRepository.class);
        }

        @Bean
        LearningProposalService learningProposalService() {
            return mock(LearningProposalService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    void offByDefault() {
        runner.run(ctx -> assertEquals(0, ctx.getBeanNamesForType(PreferenceLearningService.class).length));
    }

    @Test
    void needsTheProposalStoreToo() {
        runner.withPropertyValues("app.learning.preferences.enabled=true")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(PreferenceLearningService.class).length));
        runner.withPropertyValues("app.learning.proposals.enabled=true")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(PreferenceLearningService.class).length));
    }

    @Test
    void switchedOnItInstallsTheTurnHookAndRemovesItOnClose() {
        AtomicInteger asked = new AtomicInteger();
        runner.withPropertyValues(
                        "app.learning.proposals.enabled=true", "app.learning.preferences.enabled=true")
                .run(
                        ctx -> {
                            assertEquals(1, ctx.getBeanNamesForType(PreferenceLearningService.class).length);
                            // With no tenant on the turn nothing is queued, but the hook is reachable and quiet.
                            PreferenceSignalGateway.observe(UUID.randomUUID(), "s", "shorter", Optional.empty());
                            asked.incrementAndGet();
                        });
        assertTrue(asked.get() == 1);
        // Closed context: the hook is gone, observing is a no-op.
        PreferenceSignalGateway.observe(UUID.randomUUID(), "s", "shorter", Optional.empty());
    }
}
