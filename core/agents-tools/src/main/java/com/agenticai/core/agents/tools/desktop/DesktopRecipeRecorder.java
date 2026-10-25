package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.RecipeStep;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a finished run's tool-call journal into a replayable {@link DesktopRecipeSpec} - the
 * "record" half of run-to-script for the {@code local_desktop_*} tools. Pure: it takes
 * already-loaded journal rows (see {@link JournalCall}) and the parameters the user supplied, so
 * it is unit-testable with a fake journal. Mirrors {@code BrowserRecipeRecorder}.
 *
 * <p>What it keeps: the SUCCESSFUL {@code local_desktop_open} / {@code _activate} / {@code
 * _press} / {@code _click} / {@code _type} / {@code _key} calls, in order. Failed attempts,
 * reads (screen, elements, clipboard get), notifications and clipboard writes are dropped. What
 * it never keeps:
 *
 * <ul>
 *   <li>a user-supplied value - each occurrence of a supplied parameter's value in a press label,
 *       an open target or a typed text becomes {@code {{name}}} and the value is discarded;
 *   <li>a literal typed text - any {@code type} text that is not one of the supplied parameters
 *       (or a {@code {{secret.NAME}}} placeholder) aborts the recording unless {@code
 *       allowLiteralText} was asked for explicitly, because an unfocused field can hold a secret
 *       just as well as a labelled one.
 * </ul>
 *
 * App names are never parameterized: an {@code app} field decides which application is driven and
 * the validator insists it stay a literal inside {@code allowedApps}. A recorded right-button
 * click becomes a plain {@code click} with a warning - the step vocabulary has no button field,
 * so replays always use the primary button.
 */
final class DesktopRecipeRecorder {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Shortest user value that is replaced by a placeholder; shorter ones would corrupt
     * unrelated text.
     */
    static final int MIN_PARAM_VALUE_LENGTH = 3;

    static final int MAX_PARAM_VALUE_LENGTH = 500;

    private DesktopRecipeRecorder() {}

    /** One journaled tool call (a {@code tool_call_log} row, reduced to what recording needs). */
    record JournalCall(String toolName, String input, String output, String error) {}

    /**
     * A recorded, validated spec plus human-readable notes about what was kept, rewritten or
     * flagged.
     */
    record Recorded(DesktopRecipeSpec spec, List<String> warnings) {}

    static Recorded record(
            List<JournalCall> calls, Map<String, String> parameters, boolean allowLiteralText) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> params = cleanParameters(parameters, warnings);

        List<RecipeStep> steps = new ArrayList<>();
        Set<String> apps = new LinkedHashSet<>();
        Set<String> usedParams = new LinkedHashSet<>();

