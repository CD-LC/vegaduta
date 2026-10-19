package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.demo.studio.DemoStudioWorkflowRunner.Definition;
import com.agenticai.core.agents.demo.studio.DemoStudioWorkflowRunner.Step;
import com.agenticai.core.tenancy.demo.DemoSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Definition rules for {@link DemoStudioWorkflowRunner}: the shape check run at save and at run,
 * and the labelled visitor text a workflow carries for moderation. Split out of the runner to keep
 * it under the file-size cap; the runner's public methods delegate here.
 */
final class DemoStudioWorkflowSteps {

    private DemoStudioWorkflowSteps() {}

    static Definition validate(DemoSession session, Definition def, DemoStudioService studio) {
        List<Step> steps = def.steps();
        if (steps == null || steps.isEmpty()) {
            throw new DemoStudioService.StudioException("invalid", 400, "Add at least one step.");
        }
        if (steps.size() > DemoStudioWorkflowRunner.MAX_STEPS) {
            throw new DemoStudioService.StudioException(
                    "invalid", 400, "A sandbox workflow can have up to " + DemoStudioWorkflowRunner.MAX_STEPS + " steps.");
        }
        if (!"trigger".equals(type(steps.get(0)))) {
            throw new DemoStudioService.StudioException(
                    "invalid", 400, "The first step must be the trigger.");
        }
        List<Step> cleaned = new ArrayList<>();
        boolean hasAgent = false;
        int index = 0;
        for (Step step : steps) {
            String type = type(step);
            String id = step.id() == null || step.id().isBlank() ? "s" + index : step.id().trim();
            Map<String, Object> config =
                    step.config() == null ? Map.of() : new LinkedHashMap<>(step.config());
            switch (type) {
                case "trigger" -> {
                    if (index != 0) {
                        throw new DemoStudioService.StudioException(
                                "invalid", 400, "Only the first step can be a trigger.");
                    }
                }
                case "agent" -> {
                    String agentId = step.str("agentId");
                    if (agentId == null || agentId.isBlank()) {
                        throw new DemoStudioService.StudioException(
                                "invalid", 400, "Pick an agent for \"" + label(step) + "\".");
                    }
                    try {
                        studio.getAgent(session, UUID.fromString(agentId.trim()));
                    } catch (IllegalArgumentException e) {
                        throw new DemoStudioService.StudioException(
                                "invalid",
                                400,
                                "\"" + label(step) + "\" points at an unknown agent.");
                    }
                    hasAgent = true;
                }
                case "condition" -> {
                    String needle = step.str("contains");
                    if (needle == null || needle.isBlank()) {
                        throw new DemoStudioService.StudioException(
                                "invalid",
                                400,
                                "\"" + label(step) + "\" needs a word to look for.");
                    }
                }
                case "approval", "output" -> {
                    // nothing to validate - both are self-contained
                }
                default ->
                        throw new DemoStudioService.StudioException(
                                "invalid", 400, "Unknown step type: " + type);
            }
            cleaned.add(new Step(id, type, label(step), config));
            index++;
        }
        if (!hasAgent) {
            throw new DemoStudioService.StudioException(
                    "invalid", 400, "Add at least one agent step - that's the point.");
        }
        return new Definition(cleaned);
    }

    /**
     * Everything a visitor typed into a workflow: its name, every step label and every free-text
     * config value (instructions, approval messages, condition text). {@code agentId} values are
     * ids, not prose, and are skipped.
     */
    static Map<String, String> moderationTexts(String workflowName, Definition def) {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("workflow name", workflowName);
        if (def != null && def.steps() != null) {
            int index = 0;
            for (Step step : def.steps()) {
                index++;
                texts.put("step " + index + " label", step.label());
                if (step.config() != null) {
                    for (Map.Entry<String, Object> e : step.config().entrySet()) {
                        if (e.getValue() instanceof CharSequence value
                                && !"agentId".equals(e.getKey())) {
                            texts.put("step " + index + " " + e.getKey(), value.toString());
                        }
                    }
                }
            }
        }
        return texts;
    }

    static String type(Step step) {
        return step.type() == null ? "" : step.type().trim().toLowerCase(Locale.ROOT);
    }

    static String label(Step step) {
        String l = step.label() == null ? "" : step.label().strip();
        if (l.length() > 80) {
            l = l.substring(0, 80);
        }
        return l.isEmpty() ? type(step) : l;
    }
}
