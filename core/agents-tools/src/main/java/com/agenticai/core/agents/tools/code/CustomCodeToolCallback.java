package com.agenticai.core.agents.tools.code;

import com.agenticai.core.json.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exposes one tenant-authored {@link CustomCodeTool} as a native Spring AI tool - the same "plain
 * in-process ToolCallback, not an MCP server" choice every other native tool in this package makes,
 * and a direct mirror of {@code UserToolScriptCallback}.
 *
 * <p>{@code getToolDefinition().name()} is the tool's real, stable {@code toolName} (unique per
 * tenant schema, validated at create time), so an agent's LLM calls it exactly like any other tool
 * and has no way to tell it is tenant-authored - except that the description says so, deliberately.
 *
 * <p>All of the actual isolation lives in the tool-sandbox service; this class is a thin adapter
 * that never sees, parses, or runs the program itself.
 */
public class CustomCodeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CustomCodeToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    // Same permissive default UserToolScriptCallback uses, and for the same
    // reason: a tenant's program can read any key of whatever JSON object it is
    // handed, so a stricter invented schema would simply be wrong for every
    // tool whose author didn't supply one.
    private static final String DEFAULT_INPUT_SCHEMA =
            """
        {"type": "object", "description": "Any JSON object - see this tool's description for the fields its program expects."}
        """;

    private final CustomCodeToolSnapshot tool;
    private final CustomCodeToolService service;

    public CustomCodeToolCallback(CustomCodeToolSnapshot tool, CustomCodeToolService service) {
        this.tool = tool;
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
     * Never throws. A tool callback runs inside Spring AI's streaming chat loop, where an escaping
     * exception aborts the whole turn - every other callback in this codebase returns an {@code
     * {"error": ...}} JSON string instead so the model can see what went wrong and recover on its
     * own, and this one is no different. That includes sandbox outages ({@link
     * ToolSandboxUnavailableException}) and the feature being switched off mid-conversation ({@link
     * CustomCodeToolsDisabledException}), both of which are real 4xx/5xx at the REST layer but must
     * degrade to a readable message here.
     */
    @Override
    public String call(String toolInput) {
        try {
            return service.executeById(tool.id(), toolInput);
        } catch (Exception e) {
            log.warn("Custom code tool '{}' failed: {}", tool.toolName(), e.getMessage());
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
        StringBuilder sb =
                new StringBuilder(
                        StringUtils.hasText(tool.description())
                                ? tool.description()
                                : "A custom tool defined by this tenant.");
        if (StringUtils.hasText(tool.outputDescription())) {
            sb.append(" Returns: ").append(tool.outputDescription());
        }
        sb.append(" (User-created tool - a ")
                .append(tool.language() == CustomCodeToolLanguage.PYTHON ? "Python" : "Java")
                .append(
                        " program written by this workspace, run in an isolated sandbox with hard time and memory limits.)");
        return sb.toString();
    }
}
