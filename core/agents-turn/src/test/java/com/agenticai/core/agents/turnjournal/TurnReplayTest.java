package com.agenticai.core.agents.turnjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class TurnReplayTest {

    private final UUID agent = UUID.randomUUID();

    private static ToolCallback tool(String name, java.util.function.Function<String, String> fn) {
        ToolDefinition def =
                ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return def;
            }

            public String call(String in) {
                return fn.apply(in);
            }
        };
    }

    private Map<String, ToolCallback> tools(AtomicInteger sideEffects) {
        Map<String, ToolCallback> m = new LinkedHashMap<>();
        m.put("lookup", tool("lookup", in -> "order 42 shipped"));
        m.put("notify", tool("notify", in -> { sideEffects.incrementAndGet(); return "sent"; }));
        return m;
    }

    private static final TurnReplay.ReplayModel SCRIPT =
            (msg, results) ->
                    switch (results.size()) {
                        case 0 -> TurnReplay.Step.call("lookup", "{\"order\":42}");
                        case 1 -> TurnReplay.Step.call("notify", "{\"to\":\"customer\"}");
                        default -> TurnReplay.Step.finish("Your order 42 shipped and you were notified.");
                    };

    @Test
    void replayingARecordedTurnReproducesTheToolSequenceAndTheAnswer() {
        InMemoryTurnJournal recordedJournal = new InMemoryTurnJournal();
        UUID recordedTurn =
                TurnReplay.run(recordedJournal, agent, "where is order 42?", SCRIPT, tools(new AtomicInteger()));
        List<TurnEvent> recorded = recordedJournal.read(agent, recordedTurn);

        InMemoryTurnJournal replayJournal = new InMemoryTurnJournal();
        UUID replayTurn =
                TurnReplay.run(replayJournal, agent, "where is order 42?", SCRIPT, tools(new AtomicInteger()));
        List<TurnEvent> replayed = replayJournal.read(agent, replayTurn);

        assertEquals(2, TurnReplay.toolSequence(recorded).size());
        assertTrue(TurnReplay.toolSequence(recorded).get(0).startsWith("lookup|"));
        assertEquals(TurnReplay.toolSequence(recorded), TurnReplay.toolSequence(replayed));
        assertTrue(TurnReplay.sameBehaviour(recorded, replayed));
        assertTrue(TurnHashChain.verify(recorded).intact());
        assertTrue(TurnHashChain.verify(replayed).intact());
    }

    @Test
    void aBehaviourChangeIsNotTheSameBehaviour() {
        InMemoryTurnJournal a = new InMemoryTurnJournal();
        UUID ta = TurnReplay.run(a, agent, "q", SCRIPT, tools(new AtomicInteger()));
        TurnReplay.ReplayModel regressed =
                (msg, results) ->
                        results.isEmpty()
                                ? TurnReplay.Step.call("lookup", "{\"order\":43}")
                                : TurnReplay.Step.finish("done");
        InMemoryTurnJournal b = new InMemoryTurnJournal();
        UUID tb = TurnReplay.run(b, agent, "q", regressed, tools(new AtomicInteger()));
        assertFalse(TurnReplay.sameBehaviour(a.read(agent, ta), b.read(agent, tb)));
    }

    @Test
    void noRawPromptArgumentsOrResultsAreStored() {
        InMemoryTurnJournal j = new InMemoryTurnJournal();
        UUID t = TurnReplay.run(j, agent, "my secret question about order 42", SCRIPT, tools(new AtomicInteger()));
        String dump = j.read(agent, t).toString();
        assertFalse(dump.contains("secret question"));
        assertFalse(dump.contains("customer"));
        assertFalse(dump.contains("shipped"));
    }
}
