package com.agenticai.core.agents.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.fasterxml.jackson.databind.node.TextNode;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Rejoining a run is scoped to the agent and the caller that started it: with a real buffer over a
 * fake Redis, someone who knows (or chooses) a runId still cannot read, or write into, a run on
 * another agent or one started by another user.
 */
class AgUiRunControllerRejoinScopeTest {

    private final UUID agentA = UUID.randomUUID();
    private final UUID agentB = UUID.randomUUID();
    private final Principal alice = () -> "alice";
    private final Principal bob = () -> "bob";

    private AgUiRunController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Map<String, List<String>> lists = new HashMap<>();
        Map<String, String> values = new HashMap<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOps = mock(ListOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(listOps.rightPush(anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            List<String> list =
                                    lists.computeIfAbsent(
                                            inv.getArgument(0), k -> new ArrayList<>());
                            list.add(inv.getArgument(1));
                            return (long) list.size();
                        });
        when(listOps.range(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(0L),
                        org.mockito.ArgumentMatchers.eq(-1L)))
                .thenAnswer(
                        inv -> {
                            List<String> list = lists.get(inv.getArgument(0));
                            return list == null ? null : new ArrayList<>(list);
                        });
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            values.put(inv.getArgument(0), inv.getArgument(1));
                            return null;
                        })
                .when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));
        when(redis.hasKey(anyString()))
                .thenAnswer(inv -> values.containsKey(inv.<String>getArgument(0)));

        AgentChatService chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(inv -> Flux.just("hello from the agent"));
        controller =
                new AgUiRunController(
                        mock(AgentService.class), chat, null, new AgUiRunEventBuffer(redis));
    }

    private static RunAgentInput input(String runId) {
        return new RunAgentInput(
                "thread-1",
                runId,
                null,
                null,
                List.of(
                        new RunAgentInput.InputMessage(
                                "m1", "user", new TextNode("hi"), null, null, null)),
                List.of(),
                List.of(),
                null);
    }

    private static List<ServerSentEvent<String>> frames(Flux<ServerSentEvent<String>> flux) {
        return flux.filter(e -> e.data() != null).collectList().block(Duration.ofSeconds(10));
    }

    private List<ServerSentEvent<String>> run(UUID agent, Principal who, String runId) {
        return frames(controller.run(agent, input(runId), who));
    }

    private List<ServerSentEvent<String>> rejoin(UUID agent, Principal who, String runId) {
        return frames(controller.events(agent, runId, 0L, null, who));
    }

    @Test
    void theCallerWhoStartedARunCanRejoinIt() {
        List<ServerSentEvent<String>> original = run(agentA, alice, "run-1");

        List<ServerSentEvent<String>> rejoined = rejoin(agentA, alice, "run-1");

        assertFalse(original.isEmpty());
        assertEquals(original.size(), rejoined.size());
        assertEquals(original.get(0).data(), rejoined.get(0).data());
    }

    @Test
    void aUserWithAccessToAnotherAgentCannotReadTheRunByItsId() {
        run(agentA, alice, "run-1");

        // Same user, same runId, but the path names an agent the run did not happen on.
        assertTrue(rejoin(agentB, alice, "run-1").isEmpty());
    }

    @Test
    void anotherUserOfTheSameAgentCannotReadTheRunByItsId() {
        run(agentA, alice, "run-1");

        assertTrue(rejoin(agentA, bob, "run-1").isEmpty());
    }

    @Test
    void twoUsersChoosingTheSameRunIdDoNotShareOrCorruptEachOthersBuffer() {
        List<ServerSentEvent<String>> alicesRun = run(agentA, alice, "run-1");
        run(agentA, bob, "run-1");

        assertEquals(alicesRun.size(), rejoin(agentA, alice, "run-1").size());
        assertEquals(alicesRun.size(), rejoin(agentA, bob, "run-1").size());
    }

    @Test
    void aRunIdThatCouldSpellAnotherRunsMarkerKeyIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.run(agentA, input("run-1:done"), alice));
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.events(agentA, "run-1:done", 0L, null, alice));
    }
}
