package com.agenticai.core.agents.learning.routines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.agents.skills.RoutineObservationGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The loop exists only when both flags are on: no flag, no bean, no hook on the chat turn. */
class RoutineLearningWiringTest {

    @Configuration
    @Import({RoutineLearningService.class, RoutineApplier.class})
    static class Wiring {
        @Bean
        RoutineObservationRepository routineObservationRepository() {
            return mock(RoutineObservationRepository.class);
        }

        @Bean
        LearningProposalService learningProposalService() {
            return mock(LearningProposalService.class);
        }

        @Bean
        ScheduledTaskService scheduledTaskService() {
            return mock(ScheduledTaskService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @AfterEach
    void clear() {
        RoutineObservationGateway.install(null);
    }

    @Test
    void withNoFlagNothingIsCreatedAndTheTurnIsUntouched() {
        runner.run(
                ctx -> {
                    assertEquals(0, ctx.getBeanNamesForType(RoutineLearningService.class).length);
                    assertEquals(0, ctx.getBeanNamesForType(RoutineApplier.class).length);
                    assertFalse(RoutineObservationGateway.active());
                });
    }

    @Test
    void proposalsAloneDoNotStartTheLoop() {
        runner.withPropertyValues("app.learning.proposals.enabled=true")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(RoutineLearningService.class).length));
    }

    @Test
    void routinesAloneDoNotStartTheLoop() {
        runner.withPropertyValues("app.learning.routines.enabled=true")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(RoutineApplier.class).length));
    }

    @Test
    void bothFlagsInstallTheObserverAndTheApplier() {
        runner.withPropertyValues(
                        "app.learning.proposals.enabled=true", "app.learning.routines.enabled=true")
                .run(
                        ctx -> {
                            assertEquals(1, ctx.getBeanNamesForType(RoutineLearningService.class).length);
                            assertEquals(1, ctx.getBeanNamesForType(RoutineApplier.class).length);
                            assertTrue(RoutineObservationGateway.active());
                        });
        assertFalse(RoutineObservationGateway.active()); // closing the context uninstalls it
    }
}
