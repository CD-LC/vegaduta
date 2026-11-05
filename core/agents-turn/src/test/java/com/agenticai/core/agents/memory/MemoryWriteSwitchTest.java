package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.graphmemory.EntityExtractionService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * memory.write.enabled: unchanged by default; off = the remember tools answer "disabled" and write
 * nothing.
 */
class MemoryWriteSwitchTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SettingsResolver settings = mock(SettingsResolver.class);
    private final UUID agentId = UUID.randomUUID();
    private AgentMemoryService agentMemory;
    private TenantMemoryService tenantMemory;

    @BeforeEach
    void setUp() {
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f});
        agentMemory = new AgentMemoryService(sidecar, jdbc);
        tenantMemory = new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true);
    }

    private void writesOff() {
        when(settings.getBool(
                        AgentMemoryService.WRITE_ENABLED_KEY, SettingContext.forAgent(agentId)))
                .thenReturn(false);
        agentMemory.setSettingsResolver(settings);
        tenantMemory.setSettingsResolver(settings);
    }

    @Test
    void defaultIsOnAndTheToolWritesAsBefore() {
        assertThat(agentMemory.writesSwitchedOff(agentId)).isFalse();
        assertThat(tenantMemory.writesSwitchedOff(agentId)).isFalse();

        String out =
                new RememberFactToolCallback(agentMemory, agentId, "alice")
                        .call("{\"fact\":\"Prefers tea\"}");

        assertThat(out).contains("\"remembered\": true");
        verify(jdbc)
                .update(org.mockito.ArgumentMatchers.contains("INSERT INTO"), any(Object[].class));
    }

    @Test
    void platformValueOffIsHonouredWithoutACatalog() {
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setWriteEnabled(false);
        agentMemory.setLimits(limits);

        assertThat(agentMemory.writesSwitchedOff(agentId)).isTrue();
        assertThat(agentMemory.writesSwitchedOffByPlatform()).isTrue();
    }

    @Test
    void offMakesRememberFactAnswerDisabledAndWriteNothing() {
        writesOff();
        GraphMemoryService graph = mock(GraphMemoryService.class);
        EntityExtractionService extractor = mock(EntityExtractionService.class);

        String out =
                new RememberFactToolCallback(
                                agentMemory,
                                agentId,
                                "alice",
                                graph,
                                extractor,
                                null,
                                mock(ChatClient.class),
                                null)
                        .call("{\"fact\":\"Prefers tea\"}");

        assertThat(out)
                .contains("\"skipped\": true")
                .contains(MemoryWriteSwitch.REASON)
                .doesNotContain("tea");
        assertThat(MemoryWriteSwitch.isSkippedResult(out)).isTrue();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(sidecar, graph, extractor);
    }

    @Test
    void offMakesRememberSharedFactAnswerDisabledAndWriteNothing() {
        writesOff();

        String out =
                new RememberSharedFactToolCallback(tenantMemory, agentId, "alice")
                        .call("{\"fact\":\"On the Enterprise plan\"}");

        assertThat(MemoryWriteSwitch.isSkippedResult(out)).isTrue();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aFailingLookupLeavesWritesAsThePlatformHasThem() {
        when(settings.getBool(anyString(), any(SettingContext.class)))
                .thenThrow(new IllegalStateException("down"));
        agentMemory.setSettingsResolver(settings);

        assertThat(agentMemory.writesSwitchedOff(agentId)).isFalse();
    }

    @Test
    void sessionSwitchIsMarkedAndCleared() {
        SessionWriteSwitch.mark("c1:abc", true);
        assertThat(SessionWriteSwitch.writesOff("c1:abc")).isTrue();
        assertThat(SessionWriteSwitch.writesOff("c1:other")).isFalse();
        SessionWriteSwitch.mark("c1:abc", false);
        assertThat(SessionWriteSwitch.writesOff("c1:abc")).isFalse();
    }
}
