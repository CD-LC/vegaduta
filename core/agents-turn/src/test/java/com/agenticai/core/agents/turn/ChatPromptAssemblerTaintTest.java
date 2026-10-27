package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatPromptAssemblerTaintTest {

    private static TurnContext context() {
        return new TurnContext(null, UUID.randomUUID(), "s", "r", Instant.now(), null, null);
    }

    @Test
    void nonEmptyRecallTaintsTheTurn() {
        TurnContext c = context();
        ChatPromptAssembler.taintIfRecalled(c, "- user likes tea");
        assertEquals(List.of("memory_recall"), c.toolActivity().taintSeeds().sources());
    }

    @Test
    void emptyOrMissingRecallLeavesTheTurnClean() {
        TurnContext c = context();
        ChatPromptAssembler.taintIfRecalled(c, null);
        ChatPromptAssembler.taintIfRecalled(c, "  ");
        ChatPromptAssembler.taintIfRecalled(null, "x");
        assertEquals(List.of(), c.toolActivity().taintSeeds().sources());
    }
}
