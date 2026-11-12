package com.agenticai.core.agents.turnjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.memory.MemoryWriteSwitch;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A memory tool that skipped its write because memory.write.enabled is off leaves a counts-only
 * journal entry.
 */
class JournalingMemoryWriteSkippedTest {

    private static ToolCallback tool(String result) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("remember_fact")
                        .description("d")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return result;
            }
        };
    }

    private List<String> guardSummaries(String result) {
        UUID agent = UUID.randomUUID();
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnRecorder rec = TurnRecorder.start(journal, agent, UUID.randomUUID(), "s");
        rec.wrapTools(List.of(tool(result))).get(0).call("{\"fact\":\"secret tea\"}");
        return journal.read(agent, rec.turnId()).stream()
                .filter(e -> e.type() == TurnEventType.GUARD)
                .map(TurnEvent::summary)
                .toList();
    }

    @Test
    void aSkippedWriteIsRecordedWithoutTheFactText() {
        List<String> guards = guardSummaries(MemoryWriteSwitch.skippedResult());

        assertEquals(1, guards.size());
        assertTrue(guards.get(0).contains("guard=memory_write_skipped"), guards.get(0));
        assertTrue(guards.get(0).contains("tool=remember_fact count=1"), guards.get(0));
        assertFalse(guards.get(0).contains("tea"), guards.get(0));
    }

    @Test
    void aNormalWriteLeavesNoSkipEntry() {
        assertTrue(guardSummaries("{\"remembered\": true}").isEmpty());
    }

    @Test
    void theMarkerMatchesTheMemoryPackagesWording() {
        assertTrue(
                MemoryWriteSwitch.skippedResult()
                        .contains(JournalingToolCallback.MEMORY_WRITE_SKIPPED_MARKER));
    }
}
