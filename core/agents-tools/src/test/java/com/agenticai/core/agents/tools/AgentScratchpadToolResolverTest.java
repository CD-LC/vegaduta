package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.scratchpad.AgentScratchpadService;
import com.agenticai.core.agents.tools.scratchpad.ScratchpadProperties;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The scratchpad tools are offered to a chat turn only when {@code app.tools.scratchpad.enabled}
 * is on AND the service bean exists; the names come out in the declared order.
 */
class AgentScratchpadToolResolverTest {

    private AgentToolGuard toolGuard;

    @BeforeEach
    void setUp() {
        toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static ScratchpadProperties properties(boolean enabled) {
        ScratchpadProperties p = mock(ScratchpadProperties.class);
        when(p.isEnabled()).thenReturn(enabled);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentScratchpadService> providing(AgentScratchpadService svc) {
        ObjectProvider<AgentScratchpadService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(svc);
        return p;
    }

    private static List<String> namesOf(
            AgentScratchpadToolResolver resolver, UUID agentId, String sessionId) {
        return resolver
                .resolveScratchpadTools(
                        agentId, sessionId, new AtomicInteger(), null, null, false)
                .stream()
                .map(t -> t.getToolDefinition().name())
                .toList();
    }

    @Test
    void disabledPropertiesOrAnAbsentServiceOfferNothing() {
        AgentScratchpadService service = mock(AgentScratchpadService.class);
        AgentScratchpadToolResolver disabled =
                new AgentScratchpadToolResolver(
                        toolGuard, providing(service), properties(false), null);
        assertThat(namesOf(disabled, UUID.randomUUID(), "s1")).isEmpty();

        AgentScratchpadToolResolver noService =
                new AgentScratchpadToolResolver(toolGuard, providing(null), properties(true), null);
        assertThat(namesOf(noService, UUID.randomUUID(), "s1")).isEmpty();

        AgentScratchpadToolResolver noProperties =
                new AgentScratchpadToolResolver(toolGuard, providing(service), null, null);
        assertThat(namesOf(noProperties, UUID.randomUUID(), "s1")).isEmpty();
    }

    @Test
    void enabledWithAServiceOffersAllFourInDeclarationOrder() {
        AgentScratchpadToolResolver resolver =
                new AgentScratchpadToolResolver(
                        toolGuard, providing(mock(AgentScratchpadService.class)), properties(true), null);
        assertThat(namesOf(resolver, UUID.randomUUID(), "s1"))
                .containsExactly(
                        "scratchpad_write", "scratchpad_read", "scratchpad_list",
                        "scratchpad_delete");
    }

    @Test
    void noAgentOnTheTurnOffersNothing() {
        AgentScratchpadToolResolver resolver =
                new AgentScratchpadToolResolver(
                        toolGuard, providing(mock(AgentScratchpadService.class)), properties(true), null);
        assertThat(namesOf(resolver, null, "s1")).isEmpty();
    }
}
