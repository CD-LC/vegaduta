package com.agenticai.core.agents.tools.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * search_tools + call_tool against a hand-built {@link ToolSearchState}: activation, dispatch,
 * refusals.
 */
class SearchAndCallToolTest {

    /** Records what it was called with; returns {@code "<name>:<input>"}. */
    private static final class RecordingTool implements ToolCallback {
        final String name;
        final String description;
        final String schema;
        final List<String> inputs = new ArrayList<>();
        ToolContext lastContext;
        RuntimeException failure;

        RecordingTool(String name, String description, String schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(schema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            inputs.add(toolInput);
            if (failure != null) {
                throw failure;
            }
            return name + ":" + toolInput;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            lastContext = toolContext;
            return call(toolInput);
        }
    }

    private static final String SMALL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}},\"required\":[\"to\"]}";

    private RecordingTool gmail;
    private RecordingTool issue;
    private RecordingTool slack;
    private RecordingTool direct;
    private ToolActivationRegistry registry;
    private ToolSearchState state;
    private SearchToolsToolCallback search;
    private CallToolToolCallback callTool;

    @BeforeEach
    void setUp() {
        gmail =
                new RecordingTool(
                        "google_gmail",
                        "Reads, searches and sends Gmail email messages",
                        SMALL_SCHEMA);
        issue =
                new RecordingTool(
                        "mcp__github__create_issue",
                        "Creates a new issue in a GitHub repository",
                        "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}}}");
        slack =
                new RecordingTool(
                        "mcp__slack__post_message",
                        "Posts a message to a Slack channel",
                        "{\"type\":\"object\"}");
        direct = new RecordingTool("remember_fact", "Remembers one fact", "{}");

        Map<String, ToolCallback> deferred = new LinkedHashMap<>();
        for (RecordingTool tool : List.of(gmail, issue, slack)) {
            deferred.put(tool.name, tool);
        }
        Map<String, ToolCallback> exposed = new LinkedHashMap<>();
        exposed.put(direct.name, direct);

        registry = new ToolActivationRegistry();
        state =
                new ToolSearchState(
                        exposed, deferred, new ToolSearchService(), registry, "key-1", 6);
        search = new SearchToolsToolCallback(state);
        callTool = new CallToolToolCallback(state);
    }

    // ---- search_tools ----------------------------------------------------

    @Test
    void searchReturnsNamesOneLineDescriptionsAndFullSchemasAndActivatesTheMatches() {
        String result = search.call("{\"query\":\"send email\"}");

        assertTrue(result.contains("Found 1 tool(s)"), result);
        assertTrue(
                result.contains("google_gmail - Reads, searches and sends Gmail email messages"),
                result);
        assertTrue(result.contains("\"required\":[\"to\"]"), result);
        assertEquals(Set.of("google_gmail"), state.activatedNames());
        assertEquals(List.of("google_gmail"), registry.recall("key-1", 10));
    }

    @Test
    void searchDoesNotListToolsThatAreAlreadyOfferedDirectly() {
        String result = search.call("{\"query\":\"remember fact\"}");

        assertTrue(result.startsWith("No tools matched"), result);
        assertTrue(state.activatedNames().isEmpty());
    }

    @Test
    void searchWithNoMatchSaysSoAndListsWhatIsSearchableBySource() {
        String result = search.call("{\"query\":\"zzzqqq\"}");

        assertTrue(result.startsWith("No tools matched \"zzzqqq\""), result);
        assertTrue(result.contains("built-in (1)"), result);
        assertTrue(result.contains("github (1)"), result);
        assertTrue(state.activatedNames().isEmpty());
        assertTrue(registry.recall("key-1", 10).isEmpty());
    }

    @Test
    void aNoMatchListsEverySearchableToolNameAndKeepsTheSelectEscapeHatch() {
        String result = search.call("{\"query\":\"zzzqqq\"}");

        // A model whose task keywords missed can recover: the names are its next search terms
        // and select: loads one directly.
        assertTrue(result.contains("Searchable tool names: "), result);
        assertTrue(result.contains("google_gmail"), result);
        assertTrue(result.contains("mcp__github__create_issue"), result);
        assertTrue(result.contains("mcp__slack__post_message"), result);
        assertTrue(result.contains("select:name1,name2"), result);
        assertFalse(result.contains("remember_fact"), "exposed tools are not searchable");
    }

    @Test
    void aNoMatchOnALargeCatalogListsNameGroupsAndGenericToolsNotEveryName() {
        Map<String, ToolCallback> deferred = new LinkedHashMap<>();
        deferred.put(
                "fetch_web_page",
                new RecordingTool("fetch_web_page", "Fetches a web page", "{}"));
        deferred.put(
                "mcp__files__read_file",
                new RecordingTool("mcp__files__read_file", "Reads a file", "{}"));
        for (int i = 0; i < 70; i++) {
            String name = "group" + (i % 25) + "_tool_" + i;
            deferred.put(name, new RecordingTool(name, "tool " + i, "{}"));
        }
        ToolSearchState bigState =
                new ToolSearchState(
                        Map.of(), deferred, new ToolSearchService(), null, "key-big", 0);

        String result =
                new SearchToolsToolCallback(bigState).call("{\"query\":\"zzzqqq\"}");

        assertTrue(result.contains("Tool name groups: "), result);
        assertTrue(result.contains("(+"), result);
        assertFalse(result.contains("_tool_69"), "individual names are not enumerated");
        // The deterministic fallback names the broadly useful tools the model likely needs.
        assertTrue(result.contains("Common searchable tools: "), result);
        assertTrue(result.contains("fetch_web_page"), result);
        assertTrue(result.contains("mcp__files__read_file"), result);
        assertTrue(result.contains("select:name1,name2"), result);
        assertTrue(result.length() < 4_000, "the hint stays small: " + result.length());
    }

    @Test
    void aNoMatchOnAnEmptyCatalogStillPointsAtKeywords() {
        ToolSearchState empty =
                new ToolSearchState(
                        Map.of(), Map.of(), new ToolSearchService(), null, "key-0", 0);

        String result = new SearchToolsToolCallback(empty).call("{\"query\":\"anything\"}");

        assertTrue(result.startsWith("No tools matched"), result);
        assertFalse(result.contains("Searchable tools by source"), result);
    }

    @Test
    void searchHonoursTheLimitAndSelectSyntax() {
        String selected =
                search.call(
                        "{\"query\":\"select:mcp__slack__post_message,google_gmail\",\"limit\":1}");

        assertTrue(selected.contains("Found 1 tool(s)"), selected);
        assertEquals(Set.of("mcp__slack__post_message"), state.activatedNames());
    }

    @Test
    void searchAnswersBadInputWithAShortSentenceInsteadOfThrowing() {
        assertTrue(
                search.call("not json")
                        .startsWith("Could not search tools: the tool input was not valid JSON"));
        assertTrue(
                search.call("{\"query\":\"   \"}").startsWith("Could not search tools: no query"));
        assertTrue(search.call("{}").startsWith("Could not search tools: no query"));
        assertTrue(search.call(null).startsWith("Could not search tools: no query"));
    }

    @Test
    void aSchemaTooLargeToInlineIsReplacedByItsParameterNames() {
        StringBuilder props = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            if (i > 0) {
                props.append(',');
            }
            props.append("\"parameter_number_").append(i).append("\":{\"type\":\"string\"}");
        }
        RecordingTool huge =
                new RecordingTool(
                        "huge_tool",
                        "Does a huge thing",
                        "{\"type\":\"object\",\"properties\":{" + props + "}}");
        Map<String, ToolCallback> deferred = new LinkedHashMap<>();
        deferred.put(huge.name, huge);
        ToolSearchState bigState =
                new ToolSearchState(Map.of(), deferred, new ToolSearchService(), null, "k", 0);

        String result = new SearchToolsToolCallback(bigState).call("{\"query\":\"huge thing\"}");

        assertTrue(result.contains("too large to show"), result);
        assertTrue(result.contains("parameter_number_0"), result);
        assertTrue(
                result.length() < SearchToolsToolCallback.MAX_INLINE_SCHEMA_CHARS,
                "result stayed small: " + result.length());
    }

    @Test
    void theToolDefinitionsAreValidJsonSchemaWithTheDocumentedFields() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode searchSchema =
                mapper.readTree(search.getToolDefinition().inputSchema());
        com.fasterxml.jackson.databind.JsonNode callSchema =
                mapper.readTree(callTool.getToolDefinition().inputSchema());

        assertEquals("search_tools", search.getToolDefinition().name());
        assertEquals("call_tool", callTool.getToolDefinition().name());
        assertTrue(searchSchema.path("properties").has("query"));
        assertTrue(searchSchema.path("properties").has("limit"));
        assertEquals("query", searchSchema.path("required").get(0).asText());
        assertTrue(callSchema.path("properties").has("name"));
        assertTrue(callSchema.path("properties").has("argsJson"));
    }

    // ---- call_tool -------------------------------------------------------

    @Test
    void callToolRunsAnActivatedToolWithTheExactArgumentsAndForwardsTheToolContext() {
        search.call("{\"query\":\"send email\"}");
        ToolContext context = new ToolContext(Map.of("tenant", "t1"));

        String result =
                callTool.call(
                        "{\"name\":\"google_gmail\",\"argsJson\":\"{\\\"to\\\":\\\"a@b.com\\\"}\"}",
                        context);

        assertEquals("google_gmail:{\"to\":\"a@b.com\"}", result);
        assertEquals(List.of("{\"to\":\"a@b.com\"}"), gmail.inputs);
        assertSame(context, gmail.lastContext);
    }

    @Test
    void callToolWithoutAContextUsesTheSingleArgumentCall() {
        search.call("{\"query\":\"send email\"}");

        callTool.call("{\"name\":\"google_gmail\",\"argsJson\":\"{}\"}");

        assertEquals(List.of("{}"), gmail.inputs);
        assertNull(gmail.lastContext);
    }

    @Test
    void callToolRefusesADeferredToolThatWasNeverSearchedFor() {
        String result = callTool.call("{\"name\":\"google_gmail\",\"argsJson\":\"{}\"}");

        assertTrue(result.contains("has not been loaded yet"), result);
        assertTrue(result.contains("select:google_gmail"), result);
        assertTrue(gmail.inputs.isEmpty(), "the tool must not have run");
    }

    @Test
    void callToolRunsAToolThatIsOfferedDirectlyWithoutAnySearch() {
        String result =
                callTool.call(
                        "{\"name\":\"remember_fact\",\"argsJson\":\"{\\\"fact\\\":\\\"x\\\"}\"}");

        assertEquals("remember_fact:{\"fact\":\"x\"}", result);
    }

    @Test
    void callToolRefusesUnknownNamesBlankNamesAndRecursion() {
        assertTrue(
                callTool.call("{\"name\":\"no_such_tool\"}")
                        .contains("no tool named \"no_such_tool\""));
        assertTrue(
                callTool.call("{\"argsJson\":\"{}\"}")
                        .startsWith("Could not run the tool: no name"));
        assertTrue(callTool.call("{\"name\":\"call_tool\"}").contains("directly"));
        assertTrue(callTool.call("{\"name\":\"search_tools\"}").contains("directly"));
        assertTrue(
                callTool.call("nope")
                        .startsWith(
                                "Could not run the tool: call_tool's input was not valid JSON"));
    }

    @Test
    void callToolValidatesTheArgumentsBeforeDispatching() {
        search.call("{\"query\":\"send email\"}");

        String notJson = callTool.call("{\"name\":\"google_gmail\",\"argsJson\":\"to=a@b.com\"}");
        String notObject = callTool.call("{\"name\":\"google_gmail\",\"argsJson\":\"[1,2]\"}");

        assertTrue(notJson.contains("argsJson must be a JSON object string"), notJson);
        assertTrue(notObject.contains("argsJson must be a JSON object string"), notObject);
        assertTrue(gmail.inputs.isEmpty());
    }

    @Test
    void callToolAcceptsAnObjectUnderArgsJsonOrArgumentsAndDefaultsToAnEmptyObject() {
        search.call("{\"query\":\"send email\"}");

        callTool.call("{\"name\":\"google_gmail\",\"argsJson\":{\"to\":\"x@y.z\"}}");
        callTool.call("{\"name\":\"google_gmail\",\"arguments\":{\"to\":\"p@q.r\"}}");
        callTool.call("{\"name\":\"google_gmail\"}");
        callTool.call("{\"name\":\"google_gmail\",\"argsJson\":\"  \"}");

        assertEquals(List.of("{\"to\":\"x@y.z\"}", "{\"to\":\"p@q.r\"}", "{}", "{}"), gmail.inputs);
    }

    @Test
    void callToolDoesNotSwallowWhatTheTargetThrows() {
        search.call("{\"query\":\"send email\"}");
        gmail.failure = new IllegalStateException("target failed");

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> callTool.call("{\"name\":\"google_gmail\",\"argsJson\":\"{}\"}"));

        assertEquals("target failed", thrown.getMessage());
    }

    @Test
    void searchingTwiceKeepsEarlierActivationsAndNeverActivatesAnUnknownName() {
        search.call("{\"query\":\"send email\"}");
        search.call("{\"query\":\"github issue\"}");

        assertEquals(Set.of("google_gmail", "mcp__github__create_issue"), state.activatedNames());
        assertFalse(
                state.activate(List.of("not_deferred", "remember_fact")).contains("remember_fact"));
        assertEquals(3, state.deferredCount());
    }

    @Test
    void searchResultStaysUnderTheOutputBudgetWhenManySchemasAreLarge() {
        String filler = "x".repeat(2_300);
        String bigSchema =
                "{\"type\":\"object\",\"properties\":{\"p0\":{\"type\":\"string\",\"description\":\""
                        + filler
                        + "\"}}}";
        Map<String, ToolCallback> deferred = new LinkedHashMap<>();
        StringBuilder select = new StringBuilder("select:");
        for (int i = 0; i < 8; i++) {
            RecordingTool tool =
                    new RecordingTool("bulk_tool_" + i, "Bulk tool number " + i, bigSchema);
            deferred.put(tool.name, tool);
            select.append(i == 0 ? "" : ",").append(tool.name);
        }
        ToolSearchState bigState =
                new ToolSearchState(
                        new LinkedHashMap<>(),
                        deferred,
                        new ToolSearchService(),
                        null,
                        "key-big",
                        6);
        SearchToolsToolCallback bigSearch = new SearchToolsToolCallback(bigState);

        String result = bigSearch.call("{\"query\":\"" + select + "\",\"limit\":8}");

        assertTrue(result.length() < 14_000, "result was " + result.length() + " chars");
        for (int i = 0; i < 8; i++) {
            assertTrue(result.contains("bulk_tool_" + i), result);
        }
        assertTrue(result.contains("too large to show; parameters: p0"), result);
        assertTrue(result.contains(filler), "the first schemas should still be inlined");
        assertEquals(8, bigState.activatedNames().size());
    }

    @Test
    void callToolStopsTheTurnAfterTooManyConsecutiveRefusals() {
        CallToolToolCallback bounded =
                new CallToolToolCallback(state, java.util.UUID.randomUUID(), "session-1");
        for (int i = 0; i < CallToolToolCallback.MAX_CONSECUTIVE_REFUSALS; i++) {
            assertTrue(
                    bounded.call("{\"name\":\"nope\",\"argsJson\":\"{}\"}")
                            .startsWith("There is no tool named"));
        }

        assertThrows(
                com.agenticai.core.guardrails.ToolStepLimitExceededException.class,
                () -> bounded.call("{\"name\":\"nope\",\"argsJson\":\"{}\"}"));
    }

    @Test
    void withoutAnAgentIdentityTheRefusalCeilingAnswersWithTheBudgetExhaustedResult() {
        // `callTool` was built with no agent/session (see setUp): there is no turn identity to
        // throw away, so past the ceiling the refusal is the shared tool_budget_exhausted result
        // the tool loop honours - bounded, never thrown.
        for (int i = 0; i < CallToolToolCallback.MAX_CONSECUTIVE_REFUSALS; i++) {
            assertTrue(
                    callTool.call("{\"name\":\"nope\",\"argsJson\":\"{}\"}")
                            .startsWith("There is no tool named"));
        }

        String exhausted = callTool.call("{\"name\":\"nope\",\"argsJson\":\"{}\"}");

        assertTrue(
                com.agenticai.core.guardrails.ToolBudgetExhausted.isBudgetExhausted(exhausted),
                exhausted);
        assertEquals(
                java.util.Optional.of(
                        com.agenticai.core.guardrails.ToolResultMarker.Kind.BUDGET_EXHAUSTED),
                com.agenticai.core.guardrails.ToolResultMarker.kindOf(exhausted));
        // Every later refusal is answered the same way; a successful dispatch still resets.
        assertTrue(
                com.agenticai.core.guardrails.ToolBudgetExhausted.isBudgetExhausted(
                        callTool.call("{\"name\":\"nope\",\"argsJson\":\"{}\"}")));
        search.call("{\"query\":\"send email\"}");
        assertEquals(
                "google_gmail:{}",
                callTool.call("{\"name\":\"google_gmail\",\"argsJson\":\"{}\"}"));
        assertTrue(
                callTool.call("{\"name\":\"nope\",\"argsJson\":\"{}\"}")
                        .startsWith("There is no tool named"));
    }

    @Test
    void aDispatchedCallResetsTheRefusalCount() {
        CallToolToolCallback bounded =
                new CallToolToolCallback(state, java.util.UUID.randomUUID(), "session-1");
        search.call("{\"query\":\"send email\"}");
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < CallToolToolCallback.MAX_CONSECUTIVE_REFUSALS; i++) {
                bounded.call("{\"name\":\"nope\",\"argsJson\":\"{}\"}");
            }
            assertEquals(
                    "google_gmail:{}",
                    bounded.call("{\"name\":\"google_gmail\",\"argsJson\":\"{}\"}"));
        }
    }

    @Test
    void callToolForwardsOnlyTheParsedObjectNotTrailingText() {
        search.call("{\"query\":\"send email\"}");

        callTool.call(
                "{\"name\":\"google_gmail\",\"argsJson\":\"{\\\"to\\\":\\\"a@b.com\\\"} trailing garbage\"}");

        assertEquals(List.of("{\"to\":\"a@b.com\"}"), gmail.inputs);
    }
}
