package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@link ToolRelevanceSelector}: the built-in {@code browser_*} tools only work as a set. A request
 * that names one step ("open this page in your browser") used to keep {@code browser_navigate} and
 * trim the tools needed to read or click what it opened.
 */
class ToolRelevanceSelectorBrowserFamilyTest extends ToolRelevanceSelectorTestSupport {

    private static List<ToolCallback> catalog() {
        return List.of(
                tool("browser_navigate", "Open a URL in the headless browser"),
                tool("browser_read_page_text", "Return the visible words of the current screen"),
                tool("browser_click_element", "Press a numbered element"),
                tool("browser_snapshot", "List the numbered elements"),
                tool("web_search", "Search the web"),
                tool("calendar_add", "Add an event"),
                tool("memo_save", "Save a memo"),
                tool("zip_files", "Zip files"),
                tool("convert_units", "Convert units"),
                tool("translate_text", "Translate"),
                tool("weather_now", "Weather"),
                tool("stock_quote", "Quote"));
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void oneNamedBrowserStepBringsItsSiblingsAlong() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 3, 8000);

        List<String> kept = names(selector.select("open https://example.com in the browser", catalog(), Set.of(), Set.of()));

        assertTrue(kept.contains("browser_navigate"), "the named step is kept: " + kept);
        assertTrue(kept.contains("browser_read_page_text"), "so is the way to read the page: " + kept);
        assertTrue(kept.contains("browser_click_element"), "and to click in it: " + kept);
    }

    @Test
    void aTurnThatNeverMentionsTheBrowserIsNotOfferedOne() {
        ToolRelevanceSelector selector = new ToolRelevanceSelector(true, 3, 8000);

        List<String> kept = names(selector.select("what is the weather today", catalog(), Set.of(), Set.of()));

        assertFalse(kept.stream().anyMatch(n -> n.startsWith("browser_")), "no seeding of the browser family: " + kept);
    }
}
