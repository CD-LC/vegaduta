package com.agenticai.core.agents.tools.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolResultMarker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The tool-search pair's own diagnostics are the PLATFORM's answers, not tool output: every
 * {@link CallToolToolCallback} refusal and every {@link SearchToolsToolCallback} input/availability
 * error is registered with {@link ToolResultMarker}, so the guard's untrusted-content screen passes
 * them through. This is the live-incident fix - a "call search_tools first" hint was withheld as a
 * prompt injection and the model could no longer see how to recover.
 *
 * <p>The real search results are deliberately NOT marked: they embed tool names and descriptions an
 * MCP server can author, which is exactly what the screen exists for.
 */
class ToolSearchPlatformAnswerTest {

    private static final class StubTool implements ToolCallback {
        private final String name;

        StubTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("A " + name + " tool")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return name + " ran";
        }
    }

    private ToolSearchState state;
    private SearchToolsToolCallback search;
    private CallToolToolCallback callTool;

    @BeforeEach
    void setUp() {
        Map<String, ToolCallback> deferred = new LinkedHashMap<>();
        deferred.put("fetch_web_page", new StubTool("fetch_web_page"));
        Map<String, ToolCallback> exposed = new LinkedHashMap<>();
        exposed.put("remember_fact", new StubTool("remember_fact"));
        state =
                new ToolSearchState(
                        exposed, deferred, new ToolSearchService(), null, "key-1", 0);
        search = new SearchToolsToolCallback(state);
        callTool = new CallToolToolCallback(state);
    }

    @Test
    void everyCallToolRefusalIsRegisteredAsThePlatformsAnswer() {
        String notLoaded = callTool.call("{\"name\":\"fetch_web_page\",\"argsJson\":\"{}\"}");
        String unknown = callTool.call("{\"name\":\"no_such_tool\",\"argsJson\":\"{}\"}");
        String noName = callTool.call("{\"argsJson\":\"{}\"}");
        String recursion = callTool.call("{\"name\":\"call_tool\",\"argsJson\":\"{}\"}");
        String badJson = callTool.call("nope");
        String badArgs = callTool.call("{\"name\":\"fetch_web_page\",\"argsJson\":\"[1]\"}");

        for (String refusal :
                new String[] {notLoaded, unknown, noName, recursion, badJson, badArgs}) {
            assertEquals(
                    ToolResultMarker.Kind.BLOCKED,
                    ToolResultMarker.kindOf(refusal).orElseThrow(
                            () -> new AssertionError("unmarked refusal: " + refusal)));
        }
        assertTrue(notLoaded.contains("has not been loaded yet"), notLoaded);
    }

    @Test
    void theSearchToolsOwnErrorAnswersAreMarkedButItsResultsAreNot() {
        assertEquals(
                ToolResultMarker.Kind.BLOCKED,
                ToolResultMarker.kindOf(search.call("not json")).orElseThrow());
        assertEquals(
                ToolResultMarker.Kind.BLOCKED,
                ToolResultMarker.kindOf(search.call("{\"query\":\"   \"}")).orElseThrow());

        // A real result embeds tool names and descriptions an outsider could have authored - it
        // must keep going through the screen.
        String found = search.call("{\"query\":\"web page\"}");
        String noMatch = search.call("{\"query\":\"zzzqqq\"}");
        assertTrue(ToolResultMarker.kindOf(found).isEmpty(), found);
        assertTrue(ToolResultMarker.kindOf(noMatch).isEmpty(), noMatch);
    }

    /**
     * The incident itself, end to end: {@code call_tool}'s "not loaded yet" hint inside a {@link
     * GuardedToolCallback} whose screen withholds EVERYTHING still reaches the model verbatim -
     * and the screen is not even invoked for it.
     */
    @Test
    void theNotLoadedHintSurvivesAGuardThatWithholdsEverything() {
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any()))
                .thenThrow(new GuardrailViolationException("call_tool", "flagged as injection"));
        GuardedToolCallback guarded =
                new GuardedToolCallback(
                        callTool,
                        new AtomicInteger(),
                        10,
                        guardrails,
                        mock(ApprovalPolicyService.class),
                        mock(HumanApprovalGateService.class),
                        java.util.UUID.randomUUID(),
                        "sess-1");

        String result = guarded.call("{\"name\":\"fetch_web_page\",\"argsJson\":\"{}\"}");

        assertTrue(result.contains("has not been loaded yet"), result);
        assertTrue(result.contains("select:fetch_web_page"), result);
        assertTrue(ToolResultMarker.isPlatformAnswer(result));
        verify(guardrails, never()).sanitizeToolOutput(anyString(), anyString(), any());
    }

    @Test
    void aDispatchedTargetResultIsStillScreenedUnderTheTargetsName() {
        // The refused path bypasses the screen; a DISPATCHED call returns the target's own
        // output, which must still be screened (here by call_tool's guard, after the target's
        // own guard already ran it).
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any()))
                .thenAnswer(i -> i.getArgument(1));
        GuardedToolCallback guarded =
                new GuardedToolCallback(
                        callTool,
                        new AtomicInteger(),
                        10,
                        guardrails,
                        mock(ApprovalPolicyService.class),
                        mock(HumanApprovalGateService.class),
                        java.util.UUID.randomUUID(),
                        "sess-1");
        search.call("{\"query\":\"select:fetch_web_page\"}");

        String result = guarded.call("{\"name\":\"fetch_web_page\",\"argsJson\":\"{}\"}");

        assertEquals("fetch_web_page ran", result);
        verify(guardrails).sanitizeToolOutput("call_tool", "fetch_web_page ran", null);
    }
}
