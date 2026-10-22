package com.agenticai.core.agents.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one invariant that matters here: {@link CustomCodeToolCallback#call} must never throw,
 * because it runs inside Spring AI's streaming chat loop where an escaping exception aborts the
 * whole turn. Whatever goes wrong, the model gets parseable JSON back.
 */
class CustomCodeToolCallbackTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final CustomCodeToolService service = mock(CustomCodeToolService.class);

    @Test
    void returnsParseableErrorJsonWhenTheSandboxIsUnavailable() throws Exception {
        when(service.executeById(any(), anyString()))
                .thenThrow(
                        new ToolSandboxUnavailableException(
                                "The code sandbox is unavailable (POST /v1/execute -> no response)."));

        String result = new CustomCodeToolCallback(snapshot(tool()), service).call("{}");

        JsonNode node = MAPPER.readTree(result);
        assertThat(node.hasNonNull("error")).isTrue();
        assertThat(node.get("error").asString()).contains("sandbox is unavailable");
    }

    @Test
    void returnsParseableErrorJsonWhenTheFeatureIsDisabled() throws Exception {
        when(service.executeById(any(), anyString()))
                .thenThrow(new CustomCodeToolsDisabledException());

        String result = new CustomCodeToolCallback(snapshot(tool()), service).call("{}");

        assertThat(MAPPER.readTree(result).get("error").asString())
                .contains("TOOL_SANDBOX_ENABLED=true");
    }

    @Test
    void errorJsonSurvivesAMessageFullOfQuotesAndNewlines() throws Exception {
        // Guards the "built with Jackson, never concatenated" rule - a compiler
        // diagnostic or stack trace routinely contains both.
        when(service.executeById(any(), anyString()))
                .thenThrow(
                        new IllegalStateException(
                                "line 1: expected \"}\"\n\tat Foo.bar(Foo.java:1)"));

        String result = new CustomCodeToolCallback(snapshot(tool()), service).call("{}");

        assertThat(MAPPER.readTree(result).get("error").asString())
                .contains("expected \"}\"")
                .contains("at Foo.bar");
    }

    @Test
    void passesTheServiceResultStraightThroughOnSuccess() {
        when(service.executeById(any(), anyString())).thenReturn("{\"total\":42}");

        assertThat(new CustomCodeToolCallback(snapshot(tool()), service).call("{\"a\":1}"))
                .isEqualTo("{\"total\":42}");
    }

    @Test
    void toolDefinitionUsesTheToolNameAndFallsBackToAPermissiveSchema() {
        var definition = new CustomCodeToolCallback(snapshot(tool()), service).getToolDefinition();

        assertThat(definition.name()).isEqualTo("my_custom_tool");
        assertThat(definition.description())
                .contains("Adds numbers.")
                .contains("Returns: the sum")
                // The model is told plainly that this is tenant-authored code.
                .contains("isolated sandbox");
        assertThat(definition.inputSchema()).contains("\"type\": \"object\"");
    }

    @Test
    void toolDefinitionPrefersTheAuthorsInputSchema() {
        CustomCodeTool withSchema =
                new CustomCodeTool(
                        "my_custom_tool",
                        "My tool",
                        "Adds numbers.",
                        CustomCodeToolLanguage.JAVA,
                        "class X {}",
                        "{\"type\":\"object\",\"required\":[\"a\"]}",
                        "the sum",
                        "sha",
                        5000,
                        128,
                        "tester");

        assertThat(
                        new CustomCodeToolCallback(snapshot(withSchema), service)
                                .getToolDefinition()
                                .inputSchema())
                .isEqualTo("{\"type\":\"object\",\"required\":[\"a\"]}");
    }

    private static CustomCodeToolSnapshot snapshot(CustomCodeTool tool) {
        return CustomCodeToolSnapshot.from(tool);
    }

    private static CustomCodeTool tool() {
        return new CustomCodeTool(
                "my_custom_tool",
                "My tool",
                "Adds numbers.",
                CustomCodeToolLanguage.PYTHON,
                "print(1)",
                null,
                "the sum",
                "sha",
                5000,
                128,
                "tester");
    }
}
