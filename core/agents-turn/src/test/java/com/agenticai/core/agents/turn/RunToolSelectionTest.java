package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The narrowing rules of a run-scoped tool selection: it removes tools from the resolved list, nothing else. */
class RunToolSelectionTest {

    private static ToolCallback tool(String name) {
        ToolDefinition definition =
                ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return name;
            }
        };
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void keepsExactlyTheNamedTools() {
        List<ToolCallback> tools = new ArrayList<>(List.of(tool("a"), tool("b"), tool("c")));

        RunToolSelection.narrow(tools, List.of("c", "a"), true);

        assertEquals(List.of("a", "c"), names(tools));
    }

    @Test
    void theKeptToolsAreTheSameWrappedInstances() {
        ToolCallback guarded = tool("send_invoice");
        List<ToolCallback> tools = new ArrayList<>(List.of(guarded, tool("lookup")));

        RunToolSelection.narrow(tools, List.of("send_invoice"), true);

        assertSame(guarded, tools.get(0), "narrowing never replaces a tool (and so never its gates)");
    }

    @Test
    void anEmptySelectionLeavesNoTools() {
        List<ToolCallback> tools = new ArrayList<>(List.of(tool("a"), tool("b")));

        RunToolSelection.narrow(tools, List.of(), true);

        assertTrue(tools.isEmpty());
    }

    @Test
    void aRepeatedNameIsHarmless() {
        List<ToolCallback> tools = new ArrayList<>(List.of(tool("a"), tool("b")));

        RunToolSelection.narrow(tools, List.of("a", "a"), true);

        assertEquals(List.of("a"), names(tools));
    }

    @Test
    void anUnknownNameIsRefusedAndNamedAndTheListIsLeftAlone() {
        List<ToolCallback> tools = new ArrayList<>(List.of(tool("a"), tool("b")));

        ResponseStatusException refused =
                assertThrows(
                        ResponseStatusException.class,
                        () -> RunToolSelection.narrow(tools, List.of("a", "rm_rf"), true));

        assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
        assertTrue(refused.getReason().contains("rm_rf"), refused.getReason());
        assertTrue(refused.getReason().contains("a, b"), "the message lists what the agent does offer");
        assertEquals(List.of("a", "b"), names(tools), "a refused selection changes nothing");
    }

    @Test
    void aSelectionCanNeverAddAToolTheListLacks() {
        List<ToolCallback> tools = new ArrayList<>(List.of(tool("a")));

        assertThrows(ResponseStatusException.class, () -> RunToolSelection.narrow(tools, List.of("a", "extra"), true));
        assertEquals(List.of("a"), names(tools));
    }

    @Test
    void theContinuationDropsAToolThatIsGoneInsteadOfRefusing() {
        List<ToolCallback> tools = new ArrayList<>(List.of(tool("a"), tool("b")));

        RunToolSelection.narrow(tools, List.of("a", "removed_since"), false);

        assertEquals(List.of("a"), names(tools));
    }

    @Test
    void theToolSearchPairIsNotKeptAlongsideASelection() {
        List<ToolCallback> tools = new ArrayList<>(List.of(tool("a"), tool("search_tools"), tool("call_tool")));

        RunToolSelection.narrow(tools, List.of("a"), true);

        assertEquals(List.of("a"), names(tools), "call_tool would reach tools the selection left out");
    }

    @Test
    void theRefusalListsAtMostAFewDozenNames() {
        List<ToolCallback> tools = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tools.add(tool("tool_" + String.format("%03d", i)));
        }

        ResponseStatusException refused =
                assertThrows(ResponseStatusException.class, () -> RunToolSelection.narrow(tools, List.of("nope"), true));

        assertTrue(refused.getReason().contains("and 60 more"), refused.getReason());
    }
}
