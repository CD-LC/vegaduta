package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ResolvedToolList} carries the "were any MCP tools offered" answer out of {@code
 * AgentToolProvider#resolveToolsFor}. These pin that the answer survives {@link
 * ResolvedToolList#copyOf} but is absent on a list that never recorded one.
 */
class ResolvedToolListTest {

    @Test
    void ofCarriesTheRecordedAnswer() {
        ResolvedToolList yes = ResolvedToolList.of(List.of(), true);
        ResolvedToolList no = ResolvedToolList.of(List.of(), false);

        assertEquals(Boolean.TRUE, yes.anyMcpToolsOffered());
        assertEquals(Boolean.FALSE, no.anyMcpToolsOffered());
        assertTrue(yes.isEmpty());
    }

    @Test
    void emptyCarriesNoAnswer() {
        ResolvedToolList list = ResolvedToolList.empty();

        assertTrue(list.isEmpty());
        assertNull(list.anyMcpToolsOffered());
    }

    @Test
    void copyOfPreservesTheAnswerAndTheTools() {
        ResolvedToolList original = ResolvedToolList.of(List.of(), true);

        ResolvedToolList copy = ResolvedToolList.copyOf(original);

        assertEquals(Boolean.TRUE, copy.anyMcpToolsOffered());
        // A copy of a plain list carries no answer at all.
        assertNull(
                ResolvedToolList.copyOf(List.<org.springframework.ai.tool.ToolCallback>of())
                        .anyMcpToolsOffered());
    }
}
