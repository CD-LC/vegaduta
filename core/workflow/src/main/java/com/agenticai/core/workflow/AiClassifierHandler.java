package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowAiNodeSupport.fence;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.intConfig;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.listOfMaps;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.oneLine;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.present;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.str;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.unitConfig;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import static com.agenticai.core.workflow.WorkflowAiNodeHandler.*;

/**
 * The {@code question_classifier} node: a constrained, temperature-0 JSON model call that picks exactly ONE of the
 * author's classes and routes down the matching edge. The reply is validated strictly; anything else (provider
 * failure, unparseable text, unknown class, low confidence) routes to the default class. Extracted from
 * {@link WorkflowAiNodeHandler}.
 */
final class AiClassifierHandler {

    /** The registry slot of the owning node handler; shipped text until it is wired. */
    private PromptSlot prompts = new PromptSlot();

    void usePrompts(PromptSlot slot) {
        this.prompts = slot;
    }

    private final ObjectProvider<WorkflowAiModel> modelProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int maxInputChars;
    private final double defaultMinConfidence;

    AiClassifierHandler(
            ObjectProvider<WorkflowAiModel> modelProvider,
            ObjectMapper objectMapper,
            boolean enabled,
            int maxInputChars,
            double defaultMinConfidence) {
        this.modelProvider = modelProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxInputChars = maxInputChars;
        this.defaultMinConfidence = defaultMinConfidence;
    }

    /** The classifier's {@link AskAndValidate}: one call, strict class validation, never a repair. */
    private final class Asker extends AskAndValidate<Classification> {
        private final List<AiClass> classes;
        private final String defaultHandle;
        private final double minConfidence;

        Asker(List<AiClass> classes, String defaultHandle, double minConfidence) {
            this.classes = classes;
            this.defaultHandle = defaultHandle;
            this.minConfidence = minConfidence;
        }

        @Override
        String system() {
            return prompts.text(CLASSIFIER_KEY);
        }

        @Override
        String label() {
            return "Question classifier";
        }

        @Override
        Classification validate(String reply) {
            return classify(reply, classes, defaultHandle, minConfidence, objectMapper);
        }
    }

    NodeExecutionResult execute(WorkflowNodeDto node, String input) {
        Map<String, Object> config = node.config();
        List<AiClass> classes =
                parseClasses(config.get("classes"), objectMapper, new ArrayList<>());
        if (classes.isEmpty()) {
            return NodeExecutionResult.skipped(
                    input, "No classes configured for this Question Classifier node.");
        }
        String defaultHandle = resolveDefaultHandle(str(config.get("defaultClass")), classes);
        double minConfidence = unitConfig(config, "minConfidence", defaultMinConfidence);
        String configuredText = str(config.get("text"));
        String text =
                configuredText.isEmpty() ? (input == null ? "" : input.strip()) : configuredText;
        int inputChars = intConfig(config, "maxInputChars", maxInputChars, 200, maxInputChars);

        List<String> logLines = new ArrayList<>();
        logLines.add(
                "Classifying "
                        + text.length()
                        + " char(s) into "
                        + classes.size()
                        + " class(es); default '"
                        + defaultHandle
                        + "', minimum confidence "
                        + minConfidence
                        + ".");
        if (text.length() > inputChars) {
            logLines.add("Input clipped to " + inputChars + " chars for the model.");
        }

        WorkflowAiModel model = modelProvider.getIfAvailable();
        Classification result;
        if (!enabled) {
            result =
                    new Classification(
                            defaultHandle, null, true, "AI nodes are disabled in this deployment");
        } else if (model == null) {
            result =
                    new Classification(
                            defaultHandle, null, true, "no model is available in this deployment");
        } else if (text.isEmpty()) {
            result = new Classification(defaultHandle, null, true, "the input text is empty");
        } else {
            String user =
                    classifierUserMessage(
                            classes, str(config.get("instruction")), text, inputChars);
            AskAndValidate.Attempt<Classification> attempt =
                    new Asker(classes, defaultHandle, minConfidence)
                            .ask(
                                    model,
                                    node,
                                    user,
                                    CLASSIFIER_MAX_TOKENS,
                                    str(config.get("provider")),
                                    str(config.get("model")),
                                    logLines);
            result =
                    attempt.providerFailed()
                            ? new Classification(
                                    defaultHandle,
                                    null,
                                    true,
                                    "the model call failed ("
                                            + attempt.failure().getClass().getSimpleName()
                                            + ")")
                            : attempt.result();
        }

        AiClass chosen =
                classes.stream()
                        .filter(c -> c.handle().equals(result.handle()))
                        .findFirst()
                        .orElse(classes.get(0));
        String summary =
                result.fellBack()
                        ? "Used default class '" + chosen.handle() + "': " + result.reason() + "."
                        : "Classified as '"
                                + chosen.handle()
                                + "'"
                                + (result.confidence() == null
                                        ? "."
                                        : " (confidence "
                                                + formatConfidence(result.confidence())
                                                + ").");
        logLines.add(summary);

        String output = input;
        if ("result".equalsIgnoreCase(str(config.get("outputMode")))) {
            ObjectNode out = objectMapper.createObjectNode();
            out.put("class", chosen.handle());
            out.put("label", chosen.label());
            if (result.confidence() == null) {
                out.putNull("confidence");
            } else {
                out.put("confidence", result.confidence());
            }
            out.put("fallback", result.fellBack());
            if (result.fellBack()) {
                out.put("reason", result.reason());
            }
            out.put("text", input == null ? "" : input);
            output = out.toString();
        }
        return NodeExecutionResult.ok(output, summary, chosen.handle(), logLines);
    }

