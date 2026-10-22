package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TextToolCallParserTest {

    private static final Set<String> TOOLS =
            Set.of("generate_document", "read_skill_file", "search_docs");

    /**
     * The exact reply from the 2026-10-15 playground incident (Documents Agent, "yes generate").
     */
    private static final String INCIDENT_REPLY =
            "{\"name\": \"generate_document\", \"parameters\": {\"f\": \"mandi_prices_weather_report\", "
                    + "\"path\": \"templates/report.md\", \"skill\": \"mandi-prices-weather-report\"}}";

    private final ObjectMapper objectMapper = com.agenticai.core.json.JsonMappers.plain();

    @Test
    void theIncidentReplyParsesAsOneCallToGenerateDocument() throws Exception {
        List<AssistantMessage.ToolCall> calls =
                TextToolCallParser.parse(INCIDENT_REPLY, TOOLS, objectMapper);

        assertEquals(1, calls.size());
        AssistantMessage.ToolCall call = calls.get(0);
        assertEquals("generate_document", call.name());
        assertEquals("function", call.type());
        assertFalse(call.id().isBlank());
        JsonNode args = objectMapper.readTree(call.arguments());
        assertEquals("templates/report.md", args.get("path").asString());
        assertEquals("mandi-prices-weather-report", args.get("skill").asString());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}",
                "{\"name\":\"search_docs\",\"args\":{\"q\":\"x\"}}",
                "{\"name\":\"search_docs\",\"input\":{\"q\":\"x\"}}",
                "{\"tool\":\"search_docs\",\"parameters\":{\"q\":\"x\"}}",
                "{\"tool_name\":\"search_docs\",\"tool_input\":{\"q\":\"x\"}}",
                "{\"name\":\"search_docs\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}",
                "{\"function\":{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}}",
                "{\"type\":\"function\",\"function\":{\"name\":\"search_docs\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}",
                "{\"name\":\"search_docs\",\"q\":\"x\"}",
                "{\"tool_calls\":[{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}]}",
                "```json\n{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}\n```",
                "```\n{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}\n```",
                "<tool_call>\n{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}\n</tool_call>",
                "Let me look that up.\n<tool_call>{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}</tool_call>",
                "<function=search_docs>{\"q\":\"x\"}</function>",
                "<|python_tag|>{\"name\":\"search_docs\",\"parameters\":{\"q\":\"x\"}}",
                "[TOOL_CALLS] [{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}]",
                "  \n{\"name\":\"search_docs\",\"arguments\":{\"q\":\"x\"}}\n\n"
            })
    void everyKnownTextShapeParsesToTheSameCall(String reply) throws Exception {
        List<AssistantMessage.ToolCall> calls =
                TextToolCallParser.parse(reply, TOOLS, objectMapper);

        assertEquals(1, calls.size(), reply);
        assertEquals("search_docs", calls.get(0).name());
        assertEquals("x", objectMapper.readTree(calls.get(0).arguments()).get("q").asString());
    }

    @Test
    void anArrayOfCallsKeepsOrder() {
        String reply =
                "[{\"name\":\"search_docs\",\"arguments\":{}},{\"name\":\"read_skill_file\",\"arguments\":{\"path\":\"a\"}}]";

        List<AssistantMessage.ToolCall> calls =
                TextToolCallParser.parse(reply, TOOLS, objectMapper);

        assertEquals(
                List.of("search_docs", "read_skill_file"),
                calls.stream().map(AssistantMessage.ToolCall::name).toList());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Hello! How can I help you today?",
                "Here is the JSON you asked for:\n```json\n{\"name\":\"search_docs\",\"arguments\":{}}\n```",
                "{\"name\":\"search_docs\",\"arguments\":{}}\nAnd that is what I would call.",
                "{\"name\":\"not_a_tool\",\"arguments\":{}}",
                "[{\"name\":\"search_docs\",\"arguments\":{}},{\"name\":\"not_a_tool\",\"arguments\":{}}]",
                "{\"name\":\"search_docs\",\"arguments\":\"not json\"}",
                "{\"name\":\"search_docs\",\"arguments\":[1,2]}",
                "{\"status\":\"ok\",\"rows\":[]}",
                "{\"name\":\"\",\"arguments\":{}}",
                "<tool_call>{\"name\":\"search_docs\",\"arguments\":{}}</tool_call> and then some prose after it",
                "```json\n{\"result\": 42}\n```",
                "",
                "   "
            })
    void anythingElseIsNotRecovered(String reply) {
        assertTrue(TextToolCallParser.parse(reply, TOOLS, objectMapper).isEmpty(), reply);
    }

    @Test
    void noOfferedToolsMeansNothingIsRecovered() {
        assertTrue(TextToolCallParser.parse(INCIDENT_REPLY, Set.of(), objectMapper).isEmpty());
        assertTrue(TextToolCallParser.parse(INCIDENT_REPLY, null, objectMapper).isEmpty());
    }

    // --- streaming prefix ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "  ",
                "\n",
                "{",
                "{\"name\"",
                "[",
                "[{",
                "[TOOL",
                "[TOOL_CALLS] [{",
                "`",
                "``",
                "```",
                "```json",
                "```json\n",
                "```json\n{\"na",
                "<",
                "<tool",
                "<tool_call>",
                "<tool_call>{",
                "<function=",
                "<|python",
                "Sure",
                "Let me",
                "Let me generate that.\n<tool_call>{\"name\""
            })
    void prefixesOfAToolCallKeepTheStreamHeld(String prefix) {
        assertTrue(TextToolCallParser.couldBeToolCall(prefix), "should still hold: " + prefix);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Hello! How can I help you today?",
                "Here is what I found about mandi prices",
                "[1] first citation",
                "[Link text](https://example.com)",
                "`inline code`",
                "```python\nprint(1)",
                "<div>",
                "<b>bold</b>",
                "- a list item, the first of several",
                "# A heading over the report",
                "Short one.\nSecond line.",
                "I will generate the report now. Please wait a moment while I prepare it."
            })
    void aReplyThatCannotBeAToolCallIsReleasedAtOnce(String prefix) {
        assertFalse(TextToolCallParser.couldBeToolCall(prefix), "should release: " + prefix);
    }

    /**
     * A prose reply is held for at most {@code 24} characters: long enough to tell "Let me look
     * that up.\n<tool_call>" from an answer, short enough that the delay is a token or two.
     */
    @Test
    void shortProseIsHeldOnlyUntilItOutgrowsALeadIn() {
        assertTrue(TextToolCallParser.couldBeToolCall("- a list item"));
        assertTrue(TextToolCallParser.couldBeToolCall("# A heading"));
        assertFalse(TextToolCallParser.couldBeToolCall("- a list item that goes on"));
        assertFalse(TextToolCallParser.couldBeToolCall("# A heading\n"));
    }

    @Test
    void unfenceStripsOneFenceWithOrWithoutInfoString() {
        assertEquals("{\"a\":1}", TextToolCallParser.unfence("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", TextToolCallParser.unfence("```\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", TextToolCallParser.unfence("{\"a\":1}"));
    }

    @Test
    void qwenParameterTagsInsideAFunctionBlockWithAStrayClosingTagBecomeOneCall() throws Exception {
        String reply =
                "Let me write the main class:\n<function=workspace_write>\n<parameter=path>\n"
                        + "app/src/Main.java\n</parameter>\n<parameter=content>\npackage a;\n\npublic class Main {\n}\n</parameter>\n"
                        + "</function>\n</tool_call>";

        List<AssistantMessage.ToolCall> calls =
                TextToolCallParser.parse(reply, Set.of("workspace_write"), objectMapper);

        assertEquals(1, calls.size());
        JsonNode args = objectMapper.readTree(calls.get(0).arguments());
        assertEquals("app/src/Main.java", args.get("path").asString());
        assertEquals("package a;\n\npublic class Main {\n}", args.get("content").asString());
    }

    @Test
    void parameterTagsWithProseBetweenThemAreNotRecovered() {
        String reply =
                "<function=workspace_write>\n<parameter=path>\nx\n</parameter>\nand then some prose\n</function>";

        assertTrue(
                TextToolCallParser.parse(reply, Set.of("workspace_write"), objectMapper).isEmpty());
    }
}
