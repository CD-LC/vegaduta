package com.agenticai.core.agents.skills.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.skills.SkillLearningGateway;
import com.agenticai.core.agents.skills.SkillLifecycleService;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The loop exists only when asked for: no property, no bean, no hook on the chat turn. */
class SkillLearningWiringTest {

    @Configuration
    @Import({SkillLearningProperties.class, SkillLearningService.class})
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
        SkillService skillService() {
            return mock(SkillService.class);
        }

        @Bean
        SkillLifecycleService skillLifecycleService() {
            return mock(SkillLifecycleService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

    @AfterEach
    void clear() {
        SkillLearningGateway.install(null);
    }

    @Test
    void withNoPropertyThereIsNoLearningBeanAndNoHook() {
        runner.run(
                ctx -> {
                    assertEquals(0, ctx.getBeanNamesForType(SkillLearningService.class).length);
                    assertFalse(SkillLearningGateway.active());
                    assertFalse(ctx.getBean(SkillLearningProperties.class).enabled());
                });
    }

    @Test
    void explicitlyOffIsTheSameAsAbsent() {
        runner.withPropertyValues("app.skills.learning.enabled=false")
                .run(
                        ctx -> {
                            assertEquals(0, ctx.getBeanNamesForType(SkillLearningService.class).length);
                            assertFalse(SkillLearningGateway.active());
                        });
    }

    @Test
    void switchedOnItInstallsTheHookAndRemovesItAgainOnShutdown() {
        runner.withPropertyValues("app.skills.learning.enabled=true")
                .run(
                        ctx -> {
                            assertEquals(1, ctx.getBeanNamesForType(SkillLearningService.class).length);
                            assertTrue(SkillLearningGateway.active());
                        });
        assertFalse(SkillLearningGateway.active(), "closing the context uninstalls the hook");
    }

    @Test
    void tuningValuesAreClampedToSaneRanges() {
        runner.withPropertyValues(
                        "app.skills.learning.enabled=true",
                        "app.skills.learning.min-tool-calls=0",
                        "app.skills.learning.max-transcript-chars=99999999",
                        "app.skills.learning.max-drafts-per-agent-per-day=-4",
                        "app.skills.learning.cooldown-minutes=-1")
                .run(
                        ctx -> {
                            SkillLearningProperties p = ctx.getBean(SkillLearningProperties.class);
                            assertTrue(p.minToolCalls() >= 2);
                            assertTrue(p.maxTranscriptChars() <= 20_000);
                            assertTrue(p.maxDraftsPerAgentPerDay() >= 1);
                            assertTrue(p.cooldownMinutes() >= 0);
                        });
    }
}
