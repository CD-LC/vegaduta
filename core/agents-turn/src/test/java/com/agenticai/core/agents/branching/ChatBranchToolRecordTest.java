package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.agents.branching.ChatBranchDtos.ActivePathResponse;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.turn.ToolLedger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The branch tree stores role and text only, but editing or regenerating a later message re-points the live
 * transcript at the path before it: the turns that are unchanged keep the tool record they were stored with.
 */
class ChatBranchToolRecordTest extends ChatBranchServiceTestBase {

    private static final ToolLedger RECORD =
            new ToolLedger(
                    List.of(new ToolLedger.Entry("create_file", "{}", "{\"fileId\":\"file-1\"}", ToolLedger.Status.OK, null)), 0);

    @Test
    void editingALaterMessageKeepsTheToolRecordOfTheTurnsBeforeIt() {
        chat("q1", null);
        live.add(new ChatTurn(MessageRole.ASSISTANT, "r1", Instant.parse("2026-10-01T10:00:01Z"), RECORD));
        chat("q2", "r2");
        UUID q2 = service.activePath(agentId, SESSION).messages().get(2).id();

        service.prepareEdit(agentId, SESSION, q2, "q2 edited");

        assertEquals(List.of("q1", "r1"), liveTexts(live));
        assertNotNull(live.get(1).tools(), "r1 still carries what its tools did");
        assertEquals("create_file", live.get(1).tools().entries().get(0).tool());
        assertNull(live.get(0).tools());
    }

    @Test
    void aTurnWhoseTextDiffersDoesNotInheritAnotherTurnsRecord() {
        List<ChatTurn> turns =
                List.of(new ChatTurn(MessageRole.ASSISTANT, "different", Instant.parse("2026-10-01T10:00:01Z")));
        List<ChatTurn> before =
                List.of(new ChatTurn(MessageRole.ASSISTANT, "r1", Instant.parse("2026-10-01T10:00:01Z"), RECORD));

        assertNull(ChatBranchService.withToolRecords(turns, before).get(0).tools());
    }
}
