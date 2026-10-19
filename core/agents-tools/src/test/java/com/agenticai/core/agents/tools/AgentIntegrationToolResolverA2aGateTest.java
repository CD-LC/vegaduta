package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.a2a.A2aRemoteAgentToolSource;
import com.agenticai.core.agents.core.AgentService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * a2a_call_agent is offered only when the outbound client bean exists, app.a2a.client.enabled is
 * true, the operator allow-listed at least one host, AND the agent explicitly named the tool in its
 * allow-list.
 */
class AgentIntegrationToolResolverA2aGateTest {

    private AgentIntegrationToolResolver resolver;
    private AgentService agentService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AgentToolGuard toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        agentService = mock(AgentService.class);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("a2a_call_agent"));
        resolver =
                new AgentIntegrationToolResolver(
                        toolGuard,
                        agentService,
                        List.of(),
                        null,
                        null,
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<A2aRemoteAgentToolSource> providing(
            A2aRemoteAgentToolSource svc) {
        ObjectProvider<A2aRemoteAgentToolSource> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(svc);
        return p;
    }

    private static A2aRemoteAgentToolSource service(boolean enabled, boolean hosts) {
        A2aRemoteAgentToolSource svc = mock(A2aRemoteAgentToolSource.class);
        when(svc.isEnabled()).thenReturn(enabled);
        when(svc.hasAllowedHosts()).thenReturn(hosts);
        ToolCallback tool = mock(ToolCallback.class);
        when(tool.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name(A2aRemoteAgentToolSource.TOOL_NAME)
                                .description("a2a")
                                .inputSchema("{}")
                                .build());
        when(svc.newCallAgentTool()).thenReturn(tool);
        return svc;
    }

    private boolean offered() {
        List<ToolCallback> tools =
                resolver.resolveWebTools(
                        UUID.randomUUID(), "s1", new AtomicInteger(), null, null, false);
        return tools.stream().anyMatch(t -> "a2a_call_agent".equals(t.getToolDefinition().name()));
    }

    @Test
    void notOfferedWhenNeverWired() {
        assertFalse(offered());
    }

    @Test
    void notOfferedWhenBeanAbsent() {
        resolver.setA2aClient(providing(null));
        assertFalse(offered());
    }

    @Test
    void notOfferedWhenDisabled() {
        resolver.setA2aClient(providing(service(false, true)));
        assertFalse(offered());
    }

    @Test
    void notOfferedWithEmptyHostAllowList() {
        resolver.setA2aClient(providing(service(true, false)));
        assertFalse(offered());
    }

    @Test
    void notOfferedToAnAgentThatDidNotOptIn() {
        resolver.setA2aClient(providing(service(true, true)));
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("fetch_web_page"));
        assertFalse(offered());
    }

    @Test
    void emptyAgentToolAllowListIsNotAnOptIn() {
        resolver.setA2aClient(providing(service(true, true)));
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        assertFalse(offered());
    }

    @Test
    void notOfferedWhenAllowListLookupFails() {
        resolver.setA2aClient(providing(service(true, true)));
        when(agentService.getAllowedToolNames(any()))
                .thenThrow(new IllegalStateException("no such agent"));
        assertFalse(offered());
    }

    @Test
    void offeredWhenEnabledWithHostsAndAgentOptedIn() {
        resolver.setA2aClient(providing(service(true, true)));
        assertTrue(offered());
    }

    @Test
    void flagOffAddsZeroToolsEvenForAnOptedInAgent() {
        int baseline =
                resolver.resolveWebTools(
                                UUID.randomUUID(), "s1", new AtomicInteger(), null, null, false)
                        .size();
        resolver.setA2aClient(providing(service(false, true)));
        List<ToolCallback> tools =
                resolver.resolveWebTools(
                        UUID.randomUUID(), "s1", new AtomicInteger(), null, null, false);
        assertTrue(
                tools.size() == baseline,
                "disabled client must add no tool (and so no tool-schema prompt tokens)");
        assertFalse(offered());
    }

    @Test
    void emptyAllowListAgentGetsNoA2aToolEvenWhenFeatureIsFullyEnabled() {
        resolver.setA2aClient(providing(service(true, true)));
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        List<ToolCallback> tools =
                resolver.resolveWebTools(
                        UUID.randomUUID(), "s1", new AtomicInteger(), null, null, false);
        assertTrue(
                tools.stream()
                        .noneMatch(t -> "a2a_call_agent".equals(t.getToolDefinition().name())));
    }
}
