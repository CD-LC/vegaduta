package com.agenticai.core.agents.tools.code;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The {@code create_custom_tool} built-in - lets an agent author a new tool (a Java {@code
 * Tool.run(Map)}, a Python {@code run(input)}, a JavaScript/TypeScript {@code run(input)} program)
 * when no existing tool fits the task. The program is compiled and statically verified by the
 * tool-sandbox service and, once created and enabled, is resolved for <b>every</b> agent in the
 * workspace - see {@code AgentCustomToolResolver#resolveCustomCodeTools} (runnable = enabled AND
 * VALIDATED).
 *
 * <p><b>Every enable is a human decision.</b> The draft write itself is harmless - a disabled row
 * cannot be invoked and is reversible - so this call does not park. What requires a person is the
 * ENABLE: the tool lands disabled and this callback records a {@code custom_code_tool.enable}
 * approval request via {@code HumanApprovalGateService#evaluateInTurn}; only {@link
 * CustomCodeToolEnableApprovalExecutor} can flip it on. Because the gate sits on the ACTION and not
 * on the calling agent's dispatch mode, no autonomy tier (including fully-autonomous {@code
 * NEVER_GATE} agents, who are exempt from the classification gate's confirmations) can produce a
 * callable tool without a human in between. This is the same trade {@code
 * CodeToolGenerationService} documents for its draft-only editor path: AI-authored code must not
 * become agent-visible without a human review step. The approval is the review.
 *
 * <p><b>Failure contract</b> (identical to {@code FetchWebPageToolCallback}): this callback never
 * throws into the turn. A rejected program comes back as a plain-text result naming the verifier's
 * findings so the model can fix the source and try again.
 */
public class CreateCustomToolToolCallback implements ToolCallback {

    /** The stable tool name - also the classification key. */
    public static final String NAME = "create_custom_tool";

