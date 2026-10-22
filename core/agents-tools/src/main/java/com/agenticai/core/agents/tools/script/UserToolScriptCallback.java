package com.agenticai.core.agents.tools.script;

import com.agenticai.core.json.JsonMappers;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exposes one tenant-authored {@link UserToolScript} as a native Spring AI tool - same "plain
 * in-process ToolCallback, not an MCP server" choice every other native tool in this package makes
 * (see {@code GoogleDriveToolCallback}'s javadoc). {@code getToolDefinition().name()} is this
 * script's real, stable {@code toolName} (validated unique at create time - see {@code
 * UserToolScriptService#validateToolName}), so an agent's LLM calls it exactly like any other tool.
 * All the actual sandboxing lives in {@link UserToolScriptEvaluator}, shared with the frontend's
 * dry-run tester - this class is just the thin adapter to Spring AI's interface.
 */
public class UserToolScriptCallback implements ToolCallback {

    // A generic, permissive object schema - used whenever the tenant left
    // inputSchemaJson blank at create time. Genuinely permissive (no
    // "required", no declared properties) since a SpEL expression can
    // reference any key of whatever JSON object it's given; a stricter
    // default would just be wrong for scripts that expect a different shape.
    private static final String DEFAULT_INPUT_SCHEMA =
            """
{"type": "object", "description": "Any JSON object - see this tool's description for the fields its expression expects."}
""";

    private static final Logger log = LoggerFactory.getLogger(UserToolScriptCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final UserToolScriptSnapshot script;
    private final UserToolScriptEvaluator evaluator;
    private final UserToolScriptService service;

    public UserToolScriptCallback(
            UserToolScriptSnapshot script,
            UserToolScriptEvaluator evaluator,
            UserToolScriptService service) {
        this.script = script;
        this.evaluator = evaluator;
        this.service = service;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(script.toolName())
                .description(describeForLlm())
                .inputSchema(
                        StringUtils.hasText(script.inputSchemaJson())
                                ? script.inputSchemaJson()
                                : DEFAULT_INPUT_SCHEMA)
                .build();
    }

    /**
     * Runs the CURRENT expression of the row, not the one in the cached snapshot, so a disabled,
     * edited or deleted script stops (or changes) at once instead of after the lookup-cache TTL.
     * Fails closed like the custom-code and OpenAPI callbacks: if the row cannot be confirmed
     * enabled the script does not run, and the model gets an error JSON rather than an exception.
     */
    @Override
    public String call(String toolInput) {
        Optional<String> expression;
        try {
            expression = service.findRunnableExpression(script.id());
        } catch (RuntimeException e) {
            log.warn(
                    "User script tool '{}' could not be re-read: {}",
                    script.toolName(),
                    e.getMessage());
            return error("This tool could not be run right now.");
        }
        if (expression.isEmpty()) {
            return error("This tool is no longer available: it was disabled or removed.");
        }
        return evaluator.evaluate(expression.get(), toolInput);
    }

    private static String error(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", message);
        return node.toString();
    }

    private String describeForLlm() {
        String base =
                StringUtils.hasText(script.description())
                        ? script.description()
                        : "A custom tool defined by this tenant.";
        return base
                + " (User-created tool - computes a value from the JSON you send it; does not"
                + " perform any external action.)";
    }
}
