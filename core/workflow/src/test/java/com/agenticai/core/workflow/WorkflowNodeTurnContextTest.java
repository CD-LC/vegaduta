package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.stream.NodeStreamContext;
import com.agenticai.core.workflow.stream.NodeStreamSink;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the workflow entries: the agent node (plain and live-stream branches) and the
 * supervisor node run on the workflow thread and hand {@code AgentChatService} a {@link
 * TurnContext} built from that thread's tenant. (The structured {@code chatWithParams} path is
 * covered next to {@code ChatStructuredCall} in agents-turn.)
 */
class WorkflowNodeTurnContextTest extends WorkflowNodeExecutorCharacterizationTestBase {

    private final TenantContext tenant =
            TenantContext.of("tenant_acme", "workflow-runner", Set.of("USER"));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        NodeStreamContext.clear();
    }

    private TurnContext captureTurnContext() {
        ArgumentCaptor<TurnContext> ctx = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(ctx.capture(), any(ChatTurnRequest.class));
        return ctx.getValue();
    }

    private static Map<String, Object> supervisorConfig() {
        return Map.of(
                "agentId",
                AGENT.toString(),
                "routes",
                List.of(Map.of("handle", "tech", "label", "Tech")));
    }

    @Test
    void agentNodeCarriesTheWorkflowThreadsTenant() {
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("ok"));
        TenantContext.set(tenant);

        run(node("agent", Map.of("agentId", AGENT.toString())), "hi");

        TurnContext context = captureTurnContext();
        assertSame(tenant, context.tenant());
        assertEquals("tenant_acme", context.tenantId());
        assertEquals(AGENT, context.requestedAgentId());
        assertEquals(RUN, context.sessionId());
    }

    @Test
    void agentNodeLiveStreamBranchCarriesTheWorkflowThreadsTenant() {
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("ok"));
        NodeStreamSink sink = mock(NodeStreamSink.class);
        when(sink.isLive()).thenReturn(true);
        NodeStreamContext.bind(sink);
        TenantContext.set(tenant);

        run(node("agent", Map.of("agentId", AGENT.toString())), "hi");

        assertSame(tenant, captureTurnContext().tenant());
    }

    @Test
    void agentNodeWithoutAThreadTenantBuildsATenantlessContext() {
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("ok"));

        run(node("agent", Map.of("agentId", AGENT.toString())), "hi");

        TurnContext context = captureTurnContext();
        assertNotNull(context);
        assertNull(context.tenant());
        assertNull(context.tenantId());
        assertEquals(AGENT, context.requestedAgentId());
    }

    @Test
    void supervisorNodeCarriesTheWorkflowThreadsTenant() {
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("tech"));
        TenantContext.set(tenant);

        run(node("supervisor", supervisorConfig()), "wifi down");

        TurnContext context = captureTurnContext();
        assertSame(tenant, context.tenant());
        assertEquals(AGENT, context.requestedAgentId());
    }

    @Test
    void supervisorNodeWithoutAThreadTenantBuildsATenantlessContext() {
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("tech"));

        run(node("supervisor", supervisorConfig()), "wifi down");

        assertNull(captureTurnContext().tenant());
    }
}
