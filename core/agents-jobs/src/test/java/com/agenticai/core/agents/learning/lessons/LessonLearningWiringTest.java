package com.agenticai.core.agents.learning.lessons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.skills.LessonLearningGateway;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The loop exists only when both flags are on: otherwise no bean and no hook on the chat turn. */
class LessonLearningWiringTest {

    @Configuration
    @Import(LessonLearningService.class)
    static class Wiring {
        @Bean
        ToolCallLogService toolCallLogService() {
            return mock(ToolCallLogService.class);
        }

        @Bean
        AgentLabCompletionService agentLabCompletionService() {
            return mock(AgentLabCompletionService.class);
        }

        @Bean
        LearningProposalService learningProposalService() {
            return mock(LearningProposalService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @AfterEach
    void clear() {
        LessonLearningGateway.install(null);
    }

    @Test
    void withNoPropertyThereIsNoBeanAndNoHook() {
        runner.run(
                ctx -> {
                    assertEquals(0, ctx.getBeanNamesForType(LessonLearningService.class).length);
                    assertFalse(LessonLearningGateway.active());
                });
    }

    @Test
    void theLessonsFlagAloneIsNotEnough() {
        runner.withPropertyValues("app.learning.lessons.enabled=true")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(LessonLearningService.class).length));
        runner.withPropertyValues("app.learning.proposals.enabled=true")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(LessonLearningService.class).length));
    }

    @Test
    void bothFlagsOnInstallTheHookAndShutdownRemovesIt() {
        runner.withPropertyValues("app.learning.lessons.enabled=true", "app.learning.proposals.enabled=true")
                .run(
                        ctx -> {
                            assertEquals(1, ctx.getBeanNamesForType(LessonLearningService.class).length);
                            assertTrue(LessonLearningGateway.active());
                        });
        assertFalse(LessonLearningGateway.active());
    }
}
