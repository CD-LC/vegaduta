package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.approval.ToolCallScope;
import com.agenticai.core.guardrails.ToolConfirmationDescriber;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The {@code run_desktop_recipe} built-in: replays a saved desktop recipe - a recorded, verified
 * sequence of GUI actions on the person's own screen - deterministically through the desktop-agent
 * sidecar, with no model call per step. It is the {@code local_desktop_*} twin of the {@code
 * run_browser_recipe} tool in {@code core/agents-browser} and follows the same contract: the replay
 * STOPS at the first failed step so the agent can carry on by hand with the live {@code
 * local_desktop_*} tools, and steps flagged irreversible (submit, pay, delete, send) are not run
 * unless {@code confirmIrreversible} is true - the tool itself is classified EGRESS + irreversible
 * like its browser twin, so the platform's human-confirmation gate approves the call (arguments
 * included) before it runs at all. It is additionally pinned by {@code
 * DesktopToolSource#GATED_NAMES} while {@code app.assistant.desktop.approval-required} holds, like
 * the single actuating tools.
 *
 * <p>{@code mode} is {@code AUTO} (run at full speed) or {@code MONITOR} (pause between steps -
 * {@code app.assistant.desktop.recipes.monitor-step-delay-ms} - so the person can follow the replay
 * on the console's live monitor, {@code app.assistant.desktop.monitor.enabled}); omitted, the
 * recipe's stored {@code defaultMode} applies, exactly like the REST replay.
 *
 * <p>The calling agent is read from {@link ToolCallScope}, which the tool guard opens around every
 * {@code call()} - the same agent the browser twin is handed through its constructor, except this
 * callback is built once per install by {@link DesktopToolSource} (a {@code WorkspaceToolSource}
 * has no per-agent build seam), so the scope is how a shared callback learns who is calling. A call
 * outside a guarded scope answers an error instead of guessing.
 *
 * <p>{@link DesktopRecipeService#replayForAgent} deliberately does not record the outcome, so this
 * callback calls {@link DesktopRecipeService#recordRun} after every replay, exactly like {@code
 * RunBrowserRecipeToolCallback}. Behind {@code app.assistant.desktop.recipes.enabled} (default
 * off), on top of the desktop tools' own opt-in (single-user install + configured sidecar). Same
 * failure contract as every other built-in: it never throws at the model, every failure is a short
 * JSON object.
 */
public class RunDesktopRecipeToolCallback implements ToolCallback, ToolConfirmationDescriber {

    public static final String NAME = "run_desktop_recipe";

    private static final Logger log = LoggerFactory.getLogger(RunDesktopRecipeToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final DesktopRecipeService recipes;
    private final boolean approvalRequired;

    public RunDesktopRecipeToolCallback(DesktopRecipeService recipes) {
        this(recipes, true);
    }

    public RunDesktopRecipeToolCallback(DesktopRecipeService recipes, boolean approvalRequired) {
        this.recipes = recipes;
        this.approvalRequired = approvalRequired;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Replays a saved desktop recipe (a recorded, verified sequence of GUI actions on "
                                + "this laptop) without step-by-step AI, through the desktop-agent sidecar. "
                                + "If a step fails the replay stops and reports which one - then continue by "
                                + "hand with the local_desktop_* tools. Steps that submit or commit something "
                                + "are not run until confirmIrreversible is true. mode MONITOR pauses between "
                                + "steps so a person can watch the live monitor."
                                + (approvalRequired
                                        ? " Each call is shown to the user for approval before it runs."
                                        : ""))
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "recipe": {
                            "type": "string",
                            "description": "Recipe name or id (UUID)."
                        },
                        "params": {
                            "type": "object",
                            "description": "Values for the recipe's parameters, by name.",
                            "additionalProperties": {"type": "string"}
                        },
                        "mode": {
                            "type": "string",
                            "enum": ["AUTO", "MONITOR"],
                            "description": "AUTO runs at full speed; MONITOR pauses between steps so a person can watch the live monitor. Omit for the recipe's stored default."
                        },
                        "confirmIrreversible": {
                            "type": "boolean",
                            "description": "True only after the user approved the steps that submit or commit something."
                        },
                        "fromStep": {
                            "type": "integer",
                            "description": "Resume at this 1-based step (after a confirmation pause). Default 1."
                        }
                    },
                    "required": ["recipe"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        if (!recipes.isEnabled()) {
            return error("Desktop recipes are not enabled on this install.");
        }
        // The guard opens ToolCallScope around every delegated call; an empty scope means a
        // context-free path (a sweeper, a bare harness) reached the tool, and a recipe is
        // per-agent - refuse clearly rather than run as nobody.
        UUID agentId = ToolCallScope.current().map(ToolCallScope.Current::agentId).orElse(null);
        if (agentId == null) {
            return error(
                    "Could not identify the calling agent - run this tool from an agent conversation.");
        }
        try {
            JsonNode args = MAPPER.readTree(toolInput == null ? "{}" : toolInput);
            String ref = text(args, "recipe");
            if (ref.isEmpty()) {
                return error("Missing 'recipe' - the name or id of a saved desktop recipe.");
            }
            DesktopRecipe recipe;
            try {
                recipe = recipes.findRunnable(agentId, ref).orElse(null);
            } catch (IllegalArgumentException ambiguous) {
                // e.g. "more than one desktop recipe is named 'x' - use its id instead"; a
                // service-authored message, safe to show.
                return error(ambiguous.getMessage());
            }
            if (recipe == null) {
                return error("No desktop recipe '" + ref + "' is available to this agent.");
            }
            // Omitted mode means the recipe's stored default - resolved here because the service's
            // replayForAgent takes a boolean, exactly like the REST path's resolveMonitor.
            String mode = text(args, "mode");
            if (mode.isEmpty()) {
                mode = recipe.getDefaultMode();
            }
            mode = mode.toUpperCase(Locale.ROOT);
            if (!DesktopRecipe.MODE_AUTO.equals(mode) && !DesktopRecipe.MODE_MONITOR.equals(mode)) {
                return error("mode must be AUTO or MONITOR.");
            }
            boolean confirm = args.path("confirmIrreversible").asBoolean(false);
            int fromStep = args.path("fromStep").isNumber() ? args.path("fromStep").asInt() : 1;
            DesktopRecipeReplayer.Outcome outcome =
                    recipes.replayForAgent(
                            agentId,
                            recipe.getId().toString(),
                            stringParams(args.path("params")),
                            confirm,
                            fromStep,
                            DesktopRecipe.MODE_MONITOR.equals(mode));
            // The service deliberately leaves run bookkeeping to the caller (see its javadoc) -
            // mirror run_browser_recipe: record the real id, never let it poison the result.
            recordRun(recipe, outcome);
            return toJson(response(recipe, mode, outcome));
        } catch (IOException e) {
            return error("Could not read the call's arguments: " + e.getMessage());
        } catch (RuntimeException e) {
            // Not e.getMessage(): a service-side failure must not be able to echo anything
            // secret-adjacent a recipe step typed.
            log.warn("run_desktop_recipe failed unexpectedly: {}", e.getClass().getSimpleName());
            return error("The recipe could not be run: an unexpected error occurred.");
        }
    }

    @Override
    public String describeForConfirmation(String toolInput) {
        try {
            JsonNode args = MAPPER.readTree(toolInput == null ? "{}" : toolInput);
            String name = text(args, "recipe");
            if (name.isEmpty()) {
                return null;
            }
            // This runs inside the approval park, before the guard opens ToolCallScope, so the
            // recipe is looked up tenant-wide only for the card's step count - the replay itself
            // still resolves it against the calling agent.
            String mode = text(args, "mode");
            Integer steps = null;
            try {
                DesktopRecipe recipe = recipes.findRunnable(null, name).orElse(null);
                if (recipe != null) {
                    name = recipe.getName();
                    steps = recipe.getStepCount();
                    if (mode.isEmpty()) {
                        mode = recipe.getDefaultMode();
                    }
                }
            } catch (RuntimeException lookup) {
                // No context to look it up (or a name collision): the card falls back to the ref.
            }
            mode = mode.isEmpty() ? DesktopRecipe.MODE_AUTO : mode.toUpperCase(Locale.ROOT);
            int fromStep = args.path("fromStep").asInt(1);
            return "Run desktop recipe '"
                    + name
                    + "' ("
                    + (steps != null ? steps + " steps, " : "")
                    + (fromStep > 1 ? "from step " + fromStep + ", " : "")
                    + "mode "
                    + mode
                    + ")";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Run bookkeeping, mirroring {@code RunBrowserRecipeToolCallback}: the recipe's own id, the
     * failure line or null. {@link DesktopRecipeService#recordRun} already swallows its own
     * failures; the extra catch means bookkeeping can never turn a real outcome into an error.
     */
    private void recordRun(DesktopRecipe recipe, DesktopRecipeReplayer.Outcome outcome) {
        try {
            recipes.recordRun(recipe.getId(), outcome.success() ? null : outcome.failure());
        } catch (RuntimeException e) {
            log.debug("run_desktop_recipe could not record the run for '{}'", recipe.getName(), e);
        }
    }

    private static Map<String, Object> response(
            DesktopRecipe recipe, String mode, DesktopRecipeReplayer.Outcome outcome) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", outcome.success());
        Map<String, Object> recipeInfo = new LinkedHashMap<>();
        recipeInfo.put("id", recipe.getId().toString());
        recipeInfo.put("name", recipe.getName());
        recipeInfo.put("steps", recipe.getStepCount());
        out.put("recipe", recipeInfo);
        out.put("mode", mode);
        out.put("stepsRun", outcome.stepsRun());
        if (outcome.failure() != null) {
            out.put("failure", outcome.failure());
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        if (outcome.steps() != null) {
            for (DesktopRecipeReplayer.StepResult s : outcome.steps()) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("index", s.index());
                step.put("action", s.action());
                step.put("ok", s.ok());
                step.put("note", s.note());
                steps.add(step);
            }
        }
        out.put("stepResults", steps);
        return out;
    }

    private static String text(JsonNode args, String field) {
        JsonNode value = args.path(field);
        return value.isTextual() ? value.asText().strip() : "";
    }

    private static Map<String, String> stringParams(JsonNode raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw != null && raw.isObject()) {
            raw.properties()
                    .forEach(
                            e -> {
                                if (e.getValue().isValueNode()) {
                                    out.put(e.getKey(), e.getValue().asText());
                                }
                            });
        }
        return out;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return error("Failed to serialize the tool result.");
        }
    }

    /** Same hand-built-JSON bug class {@code DesktopToolCallback#error} fixed: a map, never concat. */
    private static String error(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"internal error\"}";
        }
    }
}
