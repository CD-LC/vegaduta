package com.agenticai.core.agents.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The store exists only when asked for: no property, no service, no controller, no appliers. */
class LearningProposalWiringTest {

    @Configuration
    @Import({
        LearningProposalService.class,
        LearningProposalController.class,
        LessonApplier.class,
        PreferenceApplier.class
    })
    static class Wiring {
        @Bean
        LearningProposalRepository learningProposalRepository() {
            return mock(LearningProposalRepository.class);
        }

        @Bean
        AgentService agentService() {
            return mock(AgentService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @Test
    void withNoPropertyNothingIsCreated() {
        runner.run(
                ctx -> {
                    assertEquals(0, ctx.getBeanNamesForType(LearningProposalService.class).length);
                    assertEquals(0, ctx.getBeanNamesForType(LearningProposalController.class).length);
                    assertEquals(0, ctx.getBeanNamesForType(LearningProposalApplier.class).length);
                });
    }

    @Test
    void switchedOnItRegistersTheServiceControllerAndBothAppliers() {
        runner.withPropertyValues("app.learning.proposals.enabled=true")
                .run(
                        ctx -> {
                            assertEquals(1, ctx.getBeanNamesForType(LearningProposalService.class).length);
                            assertEquals(1, ctx.getBeanNamesForType(LearningProposalController.class).length);
                            assertEquals(2, ctx.getBeanNamesForType(LearningProposalApplier.class).length);
                            LearningProposalService s = ctx.getBean(LearningProposalService.class);
                            assertEquals(true, s.canApply(LearningProposalKind.LESSON));
                            assertEquals(true, s.canApply(LearningProposalKind.PREFERENCE));
                            assertEquals(false, s.canApply(LearningProposalKind.ROUTINE));
                        });
    }
}
