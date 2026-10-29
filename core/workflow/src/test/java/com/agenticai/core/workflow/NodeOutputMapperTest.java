package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.turn.AgentChatService.StructuredAgentOutput;
import com.agenticai.core.agents.turn.AgentChatService.ToolCallSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link NodeOutputMapper}: each {@code outputField} switch arm and the JSON serialiser fallback. */
class NodeOutputMapperTest {

    private final NodeOutputMapper mapper = new NodeOutputMapper(new ObjectMapper());

    private static StructuredAgentOutput output() {
        return new StructuredAgentOutput(
                "the answer",
                0.75,
                List.of("doc-1", "doc-2"),
                List.of(new ToolCallSummary("search", "{}", "found")),
                Map.of("model", "m1"));
    }

    @Test
    void theDefaultArmReturnsTheResultText() {
        assertEquals("the answer", mapper.applyOutputMapping("result", output()));
        assertEquals("the answer", mapper.applyOutputMapping("anything", output()));
        assertEquals(
                "",
                mapper.applyOutputMapping(
                        "x", new StructuredAgentOutput(null, 1.0, List.of(), List.of(), Map.of())),
                "a null result text maps to empty text, not \"null\"");
    }

    @Test
    void theNamedArmsMapTheirField() {
        StructuredAgentOutput out = output();
        assertEquals("0.75", mapper.applyOutputMapping("confidence", out));
        assertEquals("[\"doc-1\",\"doc-2\"]", mapper.applyOutputMapping("sources", out));
        String calls = mapper.applyOutputMapping("tool_calls", out);
        assertTrue(calls.contains("\"toolName\":\"search\""), calls);
        assertEquals("{\"model\":\"m1\"}", mapper.applyOutputMapping("metadata", out));
    }

    @Test
    void writeJsonQuietlyFallsBackToAnEmptyObject() {
        assertEquals("5", mapper.writeJsonQuietly(5));
        assertEquals("null", mapper.writeJsonQuietly(null));
        Object unserialisable = new Object() {
            @SuppressWarnings("unused")
            public String getBroken() {
                throw new IllegalStateException("no");
            }
        };
        assertEquals("{}", mapper.writeJsonQuietly(unserialisable));
    }
}
