package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.turn.ChatParityProperties.FastBufferTrigger;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Which FAST turns give streaming up is {@code app.chat.parity.fast-buffering-triggers}, decided here. */
class FastBufferingTest {

    private final ChatParityProperties parity = new ChatParityProperties();

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    @Test
    void theDefaultBuffersForAGenerationToolOrAnMcpToolAsItAlwaysDid() {
        assertTrue(FastBuffering.applies(parity, List.of(tool("generate_image")), false, () -> false));
        assertTrue(FastBuffering.applies(parity, List.of(tool("plain")), true, () -> false));
        assertFalse(FastBuffering.applies(parity, List.of(tool("plain")), false, () -> false));
    }

    @Test
    void outputGuardrailsDoNotBufferUnlessSwitchedOn() {
        assertFalse(FastBuffering.applies(parity, List.of(), false, () -> true), "the default audits a streamed reply");

        parity.setFastBufferingTriggers(EnumSet.of(FastBufferTrigger.OUTPUT_GUARDRAILS));
        assertTrue(FastBuffering.applies(parity, List.of(), false, () -> true));
        assertTrue(FastBuffering.holdsForOutputCheck(parity, () -> true));
        assertFalse(FastBuffering.holdsForOutputCheck(parity, () -> false), "an agent with none has nothing to check");
    }

    @Test
    void anEmptyListNeverBuffers() {
        parity.setFastBufferingTriggers(EnumSet.noneOf(FastBufferTrigger.class));

        assertFalse(FastBuffering.applies(parity, List.of(tool("generate_image")), true, () -> true));
    }

    @Test
    void theGuardrailLookupIsNotAskedWhenItsTriggerIsOffAndAtMostOnceWhenItIsOn() {
        AtomicInteger asked = new AtomicInteger();
        java.util.function.BooleanSupplier lookup = FastBuffering.once(() -> {
            asked.incrementAndGet();
            return true;
        });

        FastBuffering.applies(parity, List.of(), false, lookup);
        assertEquals(0, asked.get(), "default triggers do not include OUTPUT_GUARDRAILS: no plan lookup");

        parity.setFastBufferingTriggers(EnumSet.of(FastBufferTrigger.OUTPUT_GUARDRAILS));
        FastBuffering.applies(parity, List.of(), false, lookup);
        FastBuffering.holdsForOutputCheck(parity, lookup);
        assertEquals(1, asked.get());
    }
}
