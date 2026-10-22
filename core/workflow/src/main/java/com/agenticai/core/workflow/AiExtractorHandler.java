package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowAiNodeHandler.*;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.fence;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.intConfig;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.listOfMaps;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.listOfStrings;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.oneLine;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.str;

import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code parameter_extractor} node: typed JSON extraction with strict validation, ONE repair
 * retry on an unusable or ill-typed reply, and required-but-absent parameters listed under {@code
 * _missing}. The model call and repair loop is the shared {@link AskAndValidate} template.
 * Extracted from {@link WorkflowAiNodeHandler}.
 */
final class AiExtractorHandler {

    private static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final ObjectProvider<WorkflowAiModel> modelProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int maxInputChars;

    AiExtractorHandler(
            ObjectProvider<WorkflowAiModel> modelProvider,
            ObjectMapper objectMapper,
            boolean enabled,
            int maxInputChars) {
        this.modelProvider = modelProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxInputChars = maxInputChars;
    }

    /**
     * The extractor's {@link AskAndValidate}: validate + coerce against the parameters, ONE repair
     * retry.
     */
    private final class Asker extends AskAndValidate<Extraction> {
        private final List<AiParam> params;

        Asker(List<AiParam> params) {
            this.params = params;
        }

        @Override
        String system() {
            return EXTRACTOR_SYSTEM;
        }

        @Override
        String label() {
            return "Parameter extractor";
        }

        @Override
        Extraction validate(String reply) {
            Optional<JsonNode> parsed = StructuredOutput.readObject(objectMapper, reply);
            return parsed.map(obj -> AiOutputValidator.validate(obj, params, objectMapper))
                    .orElse(null);
        }

        @Override
        List<String> issues(Extraction extraction) {
            return issuesOf(extraction);
        }

        @Override
        String repairPrompt(String user, List<String> issues) {
            return user
                    + "\n\nYour previous reply could not be used: "
                    + String.join("; ", issues)
                    + ".\nReply again with ONLY the corrected JSON object - no markdown fences, no commentary. "
                    + "Use null for any value the text does not state.";
        }

        @Override
        boolean replaces(Extraction repaired, Extraction current) {
            return repaired.problems().size() <= current.problems().size();
        }

        @Override
        void onProviderFailure(RuntimeException failure, List<String> logLines) {
            logLines.add("The model call failed (" + failure.getClass().getSimpleName() + ").");
        }
    }

    NodeExecutionResult execute(WorkflowNodeDto node, String input) {
        Map<String, Object> config = node.config();
        List<AiParam> params =
                parseParams(config.get("parameters"), objectMapper, new ArrayList<>());
        if (params.isEmpty()) {
            return NodeExecutionResult.skipped(
                    input, "No parameters configured for this Parameter Extractor node.");
        }
        WorkflowAiModel model = modelProvider.getIfAvailable();
        if (!enabled) {
            return NodeExecutionResult.skipped(
                    input, "AI nodes are disabled in this deployment - input passed through.");
        }
        if (model == null) {
            return NodeExecutionResult.skipped(
                    input, "No model is available in this deployment - input passed through.");
        }
        String configuredText = str(config.get("text"));
        String text =
                configuredText.isEmpty() ? (input == null ? "" : input.strip()) : configuredText;
        int inputChars = intConfig(config, "maxInputChars", maxInputChars, 200, maxInputChars);
        int maxTokens =
                intConfig(config, "maxOutputTokens", DEFAULT_EXTRACTOR_MAX_TOKENS, 128, 4096);
        String provider = str(config.get("provider"));
        String modelId = str(config.get("model"));

        List<String> logLines = new ArrayList<>();
        logLines.add(
                "Extracting "
                        + params.size()
                        + " parameter(s) from "
                        + text.length()
                        + " char(s).");
        if (text.length() > inputChars) {
            logLines.add("Input clipped to " + inputChars + " chars for the model.");
        }

        Extraction best = null;
        String errorCode = "empty_input";
        if (!text.isEmpty()) {
            errorCode = "unusable_model_reply";
            String user =
                    extractorUserMessage(params, str(config.get("instruction")), text, inputChars);
            AskAndValidate.Result<Extraction> run =
                    new Asker(params)
                            .run(model, node, user, maxTokens, provider, modelId, logLines);
            best = run.best();
            if (run.providerFailed()) {
                errorCode = "model_call_failed";
            }
        } else {
            logLines.add("The input text is empty - nothing to extract.");
        }

        ObjectNode output = buildExtractorOutput(params, best, errorCode);
        int found = 0;
        for (AiParam p : params) {
            if (!output.get(p.name()).isNull()) {
                found++;
            }
        }
        List<String> missing = new ArrayList<>();
        for (JsonNode n : output.get("_missing")) {
            missing.add(n.asString());
        }
        String summary =
                "Extracted "
                        + found
                        + " of "
                        + params.size()
                        + " parameter(s)"
                        + (missing.isEmpty()
                                ? "."
                                : "; missing required: " + String.join(", ", missing) + ".");
        logLines.add(summary);
        return NodeExecutionResult.ok(output.toString(), summary, null, logLines);
    }

