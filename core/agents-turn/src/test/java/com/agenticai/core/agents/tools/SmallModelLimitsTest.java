package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Small-model mode's tool cap and ranking weights are {@link SmallModelProperties} ({@code
 * app.chat.small-model.*}), not constants. The defaults are the old numbers (6, 4, 50, 1000).
 */
class SmallModelLimitsTest {

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

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void theDefaultsAreTheOldConstants() {
        SmallModelProperties p = new SmallModelProperties();
        assertEquals(6, p.getToolCap());
        assertEquals(4, p.getBrowserFamilyQuota());
        assertEquals(50, p.getIntentBoost());
        assertEquals(1_000, p.getNamedToolScore());
    }

    @Test
    void theToolCapComesFromTheSettings() {
        List<ToolCallback> tools = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            tools.add(tool("tool_" + i, "Does thing number " + i + "."));
        }
        SmallModelProperties p = new SmallModelProperties();
        p.setToolCap(3);

        assertEquals(3, AgentToolSelection.capForSmallModel(tools, "hello", Set.of(), p).size());
        assertEquals(6, AgentToolSelection.capForSmallModel(tools, "hello", Set.of(), new SmallModelProperties()).size());
    }

    @Test
    void theBrowserFamilyQuotaIsConfigurable() {
        List<ToolCallback> tools =
                List.of(
                        tool("browser_navigate", "Open a page."),
                        tool("browser_snapshot", "Snapshot the page."),
                        tool("browser_read_page_text", "Read the page text."),
                        tool("browser_click_element", "Click an element."),
                        tool("remember_fact", "Remember a fact about the user."),
                        tool("other_tool", "Something else."));
        String message = "browse the site and remember this";

        // Default: a browsing request keeps the whole browser set, so the second intent finds no slot.
        List<String> byDefault =
                names(AgentToolSelection.capForSmallModel(tools, message, 4, Set.of(), new SmallModelProperties()));
        assertFalse(byDefault.contains("remember_fact"), byDefault.toString());

        SmallModelProperties one = new SmallModelProperties();
        one.setBrowserFamilyQuota(1);
        List<String> narrow = names(AgentToolSelection.capForSmallModel(tools, message, 4, Set.of(), one));
        assertTrue(narrow.contains("remember_fact"), narrow.toString());
    }

    private static final List<ToolCallback> NAMED_AND_BOOSTED =
            List.of(
                    tool("alpha_tool", "Does alpha."),
                    tool("web_search", "Search the web."),
                    tool("beta_tool", "Does beta."));

    private static final String NAMED_AND_BOOSTED_MESSAGE = "search the web, or use alpha_tool";

    @Test
    void theNamedToolScoreDecidesWhetherANamedToolOutranksAnIntentMatch() {
        List<String> byDefault =
                names(AgentToolSelection.capForSmallModel(
                        NAMED_AND_BOOSTED, NAMED_AND_BOOSTED_MESSAGE, 2, Set.of(), new SmallModelProperties()));
        assertEquals(List.of("alpha_tool", "web_search"), byDefault, "a tool named in the message comes first");

        SmallModelProperties lowNamed = new SmallModelProperties();
        lowNamed.setNamedToolScore(10);
        assertEquals(
                List.of("web_search", "alpha_tool"),
                names(AgentToolSelection.capForSmallModel(
                        NAMED_AND_BOOSTED, NAMED_AND_BOOSTED_MESSAGE, 2, Set.of(), lowNamed)));
    }

    @Test
    void theIntentBoostDecidesWhetherAnIntentMatchOutranksANamedTool() {
        SmallModelProperties huge = new SmallModelProperties();
        huge.setIntentBoost(5_000);
        assertEquals(
                List.of("web_search", "alpha_tool"),
                names(AgentToolSelection.capForSmallModel(
                        NAMED_AND_BOOSTED, NAMED_AND_BOOSTED_MESSAGE, 2, Set.of(), huge)));
    }
}
