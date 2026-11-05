package com.agenticai.core.agents.learning.episodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.memory.EpisodicMemoryProperties;
import com.agenticai.core.agents.skills.EpisodeRecordingGateway;
import com.agenticai.core.agents.tools.ToolCallLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The recorder exists only when {@code app.memory.episodic.enabled=true}; otherwise no bean and no
 * hook on the turn.
 */
class EpisodeRecorderWiringTest {

    @Configuration
    @Import(EpisodeRecorder.class)
    static class Wiring {
        @Bean
        ToolCallLogService toolCallLogService() {
            return mock(ToolCallLogService.class);
        }

        @Bean
        EpisodicMemoryProperties episodicMemoryProperties() {
            EpisodicMemoryProperties p = new EpisodicMemoryProperties();
            p.setEnabled(true);
            return p;
        }
    }

    @AfterEach
    void uninstall() {
        EpisodeRecordingGateway.install(null);
    }

    @Test
    void offByDefaultThereIsNoBeanAndNoGateway() {
        new ApplicationContextRunner()
                .withUserConfiguration(Wiring.class)
                .run(
                        ctx -> {
                            assertTrue(ctx.getBeansOfType(EpisodeRecorder.class).isEmpty());
                            assertFalse(EpisodeRecordingGateway.installed());
                        });
    }

    @Test
    void withTheFlagOnTheGatewayIsInstalled() {
        new ApplicationContextRunner()
                .withUserConfiguration(Wiring.class)
                .withPropertyValues("app.memory.episodic.enabled=true")
                .run(
                        ctx -> {
                            assertTrue(ctx.getBeansOfType(EpisodeRecorder.class).size() == 1);
                            assertTrue(EpisodeRecordingGateway.installed());
                        });
    }
}
