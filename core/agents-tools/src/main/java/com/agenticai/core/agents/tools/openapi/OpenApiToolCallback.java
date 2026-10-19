package com.agenticai.core.agents.tools.openapi;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * Exposes one imported {@link OpenApiTool} as a native Spring AI tool - the same "plain in-process
 * ToolCallback, not an MCP server" choice {@code UserToolScriptCallback} and {@code
 * CustomCodeToolCallback} make, and a direct mirror of both. {@code getToolDefinition().name()} is
 * the tool's real, stable {@code toolName} (unique per tenant schema, generated at import), so an
 * agent's LLM calls it exactly like any other tool; the description says where it came from,
 * deliberately.
 *
 * <p>Everything real (URL assembly, SSRF guard, credential handling, the HTTP call) lives in {@link
 * OpenApiToolExecutor}, shared with the console's "Try it" endpoint so a tool that worked in the
 * tester works for the agent.
 */
public class OpenApiToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final String DEFAULT_INPUT_SCHEMA =
            """
        {"type": "object", "properties": {}}
        """;

    private final OpenApiToolSnapshot tool;
    private final OpenApiToolExecutor executor;
    private final OpenApiToolSourceService service;

    public OpenApiToolCallback(
            OpenApiToolSnapshot tool,
            OpenApiToolExecutor executor,
            OpenApiToolSourceService service) {
        this.tool = tool;
        this.executor = executor;
        this.service = service;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(tool.toolName())
                .description(describeForLlm())
                .inputSchema(
                        StringUtils.hasText(tool.inputSchemaJson())
                                ? tool.inputSchemaJson()
                                : DEFAULT_INPUT_SCHEMA)
                .build();
    }

    /**
     * Never throws - a tool callback runs inside Spring AI's streaming chat loop, where an escaping
     * exception aborts the whole turn. The executor already turns every expected failure into
     * {@code {"error": ...}}; this is the backstop for anything unexpected.
     */
    @Override
    public String call(String toolInput) {
        try {
            // Re-read the row (with its source) so an edit or a disable since the turn started
            // takes effect on this call; the snapshot only carries what the model was shown.
            OpenApiTool runnable = service.findRunnable(tool.id()).orElse(null);
            if (runnable == null) {
                ObjectNode gone = MAPPER.createObjectNode();
                gone.put("error", "This tool is no longer available: it was disabled or removed.");
                return gone.toString();
            }
            return executor.execute(runnable, toolInput);
        } catch (Exception e) {
            log.warn("OpenAPI tool '{}' failed: {}", tool.toolName(), e.getMessage());
            ObjectNode node = MAPPER.createObjectNode();
            node.put(
                    "error",
                    "This tool could not be run: "
                            + (StringUtils.hasText(e.getMessage())
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName()));
            return node.toString();
        }
    }

    private String describeForLlm() {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(tool.summary())) {
            sb.append(tool.summary().strip());
        } else if (StringUtils.hasText(tool.description())) {
            sb.append(tool.description().strip());
        } else {
            sb.append("Calls ")
                    .append(tool.httpMethod())
                    .append(' ')
                    .append(tool.pathTemplate())
                    .append('.');
        }
        if (StringUtils.hasText(tool.summary())
                && StringUtils.hasText(tool.description())
                && !tool.description().strip().equals(tool.summary().strip())) {
            sb.append(' ').append(tool.description().strip());
        }
        if (tool.deprecated()) {
            sb.append(" (Marked deprecated by the API's own documentation.)");
        }
        String sourceName =
                StringUtils.hasText(tool.sourceName()) ? tool.sourceName() : "an imported";
        sb.append(" (Imported from the \"")
                .append(sourceName)
                .append("\" OpenAPI source - performs a real ")
                .append(tool.httpMethod())
                .append(' ')
                .append(tool.pathTemplate())
                .append(" request against that API and returns {status, body}.)");
        return sb.toString();
    }
}
