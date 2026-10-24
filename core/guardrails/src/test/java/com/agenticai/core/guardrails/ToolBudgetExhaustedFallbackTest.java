package com.agenticai.core.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** The hand-written JSON used when serialisation fails holds the registry's instruction, not a literal of its own. */
class ToolBudgetExhaustedFallbackTest {

    @Test
    void theFallbackIsTheSameJsonAsTheNormalPathForAnyInstruction() throws Exception {
        for (String instruction : new String[] {"Stop now.", "He said \"stop\"\nand left \\ {x}", ""}) {
            String normal = ToolBudgetExhausted.result(instruction);
            String fallback = ToolBudgetExhausted.fallbackResult(instruction);
            JsonNode parsed = JsonMappers.plain().readTree(fallback);
            assertThat(parsed.get("error").asText()).isEqualTo(ToolBudgetExhausted.MARKER);
            assertThat(parsed.get("result").asText()).isEqualTo(instruction);
            assertThat(fallback).isEqualTo(normal);
            assertThat(ToolBudgetExhausted.isBudgetExhausted(fallback)).isTrue();
        }
    }
}