        for (JournalCall call : calls == null ? List.<JournalCall>of() : calls) {
            if (call == null || call.toolName() == null) {
                continue;
            }
            String tool = call.toolName();
            if (!DesktopToolCallback.Action.OPEN.toolName.equals(tool)
                    && !DesktopToolCallback.Action.ACTIVATE.toolName.equals(tool)
                    && !DesktopToolCallback.Action.PRESS.toolName.equals(tool)
                    && !DesktopToolCallback.Action.CLICK.toolName.equals(tool)
                    && !DesktopToolCallback.Action.TYPE.toolName.equals(tool)
                    && !DesktopToolCallback.Action.KEY.toolName.equals(tool)) {
                continue;
            }
            if (failed(call)) {
                continue;
            }
            Map<String, Object> input = parseObject(call.input());
            if (input == null) {
                continue;
            }
            int n = steps.size() + 1;
            if (DesktopToolCallback.Action.OPEN.toolName.equals(tool)) {
                String target = text(input.get("target"));
                if (target == null) {
                    continue;
                }
                String application = appName(input.get("application"), params, warnings, n);
                steps.add(
                        new RecipeStep(
                                DesktopRecipeSpec.OPEN,
                                application,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                applyParams(target, params, usedParams),
                                null,
                                null,
                                null,
                                false));
            } else if (DesktopToolCallback.Action.ACTIVATE.toolName.equals(tool)) {
                String app = appName(input.get("app"), params, warnings, n);
                if (app == null) {
                    continue;
                }
                steps.add(step(DesktopRecipeSpec.ACTIVATE, app, null, null, null, false));
            } else if (DesktopToolCallback.Action.PRESS.toolName.equals(tool)) {
                String label = text(input.get("label"));
                if (label == null) {
                    continue;
                }
                String app = appName(input.get("app"), params, warnings, n);
                steps.add(
                        new RecipeStep(
                                DesktopRecipeSpec.PRESS,
                                app,
                                applyParams(label, params, usedParams),
                                text(input.get("role")),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                DesktopRecipeValidator.looksIrreversible(label)));
            } else if (DesktopToolCallback.Action.CLICK.toolName.equals(tool)) {
                Integer x = number(input.get("x"));
                Integer y = number(input.get("y"));
                if (x == null || y == null) {
                    continue;
                }
                String button = text(input.get("button"));
                if (button != null && !"left".equalsIgnoreCase(button)) {
                    warnings.add(
                            "Step "
                                    + n
                                    + " was a "
                                    + button
                                    + "-click; desktop recipes replay a click with the primary button - check it still does what you want.");
                }
                steps.add(step(DesktopRecipeSpec.CLICK, null, x, y, null, false));
            } else if (DesktopToolCallback.Action.TYPE.toolName.equals(tool)) {
                Object rawText = input.get("text");
                String typed = rawText == null ? "" : rawText.toString();
                String stored =
                        typedText(n, typed, params, usedParams, allowLiteralText, warnings);
                steps.add(
                        new RecipeStep(
                                DesktopRecipeSpec.TYPE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                stored,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false));
            } else if (DesktopToolCallback.Action.KEY.toolName.equals(tool)) {
                String key = text(input.get("key"));
                if (key == null) {
                    continue;
                }
                steps.add(
                        step(
                                DesktopRecipeSpec.KEY,
                                null,
                                null,
                                null,
                                applyParams(key, params, usedParams),
                                DesktopRecipeValidator.looksIrreversibleKey(key)));
            }
        }

        for (RecipeStep s : steps) {
            if (s.app() != null) {
                apps.add(s.app().toLowerCase(Locale.ROOT));
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(
                    "no successful local_desktop_* actions were found in this run, so there is nothing to record");
        }
        String first = steps.get(0).action();
        if (!DesktopRecipeSpec.OPEN.equals(first) && !DesktopRecipeSpec.ACTIVATE.equals(first)) {
            throw new IllegalArgumentException(
                    "this run's first desktop action was not an open or activate (no app was brought to the front), so it cannot be replayed from a cold start");
        }
        for (String name : params.keySet()) {
            if (!usedParams.contains(name)) {
                warnings.add(
                        "Parameter '"
                                + name
                                + "' was not found in any label, target, key or typed text, so it was not added.");
            }
        }
        long irreversible = steps.stream().filter(RecipeStep::irreversible).count();
        if (irreversible > 0) {
            warnings.add(
                    irreversible
                            + " step(s) look like they submit or commit something; replay stops before them unless the caller confirms.");
        }
        List<ParamSpec> paramSpecs = new ArrayList<>();
        for (String name : usedParams) {
            paramSpecs.add(new ParamSpec(name, true));
        }
        DesktopRecipeSpec spec =
                DesktopRecipeValidator.validate(
                        new DesktopRecipeSpec(
                                DesktopRecipeSpec.CURRENT_VERSION,
                                new ArrayList<>(apps),
                                paramSpecs,
                                steps));
        return new Recorded(spec, List.copyOf(warnings));
    }

    /** Convenience for the steps that carry only an action plus up to two fields. */
    private static RecipeStep step(
            String action, String app, Integer x, Integer y, String key, boolean irreversible) {
        return new RecipeStep(
                action, app, null, null, x, y, null, key, null, null, null, null, irreversible);
    }