    /**
     * Strict validation of a classifier reply: a JSON object whose {@code class} equals
     * (case-insensitively) one of the configured handles, with an optional numeric {@code
     * confidence} in [0, 1] (a value in (1, 100] is read as a percentage). Everything else falls
     * back to {@code defaultHandle} with a reason; this method never throws.
     */
    static Classification classify(
            String rawReply,
            List<AiClass> classes,
            String defaultHandle,
            double minConfidence,
            ObjectMapper mapper) {
        Optional<JsonNode> parsed = StructuredOutput.readObject(mapper, rawReply);
        if (parsed.isEmpty()) {
            return new Classification(
                    defaultHandle, null, true, "the model reply was not a JSON object");
        }
        JsonNode node = parsed.get();
        JsonNode cls = node.get("class");
        if (cls == null || !cls.isTextual()) {
            return new Classification(defaultHandle, null, true, "the model reply had no class");
        }
        String wanted = cls.asText().strip();
        AiClass match = null;
        for (AiClass candidate : classes) {
            if (candidate.handle().equalsIgnoreCase(wanted)) {
                match = candidate;
                break;
            }
        }
        if (match == null) {
            return new Classification(
                    defaultHandle,
                    null,
                    true,
                    "the model named an unknown class '" + oneLine(wanted, 40) + "'");
        }
        Double confidence = null;
        JsonNode conf = node.get("confidence");
        if (present(conf)) {
            double value;
            if (conf.isNumber()) {
                value = conf.asDouble();
            } else if (conf.isTextual()) {
                try {
                    value = Double.parseDouble(conf.asText().strip());
                } catch (NumberFormatException e) {
                    return new Classification(
                            defaultHandle, null, true, "the model's confidence was not a number");
                }
            } else {
                return new Classification(
                        defaultHandle, null, true, "the model's confidence was not a number");
            }
            if (value > 1.0 && value <= 100.0) {
                value = value / 100.0;
            }
            if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
                return new Classification(
                        defaultHandle, null, true, "the model's confidence was out of range");
            }
            confidence = value;
        }
        if (confidence != null && confidence < minConfidence) {
            return new Classification(
                    defaultHandle,
                    confidence,
                    true,
                    "low confidence ("
                            + formatConfidence(confidence)
                            + " is below "
                            + formatConfidence(minConfidence)
                            + ")");
        }
        return new Classification(match.handle(), confidence, false, null);
    }

    static String classifierUserMessage(
            List<AiClass> classes, String instruction, String text, int maxChars) {
        StringBuilder sb = new StringBuilder("CLASSES:\n");
        for (AiClass c : classes) {
            sb.append("- handle: ").append(c.handle());
            if (!c.description().isEmpty()) {
                sb.append(" | meaning: ").append(oneLine(c.description(), 300));
            } else if (!c.label().equals(c.handle())) {
                sb.append(" | meaning: ").append(oneLine(c.label(), 100));
            }
            sb.append('\n');
        }
        if (instruction != null && !instruction.isBlank()) {
            sb.append("\nAdditional instructions from the workflow author: ")
                    .append(oneLine(instruction, 500))
                    .append('\n');
        }
        sb.append("\nTEXT TO CLASSIFY:\n").append(fence(text, maxChars));
        return sb.toString();
    }

    private static String resolveDefaultHandle(String configured, List<AiClass> classes) {
        if (!configured.isEmpty()) {
            for (AiClass c : classes) {
                if (c.handle().equalsIgnoreCase(configured)) {
                    return c.handle();
                }
            }
        }
        return classes.get(0).handle();
    }

    private static String formatConfidence(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * The usable classes of a {@code classes} config value, in author order. Entries without a
     * usable handle, with a reserved or duplicate handle, or beyond {@link #MAX_CLASSES} are
     * skipped; each skip adds a human-readable line to {@code problems} (the save-time validator
     * reports them, the runtime ignores them).
     */
    static List<AiClass> parseClasses(Object raw, ObjectMapper mapper, List<String> problems) {
        List<AiClass> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (Map<?, ?> entry : listOfMaps(raw, mapper)) {
            index++;
            String handle = str(entry.get("handle"));
            if (handle.isEmpty()) {
                handle = str(entry.get("name"));
            }
            if (handle.isEmpty()) {
                handle = str(entry.get("id"));
            }
            if (handle.isEmpty()) {
                problems.add("has class #" + index + " with no handle.");
                continue;
            }
            if (handle.length() > MAX_HANDLE_CHARS
                    || handle.chars().anyMatch(Character::isISOControl)) {
                problems.add(
                        "has class #"
                                + index
                                + " with an unusable handle (over "
                                + MAX_HANDLE_CHARS
                                + " characters or containing control characters).");
                continue;
            }
            if (handle.equalsIgnoreCase(RESERVED_HANDLE)) {
                problems.add(
                        "has a class named '"
                                + RESERVED_HANDLE
                                + "', which is reserved for the failure port.");
                continue;
            }
            if (!seen.add(handle.toLowerCase(Locale.ROOT))) {
                problems.add("has two classes named '" + handle + "'.");
                continue;
            }
            if (out.size() >= MAX_CLASSES) {
                problems.add("has more than " + MAX_CLASSES + " classes.");
                break;
            }
            String label = str(entry.get("label"));
            out.add(
                    new AiClass(
                            handle,
                            label.isEmpty() ? handle : label,
                            str(entry.get("description"))));
        }
        return out;
    }
}
