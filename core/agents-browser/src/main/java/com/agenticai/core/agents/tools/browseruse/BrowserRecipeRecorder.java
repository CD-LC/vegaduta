package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.Expect;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import com.agenticai.core.json.JsonMappers;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a finished run's tool-call journal into a replayable {@link BrowserRecipeSpec} - the
 * "record" half of run-to-script. Pure: it takes already-loaded journal rows (see {@link
 * JournalCall}) and the parameters the user supplied, so it is unit-testable with a fake journal.
 *
 * <p>What it keeps: the SUCCESSFUL {@code browser_navigate} / {@code browser_click} / {@code
 * browser_type} calls, in order. Failed attempts, reads and screenshots are dropped. What it never
 * keeps:
 *
 * <ul>
 *   <li>a user-supplied value - each occurrence of a supplied parameter's value in a URL (outside
 *       the host), a selector or typed text becomes {@code {{name}}} and the value is discarded;
 *   <li>a credential - a literal typed into a credential/one-time-code field aborts the recording
 *       (the run must use {@code {{secret.NAME}}}); a typed value equal to a code {@code
 *       browser_totp} returned earlier is rewritten to {@code {{totp.NAME}}}; and ANY other literal
 *       typed text aborts it too unless {@code allowLiteralText} was asked for explicitly, because
 *       an unlabelled field can hold a secret as well.
 * </ul>
 *
 * Verification is derived where the journal proves it: after a navigate, the final host (and, when
 * the page was not redirected, the path). Clicks and typing keep the action-succeeded check plus
 * the recipe-wide "stay inside the allowed domains" check; richer per-step expectations can be
 * added by editing the recipe.
 */
final class BrowserRecipeRecorder {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Shortest user value that is replaced by a placeholder; shorter ones would corrupt unrelated
     * text.
     */
    static final int MIN_PARAM_VALUE_LENGTH = 3;

    static final int MAX_PARAM_VALUE_LENGTH = 500;

    private BrowserRecipeRecorder() {}

    /** One journaled tool call (a {@code tool_call_log} row, reduced to what recording needs). */
    record JournalCall(String toolName, String input, String output, String error) {}

    /**
     * A recorded, validated spec plus human-readable notes about what was kept, rewritten or
     * flagged.
     */
    record Recorded(BrowserRecipeSpec spec, List<String> warnings) {}

    static Recorded record(
            List<JournalCall> calls, Map<String, String> parameters, boolean allowLiteralText) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> params = cleanParameters(parameters, warnings);

        List<RecipeStep> steps = new ArrayList<>();
        Set<String> hosts = new LinkedHashSet<>();
        Set<String> usedParams = new LinkedHashSet<>();
        Map<String, String> totpCodes = new HashMap<>();