    private static List<String> issuesOf(Extraction extraction) {
        if (extraction == null) {
            return List.of("the reply was not a JSON object");
        }
        List<String> issues = new ArrayList<>();
        extraction.problems().forEach((name, problem) -> issues.add(name + ": " + problem));
        return issues;
    }

    /**
     * {@code {param: value|null, ..., "_missing": [required names with no usable value]}} plus
     * {@code "_invalid"} (names whose value was unusable even after the repair) when there are any,
     * and {@code "_error"} when no reply could be parsed at all. Absent values are JSON null -
     * never an invented default.
     */
    ObjectNode buildExtractorOutput(List<AiParam> params, Extraction extraction, String errorCode) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode missing = objectMapper.createArrayNode();
        for (AiParam p : params) {
            JsonNode value = extraction == null ? null : extraction.values().get(p.name());
            if (value == null || value.isNull()) {
                out.putNull(p.name());
                if (p.required()) {
                    missing.add(p.name());
                }
            } else {
                out.set(p.name(), value);
            }
        }
        out.set("_missing", missing);
        if (extraction != null && !extraction.problems().isEmpty()) {
            ArrayNode invalid = objectMapper.createArrayNode();
            for (String name : extraction.problems().keySet()) {
                invalid.add(name);
            }
            out.set("_invalid", invalid);
        }
        if (extraction == null) {
            out.put("_error", errorCode);
        }
        return out;
    }

    static String extractorUserMessage(
            List<AiParam> params, String instruction, String text, int maxChars) {
        StringBuilder sb = new StringBuilder("PARAMETERS:\n");
        for (AiParam p : params) {
            sb.append("- ")
                    .append(p.name())
                    .append(" (")
                    .append(p.type().name().toLowerCase(Locale.ROOT));
            if (p.type() == ParamType.ENUM) {
                sb.append(", one of: ");
                for (int i = 0; i < p.options().size(); i++) {
                    sb.append(i == 0 ? "" : ", ")
                            .append('"')
                            .append(oneLine(p.options().get(i), 60))
                            .append('"');
                }
            }
            sb.append(p.required() ? ", required" : ", optional").append(')');
            if (!p.description().isEmpty()) {
                sb.append(": ").append(oneLine(p.description(), 300));
            }
            sb.append('\n');
        }
        if (instruction != null && !instruction.isBlank()) {
            sb.append("\nAdditional instructions from the workflow author: ")
                    .append(oneLine(instruction, 500))
                    .append('\n');
        }
        sb.append("\nTEXT TO EXTRACT FROM:\n").append(fence(text, maxChars));
        return sb.toString();
    }

    /**
     * The usable parameters of a {@code parameters} config value, in author order. Skipped entries
     * (bad or duplicate name, unknown type, an enum without options, beyond {@link #MAX_PARAMS})
     * each add a line to {@code problems}.
     */
    static List<AiParam> parseParams(Object raw, ObjectMapper mapper, List<String> problems) {
        List<AiParam> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (Map<?, ?> entry : listOfMaps(raw, mapper)) {
            index++;
            String name = str(entry.get("name"));
            if (!PARAM_NAME.matcher(name).matches()) {
                problems.add(
                        "has parameter #"
                                + index
                                + " with an invalid name (use a letter first, then letters, digits or _ , up to 64 characters).");
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                problems.add("has two parameters named '" + name + "'.");
                continue;
            }
            ParamType type =
                    ParamType.of(
                            str(entry.get("type")).isEmpty() ? "string" : str(entry.get("type")));
            if (type == null) {
                problems.add(
                        "has parameter '"
                                + name
                                + "' with an unknown type (expected string, number, boolean, array or enum).");
                continue;
            }
            List<String> options = new ArrayList<>();
            if (type == ParamType.ENUM) {
                Object rawOptions = entry.get("options");
                List<String> candidates =
                        rawOptions instanceof String s && !s.stripLeading().startsWith("[")
                                ? List.of(s.split("[,\\n]"))
                                : listOfStrings(rawOptions, mapper);
                Set<String> optionSeen = new LinkedHashSet<>();
                for (String candidate : candidates) {
                    String option = candidate == null ? "" : candidate.strip();
                    if (!option.isEmpty()
                            && optionSeen.add(option.toLowerCase(Locale.ROOT))
                            && options.size() < MAX_ENUM_OPTIONS) {
                        options.add(option);
                    }
                }
                if (options.isEmpty()) {
                    problems.add("has enum parameter '" + name + "' with no options.");
                    continue;
                }
            }
            if (out.size() >= MAX_PARAMS) {
                problems.add("has more than " + MAX_PARAMS + " parameters.");
                break;
            }
            Object requiredRaw = entry.get("required");
            boolean required =
                    requiredRaw instanceof Boolean b
                            ? b
                            : requiredRaw != null
                                    && "true".equalsIgnoreCase(requiredRaw.toString().strip());
            out.add(
                    new AiParam(
                            name,
                            type,
                            str(entry.get("description")),
                            required,
                            List.copyOf(options)));
        }
        return out;
    }
}
