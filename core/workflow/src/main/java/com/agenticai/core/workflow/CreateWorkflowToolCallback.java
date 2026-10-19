package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.tools.workflowdraft.WorkflowDraftToolFactory;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.GenerateGraphResponse;
import com.agenticai.core.workflow.dto.ValidationResult;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code create_workflow} - turns a plain-language instruction into a DRAFT workflow the person can
 * open, review and run from the console.
 *
 * <p>It does what the console's "generate graph" button does ({@link
 * WorkflowGraphGeneratorService}, which validates the graph and puts an approval step in front of
 * anything that sends) and then saves the result through {@link WorkflowCrudService#create}, so the
 * tenant's workflow limit, the creator's ownership and the version history behave exactly as for a
 * workflow created in the console. The draft is never activated, scheduled or run here. The
 * generator runs first, so a failed generation leaves no empty draft behind.
 *
 * <p>Identity never travels as an argument: the owner is the tenant context's subject. Failures come
 * back as an {@code {"error": ...}} envelope for the model to relay, never as exceptions.
 */
public class CreateWorkflowToolCallback implements ToolCallback {

    public static final String TOOL_NAME = WorkflowDraftToolFactory.TOOL_NAME;
    static final int MAX_NAME_CHARS = 200;
    static final int MAX_INSTRUCTION_CHARS = 4000;
    static final int MAX_DESCRIPTION_CHARS = 500;
    private static final Logger log = LoggerFactory.getLogger(CreateWorkflowToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final WorkflowCrudService crud;
    private final WorkflowGraphGeneratorService generator;

    public CreateWorkflowToolCallback(
            WorkflowCrudService crud, WorkflowGraphGeneratorService generator) {
        this.crud = crud;
        this.generator = generator;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Builds a DRAFT workflow from a plain-language description and saves it, so the user can"
                                + " review and run it from the console. Use it when the request is repeatable or has"
                                + " several steps (every Monday do X; when a form arrives, check it and email Y; run"
                                + " these steps for each item or across several agents). Describe the whole flow in"
                                + " 'instruction': the trigger, each step in order, who or what each step uses, what"
                                + " should happen at the end. It does NOT activate, schedule or run the workflow."
                                + " Returns the workflow id, the nodes it created (type and label), any warnings and"
                                + " what still has to be connected, plus the console path: give the user that path as"
                                + " a link and describe the nodes in plain words.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "name": {
                            "type": "string",
                            "description": "Short workflow name, such as 'Weekly sales digest'."
                        },
                        "instruction": {
                            "type": "string",
                            "description": "The whole workflow in plain language: trigger, steps in order, end result."
                        }
                    },
                    "required": ["name", "instruction"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String name;
        String instruction;
        try {
            JsonNode args = MAPPER.readTree(toolInput == null ? "{}" : toolInput);
            name = text(args, "name");
            instruction = text(args, "instruction");
        } catch (Exception e) {
            return error("could not parse the input - expected {\"name\":\"...\",\"instruction\":\"...\"}");
        }
        if (name.isEmpty()) {
            return error("name is required");
        }
        if (instruction.isEmpty()) {
            return error("instruction is required");
        }
        if (name.length() > MAX_NAME_CHARS) {
            name = name.substring(0, MAX_NAME_CHARS);
        }
        if (instruction.length() > MAX_INSTRUCTION_CHARS) {
            instruction = instruction.substring(0, MAX_INSTRUCTION_CHARS);
        }

        GenerateGraphResponse generated;
        try {
            generated = generator.generate(instruction, ReasoningDepth.FAST, null);
        } catch (WorkflowValidationException e) {
            return error(
                    "the generated workflow was not valid: " + String.join("; ", e.getErrors()));
        } catch (RuntimeException e) {
            log.warn("create_workflow: graph generation failed", e);
            return error("could not generate a workflow graph: " + message(e));
        }

        WorkflowDefinition saved;
        try {
            String description =
                    instruction.length() > MAX_DESCRIPTION_CHARS
                            ? instruction.substring(0, MAX_DESCRIPTION_CHARS)
                            : instruction;
            saved = crud.create(name, description, generated.graph());
        } catch (TenantLimitExceededException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("create_workflow: saving the draft failed", e);
            return error("could not save the workflow: " + message(e));
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("workflowId", saved.getId().toString());
        out.put("name", saved.getName());
        out.put("status", "DRAFT");
        out.put("path", "/workflows/" + saved.getId());
        out.set("nodes", nodes(generated.graph()));
        out.put("edgeCount", generated.graph() == null ? 0 : generated.graph().edges().size());
        out.set("notes", strings(generated.notes()));
        out.set("warnings", strings(warnings(saved)));
        out.set("requirements", requirements(generated));
        out.put(
                "message",
                "Draft saved. It has not been activated or run: the user reviews it and presses Run (or"
                        + " activates it) from the path above.");
        return out.toString();
    }

    /** Problems the saved draft still has, from the same check the console's Validate button runs. */
    private List<String> warnings(WorkflowDefinition saved) {
        try {
            ValidationResult result = crud.validate(saved.getId());
            return result.valid() ? List.of() : result.errors();
        } catch (RuntimeException e) {
            log.debug("create_workflow: validation of the new draft failed", e);
            return List.of();
        }
    }

    private static ArrayNode nodes(WorkflowGraphDto graph) {
        ArrayNode nodes = MAPPER.createArrayNode();
        if (graph != null) {
            for (WorkflowNodeDto node : graph.nodes()) {
                ObjectNode entry = nodes.addObject();
                entry.put("id", node.id());
                entry.put("type", node.type());
                entry.put("label", node.label());
            }
        }
        return nodes;
    }

    private static ArrayNode requirements(GenerateGraphResponse generated) {
        ArrayNode items = MAPPER.createArrayNode();
        if (generated.requirements() != null && generated.requirements().items() != null) {
            for (GenerateGraphResponse.RequirementItem item : generated.requirements().items()) {
                ObjectNode entry = items.addObject();
                entry.put("kind", item.kind());
                entry.put("message", item.message());
                entry.put("href", item.href());
            }
        }
        return items;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode array = MAPPER.createArrayNode();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    private static String text(JsonNode args, String field) {
        JsonNode value = args == null ? null : args.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String error(String message) {
        return MAPPER.createObjectNode().put("error", message).toString();
    }
}
