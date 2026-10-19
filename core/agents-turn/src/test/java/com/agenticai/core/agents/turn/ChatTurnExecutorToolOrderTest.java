package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ChatTurnExecutorToolOrderTest {

    private static ToolCallback tool(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(
            ToolDefinition.builder().name(name).description("d").inputSchema("{}").build());
        return cb;
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).collect(Collectors.toList());
    }

    @Test
    void alwaysKeptToolsComeFirstThenTheRestByName() {
        List<ToolCallback> in = List.of(
            tool("zeta_tool"), tool("request_human"), tool("alpha_tool"), tool("remember_fact"));

        List<ToolCallback> out = ChatTurnExecutor.stableToolOrder(in);

        assertEquals(List.of("remember_fact", "request_human", "alpha_tool", "zeta_tool"), names(out));
    }

    @Test
    void sameSetInDifferentOrderYieldsTheSameOrderAndInputIsNotMutated() {
        List<ToolCallback> a = List.of(tool("b_tool"), tool("a_tool"), tool("search_knowledge_base"));
        List<ToolCallback> b = List.of(tool("a_tool"), tool("search_knowledge_base"), tool("b_tool"));

        assertEquals(names(ChatTurnExecutor.stableToolOrder(a)), names(ChatTurnExecutor.stableToolOrder(b)));
        assertEquals(List.of("b_tool", "a_tool", "search_knowledge_base"), names(a));
    }

    @Test
    void nullAndSingleElementListsPassThrough() {
        assertNull(ChatTurnExecutor.stableToolOrder(null));
        List<ToolCallback> one = List.of(tool("only"));
        assertEquals(one, ChatTurnExecutor.stableToolOrder(one));
    }
}