        for (JournalCall call : calls == null ? List.<JournalCall>of() : calls) {
            if (call == null || call.toolName() == null) {
                continue;
            }
            String tool = call.toolName();
            if ("browser_totp".equals(tool)) {
                rememberTotp(call, totpCodes);
                continue;
            }
            if (!"browser_navigate".equals(tool)
                    && !"browser_click".equals(tool)
                    && !"browser_type".equals(tool)) {
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
            switch (tool) {
                case "browser_navigate" -> {
                    String url = text(input.get("url"));
                    if (url == null) {
                        break;
                    }
                    Map<String, Object> output = parseObject(call.output());
                    String finalUrl = output == null ? null : text(output.get("url"));
                    addHost(hosts, url);
                    addHost(hosts, finalUrl);
                    steps.add(
                            new RecipeStep(
                                    BrowserRecipeSpec.NAVIGATE,
                                    applyParamsToUrl(url, params, usedParams),
                                    null,
                                    null,
                                    null,
                                    navigateExpect(url, finalUrl, params, usedParams),
                                    null,
                                    false));
                }
                case "browser_click" -> {
                    String selector = text(input.get("selector"));
                    if (selector == null) {
                        break;
                    }
                    steps.add(
                            new RecipeStep(
                                    BrowserRecipeSpec.CLICK,
                                    null,
                                    applyParams(selector, params, usedParams),
                                    null,
                                    null,
                                    null,
                                    null,
                                    BrowserRecipeValidator.looksIrreversible(selector)));
                }
                case "browser_type" -> {
                    String selector = text(input.get("selector"));
                    if (selector == null) {
                        break;
                    }
                    Object rawText = input.get("text");
                    String typed = rawText == null ? "" : rawText.toString();
                    String stored =
                            typedText(
                                    n,
                                    selector,
                                    typed,
                                    params,
                                    usedParams,
                                    totpCodes,
                                    allowLiteralText,
                                    warnings);
                    steps.add(
                            new RecipeStep(
                                    BrowserRecipeSpec.TYPE,
                                    null,
                                    applyParams(selector, params, usedParams),
                                    stored,
                                    null,
                                    null,
                                    null,
                                    false));
                }
                default -> {
                    // unreachable: filtered above
                }
            }
        }

        if (steps.isEmpty()) {
            throw new IllegalArgumentException(
                    "no successful browser actions were found in this run, so there is nothing to record");
        }
        if (!BrowserRecipeSpec.NAVIGATE.equals(steps.get(0).action())) {
            throw new IllegalArgumentException(
                    "this run's first browser action was not a navigate (the browser session was already open), so it cannot be replayed from a cold start");
        }
        for (String name : params.keySet()) {
            if (!usedParams.contains(name)) {
                warnings.add(
                        "Parameter '"
                                + name
                                + "' was not found in any URL, selector or typed text, so it was not added.");
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
        BrowserRecipeSpec spec =
                BrowserRecipeValidator.validate(
                        new BrowserRecipeSpec(
                                BrowserRecipeSpec.CURRENT_VERSION,
                                new ArrayList<>(hosts),
                                paramSpecs,
                                steps));
        return new Recorded(spec, List.copyOf(warnings));
    }

    // ---- typed text -----------------------------------------------------------------

    private static String typedText(
            int n,
            String selector,
            String typed,
            Map<String, String> params,
            Set<String> usedParams,
            Map<String, String> totpCodes,
            boolean allowLiteralText,
            List<String> warnings) {
        String value = typed;
        String totpName = totpCodes.get(typed.strip());
        if (totpName != null && !typed.isBlank()) {
            return "{{totp." + totpName + "}}";
        }
        if (!BrowserRecipePlaceholders.isSinglePlaceholder(value)) {
            value = applyParams(value, params, usedParams);
        }
        String literal = BrowserRecipePlaceholders.literalPart(value).strip();
        if (literal.isEmpty()) {
            return value;
        }
        String where = "step " + n + " (" + abbreviate(selector) + ")";
        if (BrowserRecipeValidator.isSensitiveTarget(selector)) {
            throw new IllegalArgumentException(
                    where
                            + " typed a literal value into what looks like a credential or one-time-code field. "
                            + "Re-run it with a {{secret.NAME}} reference (or browser_totp for a 2FA code) so the value is not stored.");
        }
        if (!allowLiteralText) {
            throw new IllegalArgumentException(
                    where
                            + " typed text that is not one of the supplied parameters. Add it as a parameter, "
                            + "or save with literal text allowed if it is fixed, non-secret content.");
        }
        warnings.add(
                "Step "
                        + n
                        + " keeps its typed text as a literal (allowed explicitly) - check it is not personal or secret.");
        return value;
    }

    private static void rememberTotp(JournalCall call, Map<String, String> totpCodes) {
        if (failed(call)) {
            return;
        }
        Map<String, Object> input = parseObject(call.input());
        Map<String, Object> output = parseObject(call.output());
        if (input == null || output == null) {
            return;
        }
        String name = text(input.get("secretName"));
        String code = text(output.get("code"));
        if (name != null && code != null) {
            totpCodes.put(code, name.toUpperCase(Locale.ROOT));
        }
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
            if (!BrowserRecipePlaceholders.PARAM_NAME.matcher(name).matches()
                    || BrowserRecipePlaceholders.isReservedParamName(name)) {
                throw new IllegalArgumentException(
                        "'"
                                + name
                                + "' is not a valid parameter name (letters, digits and underscores, "
                                + "starting with a letter, at most 32 characters; 'secret' and 'totp' are reserved)");
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
                out = out.replace(e.getValue(), BrowserRecipePlaceholders.param(e.getKey()));
                used.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * Like {@link #applyParams} but never touches scheme/host, and also matches the URL-encoded
     * form of a value.
     */
    private static String applyParamsToUrl(
            String url, Map<String, String> params, Set<String> used) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int hostEnd = url.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int idx = url.indexOf(stop, schemeEnd + 3);
            if (idx >= 0 && idx < hostEnd) {
                hostEnd = idx;
            }
        }
        String head = url.substring(0, hostEnd);
        String tail = url.substring(hostEnd);
        for (Map.Entry<String, String> e : params.entrySet()) {
            String placeholder = BrowserRecipePlaceholders.param(e.getKey());
            String encodedPlus = URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8);
            String encodedPct = encodedPlus.replace("+", "%20");
            for (String form : new String[] {e.getValue(), encodedPct, encodedPlus}) {
                if (!form.isEmpty() && tail.contains(form)) {
                    tail = tail.replace(form, placeholder);
                    used.add(e.getKey());
                }
            }
        }
        return head + tail;
    }

    // ---- verification derived from the journal --------------------------------------

    private static Expect navigateExpect(
            String requestedUrl, String finalUrl, Map<String, String> params, Set<String> used) {
        String host = BrowserStorageState.httpHostOf(finalUrl);
        if (host == null) {
            return null;
        }
        String requestedPath = pathOf(requestedUrl);
        String finalPath = pathOf(finalUrl);
        // Only pin the path when the page was NOT redirected: a login redirect seen while recording
        // must not
        // become a requirement once a saved profile lands the same navigation on the real page.
        String pathPrefix =
                requestedPath != null && requestedPath.equals(finalPath) && !"/".equals(finalPath)
                        ? finalPath
                        : null;
        return new Expect(
                host,
                pathPrefix == null ? null : applyParams(pathPrefix, params, used),
                null,
                null,
                null);
    }

    private static String pathOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String path = URI.create(url.strip()).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void addHost(Set<String> hosts, String url) {
        String host = BrowserStorageState.httpHostOf(url);
        if (host != null) {
            hosts.add(host);
        }
    }

    // ---- journal parsing ----------------------------------------------------------------

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

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 37) + "...";
    }
}