    /**
     * The literal app name of a step, or null. Apps are never parameterized - they decide which
     * application is driven, so a parameter value matching one is reported, not substituted.
     */
    private static String appName(
            Object raw, Map<String, String> params, List<String> warnings, int n) {
        String app = text(raw);
        if (app == null) {
            return null;
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (app.contains(e.getValue())) {
                warnings.add(
                        "Step "
                                + n
                                + " acts in an app whose name contains the value of parameter '"
                                + e.getKey()
                                + "'; app names stay literal, so it was not substituted.");
                break;
            }
        }
        return app;
    }

    // ---- typed text -----------------------------------------------------------------

    private static String typedText(
            int n,
            String typed,
            Map<String, String> params,
            Set<String> usedParams,
            boolean allowLiteralText,
            List<String> warnings) {
        String value = typed;
        if (!DesktopRecipePlaceholders.isSinglePlaceholder(value)) {
            value = applyParams(value, params, usedParams);
        }
        String literal = DesktopRecipePlaceholders.literalPart(value).strip();
        if (literal.isEmpty()) {
            return value;
        }
        if (!allowLiteralText) {
            throw new IllegalArgumentException(
                    "step "
                            + n
                            + " typed text that is not one of the supplied parameters. Add it as a parameter, "
                            + "use a {{secret.NAME}} reference for a credential, "
                            + "or save with literal text allowed if it is fixed, non-secret content.");
        }
        warnings.add(
                "Step "
                        + n
                        + " keeps its typed text as a literal (allowed explicitly) - check it is not personal or secret.");
        return value;
    }

    // ---- parameters -----------------------------------------------------------------

    private static Map<String, String> cleanParameters(
            Map<String, String> parameters, List<String> warnings) {
        Map<String, String> sorted = new LinkedHashMap<>();
        if (parameters == null) {
            return sorted;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(parameters.entrySet());
        entries.sort((a, b) -> Integer.compare(len(b.getValue()), len(a.getValue())));
        for (Map.Entry<String, String> e : entries) {
            String name = e.getKey() == null ? "" : e.getKey().strip();
            String value = e.getValue();
            if (!DesktopRecipePlaceholders.PARAM_NAME.matcher(name).matches()
                    || DesktopRecipePlaceholders.isReservedParamName(name)) {
                throw new IllegalArgumentException(
                        "'"
                                + name
                                + "' is not a valid parameter name (letters, digits and underscores, "
                                + "starting with a letter, at most 32 characters; 'secret' is reserved)");
            }
            if (value == null || value.isBlank()) {
                warnings.add("Parameter '" + name + "' had no value, so it was ignored.");
                continue;
            }
            if (value.length() > MAX_PARAM_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "the value of parameter '"
                                + name
                                + "' is longer than "
                                + MAX_PARAM_VALUE_LENGTH
                                + " characters");
            }
            if (value.length() < MIN_PARAM_VALUE_LENGTH) {
                warnings.add(
                        "Parameter '"
                                + name
                                + "' has a value shorter than "
                                + MIN_PARAM_VALUE_LENGTH
                                + " characters, which is too short to match safely, so it was not substituted.");
                continue;
            }
            sorted.put(name, value);
        }
        return sorted;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    private static String applyParams(String text, Map<String, String> params, Set<String> used) {
        String out = text;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (out.contains(e.getValue())) {
                out = out.replace(e.getValue(), DesktopRecipePlaceholders.param(e.getKey()));
                used.add(e.getKey());
            }
        }
        return out;
    }

    // ---- journal parsing ------------------------------------------------------------

    private static boolean failed(JournalCall call) {
        if (call.error() != null && !call.error().isBlank()) {
            return true;
        }
        String output = call.output();
        if (output == null || output.isBlank()) {
            return true;
        }
        if (output.startsWith("This tool call")
                || output.startsWith("This action (")
                || output.startsWith("This tool (")
                || output.startsWith("Tool output from '")) {
            return true;
        }
        Map<String, Object> parsed = parseObject(output);
        return parsed != null && parsed.containsKey("error");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object value = MAPPER.readValue(json, Object.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }

    private static Integer number(Object value) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
