package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Small-model mode offers at most six tools, the ones that match the message best. A follow-up such as
 * "yes" or "continue" matches none of them, so the six were simply the first six in resolved order and
 * the tools the task was using fell off the list in the middle of it. And the words it matched on were
 * ASCII only, so a message in another script matched nothing at all.
 */
class SmallModelFollowUpTest {

    private static ToolCallback tool(String name, String description) {
        ToolDefinition definition =
                ToolDefinition.builder().name(name).description(description).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    private static List<ToolCallback> tenTools() {
        List<ToolCallback> tools = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tools.add(tool("tool_" + i, "does thing number " + i));
        }
        return tools;
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void theToolsTheConversationAlreadyUsedStayOnOfferForAFollowUp() {
        List<String> kept =
                names(AgentToolSelection.capForSmallModel(tenTools(), "yes", 6, Set.of("tool_8", "tool_9")));

        assertThat(kept).hasSize(6).contains("tool_8", "tool_9");
    }

    @Test
    void withNoStickyToolsAFollowUpKeepsTheFirstSixAsBefore() {
        assertThat(names(AgentToolSelection.capForSmallModel(tenTools(), "yes", 6, Set.of())))
                .containsExactly("tool_0", "tool_1", "tool_2", "tool_3", "tool_4", "tool_5");
    }

    @Test
    void aToolTheMessageNamesStillOutranksAStickyOne() {
        List<String> kept =
                names(AgentToolSelection.capForSmallModel(tenTools(), "use tool_3 please", 6, Set.of("tool_9")));

        assertThat(kept.get(0)).isEqualTo("tool_3");
        assertThat(kept).contains("tool_9");
    }

    @Test
    void wordsInAnyScriptMatchToolDescriptionsInThatScript() {
        List<ToolCallback> tools = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tools.add(tool("tool_" + i, "Инструмент номер " + i));
        }
        tools.add(tool("send_invoice", "Отправить счёт клиенту"));

        List<String> kept = names(AgentToolSelection.capForSmallModel(tools, "Отправить счёт клиенту", 6, Set.of()));

        assertThat(kept).contains("send_invoice");
    }
}