    private static final Logger log = LoggerFactory.getLogger(CreateCustomToolToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CustomCodeToolService service;
    // ObjectProvider, not a plain field: the approval module's beans are absent in slim/test
    // contexts, and a missing gate must degrade to "created but stays disabled", not crash the
    // tool.
    private final ObjectProvider<HumanApprovalGateService> gateService;

    public CreateCustomToolToolCallback(
            CustomCodeToolService service, ObjectProvider<HumanApprovalGateService> gateService) {
        this.service = service;
        this.gateService = gateService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Creates a new, reusable tool by writing a small program - use it when no existing tool can do "
                                + "what the user asked for (parse a file format, encode an internal convention, transform data "
                                + "a specific way). Write a 'java' program whose class Tool has a static run(Map input) method, "
                                + "a 'python' program with a top-level run(input) function, a 'javascript' program with a "
                                + "top-level function run(input), or 'typescript' (type annotations allowed; enums and other "
                                + "type-generating syntax are not). The program runs in a network-isolated sandbox with no file "
                                + "system access beyond its own source - no imports that touch the network, files, processes "
                                + "or environment will pass verification. The new tool is created DISABLED and a person must "
                                + "approve it in the console's Approvals before any agent can call it; once approved it is "
                                + "enabled for every agent in this workspace under the tool_name you chose. If verification "
                                + "reports errors, fix the source and submit again.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "tool_name": {
                            "type": "string",
                            "description": "The name every agent calls the new tool by: lowercase snake_case, e.g. csv_to_markdown."
                        },
                        "display_name": {
                            "type": "string",
                            "description": "A short human-facing name shown in the console's tool list."
                        },
                        "description": {
                            "type": "string",
                            "description": "What the tool does, when to use it and what its input fields mean - written for the model that will call it."
                        },
                        "language": {
                            "type": "string",
                            "enum": ["java", "python", "javascript", "typescript"],
                            "description": "The language the source_code is written in."
                        },
                        "source_code": {
                            "type": "string",
                            "description": "The complete program. Java: a public class Tool with public static Object run(Map<String,Object> input). Python: def run(input). JavaScript/TypeScript: function run(input). Return a JSON-serializable value."
                        },
                        "input_schema": {
                            "type": "string",
                            "description": "Optional JSON Schema (as a string) describing the tool's input. Defaults to a permissive object schema."
                        },
                        "output_description": {
                            "type": "string",
                            "description": "Optional one-line description of what the tool returns."
                        },
                        "timeout_ms": {
                            "type": "integer",
                            "description": "Optional per-call wall-clock limit in milliseconds; small is better."
                        },
                        "memory_mb": {
                            "type": "integer",
                            "description": "Optional address-space limit in megabytes."
                        }
                    },
                    "required": ["tool_name", "description", "language", "source_code"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String toolName = asTrimmedString(args.get("tool_name"));
            String description = asTrimmedString(args.get("description"));
            String languageText = asTrimmedString(args.get("language"));
            String sourceCode = asString(args.get("source_code"));
            if (toolName == null
                    || description == null
                    || languageText == null
                    || sourceCode == null
                    || sourceCode.isBlank()) {
                return "Could not create the tool: tool_name, description, language and source_code are all required.";
            }
            CustomCodeToolLanguage language;
            try {
                language =
                        CustomCodeToolLanguage.valueOf(
                                languageText.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return "Could not create the tool: language must be one of java, python, javascript, typescript (got '"
                        + languageText
                        + "').";
            }

            // enabled=false: the draft is inert until the human approval below flips it.
            CustomCodeTool tool =
                    service.create(
                            toolName,
                            asTrimmedString(args.get("display_name")),
                            description,
                            language,
                            sourceCode,
                            asString(args.get("input_schema")),
                            asTrimmedString(args.get("output_description")),
                            asInteger(args.get("timeout_ms")),
                            asInteger(args.get("memory_mb")),
                            false);

            if (tool.getStatus() == CustomCodeToolStatus.REJECTED) {
                return "The tool '"
                        + tool.getToolName()
                        + "' was saved as a draft but failed verification, so it was NOT enabled. "
                        + "Fix the source and call "
                        + NAME
                        + " again. Verifier said: "
                        + (tool.getValidationError() == null
                                ? "no details"
                                : tool.getValidationError());
            }

            HumanApprovalGateService gate = gateService.getIfAvailable();
            if (gate == null) {
                return "The tool '"
                        + tool.getToolName()
                        + "' was verified and saved, but the approval service is not available on "
                        + "this deployment - it stays disabled until a person enables it in the "
                        + "custom-code-tools editor.";
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolId", tool.getId().toString());
            payload.put("toolName", tool.getToolName());
            payload.put("language", tool.getLanguage().name());
            String summary =
                    "Enable model-authored tool '"
                            + tool.getToolName()
                            + "' ("
                            + tool.getLanguage()
                            + ") for every agent in this workspace";
            String requestedBy =
                    TenantContext.current().map(TenantContext::getSubject).orElse(null);

            // evaluateInTurn: the request carries the conversation it came from, so the human's
            // decision puts the outcome back into it and the agent carries on - see
            // ApprovalOutcomePort / ChannelSendPortImpl for the same pattern.
            ApprovalEvaluation evaluation =
                    gate.evaluateInTurn(
                            CustomCodeToolEnableApprovalExecutor.ACTION_TYPE,
                            NAME,
                            summary,
                            MAPPER.writeValueAsString(payload),
                            requestedBy);

            if (evaluation.approved()) {
                return "Tool '"
                        + tool.getToolName()
                        + "' was verified and enabled. Every agent in this workspace can now call "
                        + "it as "
                        + tool.getToolName()
                        + ".";
            }
            return "The tool '"
                    + tool.getToolName()
                    + "' was verified and saved, and is now waiting for a person's approval "
                    + "(request "
                    + evaluation.pendingRequestId()
                    + "). Nobody - including you - can call it until they approve it in the "
                    + "console's Approvals; on approval it is enabled for every agent in this "
                    + "workspace. Tell the user it is waiting for their review.";
        } catch (TenantLimitExceededException e) {
            return "Could not create the tool: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Could not create the tool: " + e.getMessage();
        } catch (CustomCodeToolsDisabledException | ToolSandboxUnavailableException e) {
            return "Could not create the tool: the tool-sandbox service is unavailable on this deployment. "
                    + "The program was not saved.";
        } catch (Exception e) {
            log.warn("create_custom_tool failed: {}", e.toString());
            return "Could not create the tool: " + e.getMessage();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String asTrimmedString(Object value) {
        String s = asString(value);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
