package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agents.tools.desktop.DesktopRecipePlaceholders.Kind;
import com.agenticai.core.agents.tools.desktop.DesktopRecipePlaceholders.Token;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.Expect;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.RecipeStep;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single gate every desktop recipe passes before it is stored or replayed - recorded from a
 * run, created by hand, or read back from the table. Pure and dependency-free. Mirrors {@code
 * BrowserRecipeValidator}.
 *
 * <p>What it guarantees: a known action vocabulary and the shape each action needs; the first step
 * is {@code open} or {@code activate} (a replay starts cold, so it must establish which app it
 * works in); every named app sits inside the recipe's allowed apps and is a literal (a placeholder
 * cannot choose which application is driven); every placeholder is well-formed and every {@code
 * {{param}}} is declared; and a {@code {{secret.NAME}}} appears only inside a {@code type} step's
 * text - nowhere else, so a secret can only ever reach the keyboard.
 *
 * <p>Irreversibility heuristics (what the recorder and hand-authors mark): a {@code press} whose
 * label says it commits something - submit, pay, delete, send, publish... ({@link
 * #looksIrreversible}), and a bare {@code key} press of Return/Enter, which is how a typed command
 * or a half-filled form gets submitted on a desktop ({@link #looksIrreversibleKey}). An {@code
 * open} of a file, app or URL is deliberately NOT flagged: opening is what the first step always
 * does and the person already saw it run once.
 */
final class DesktopRecipeValidator {

    static final int MAX_STEPS = 60;
    static final int MAX_PARAMS = 20;
    static final int MAX_TARGET_LENGTH = 2_048;
    static final int MAX_LABEL_LENGTH = 500;
    static final int MAX_APP_LENGTH = 120;
    static final int MAX_TEXT_LENGTH = 2_000;
    static final int MAX_KEY_LENGTH = 32;
    static final int MAX_TIMEOUT_MS = 60_000;
    static final int MIN_TIMEOUT_MS = 100;
    static final int MAX_WAIT_MS = 120_000;
    static final int MAX_COORDINATE = 20_000;

    /** The sidecar's {@code /key} vocabulary (see services/desktop-agent and the tool schema). */
    private static final Pattern KEY_NAME =
            Pattern.compile(
                    "return|enter|tab|space|delete|escape|left|right|down|up|f([1-9]|1[0-2])",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Press targets whose label says the action commits something: submit, pay, buy, delete, send,
     * publish... Same vocabulary the browser recipes use, matched case-insensitively against the
     * element label the model pressed.
     */
    private static final Pattern IRREVERSIBLE_TARGET =
            Pattern.compile(
                    "\\b(submit|pay|purchase|buy|order|checkout|check[-_ ]?out|confirm|delete|remove|cancel|send|post|publish|transfer"
                            + "|withdraw|subscribe|unsubscribe|sign[-_ ]?out|log[-_ ]?out|approve|reject|apply|book|reserve|donate)\\b",
                    Pattern.CASE_INSENSITIVE);

    private DesktopRecipeValidator() {}

    /** True when pressing an element labelled {@code label} plausibly commits something. */
    static boolean looksIrreversible(String label) {
        return label != null && IRREVERSIBLE_TARGET.matcher(label).find();
    }

    /**
     * True for Return/Enter: on a desktop a bare Return is how a typed command runs or a form
     * submits, with no label to warn about, so it is flagged as irreversible and the replay pauses
     * before it.
     */
    static boolean looksIrreversibleKey(String key) {
        return key != null
                && ("return".equalsIgnoreCase(key.strip())
                        || "enter".equalsIgnoreCase(key.strip()));
    }

    /**
     * Validates {@code spec} and returns a normalized copy (allowed apps lower-cased and
     * de-duplicated, actions lower-cased).
     *
     * @throws IllegalArgumentException with a "step N: ..." message naming the first problem found
     */
    static DesktopRecipeSpec validate(DesktopRecipeSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("a recipe is required");
        }
        if (spec.version() != DesktopRecipeSpec.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported recipe version " + spec.version());
        }
        List<String> apps = normalizeApps(spec.allowedApps());
        List<ParamSpec> params = validateParams(spec.params());
        Set<String> declared = new HashSet<>();
        for (ParamSpec p : params) {
            declared.add(p.name().toLowerCase(Locale.ROOT));
        }
        List<RecipeStep> inSteps = spec.steps();
        if (inSteps == null || inSteps.isEmpty()) {
            throw new IllegalArgumentException("a recipe needs at least one step");
        }
        if (inSteps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("a recipe has at most " + MAX_STEPS + " steps");
        }
        List<RecipeStep> steps = new ArrayList<>(inSteps.size());
        for (int i = 0; i < inSteps.size(); i++) {
            steps.add(validateStep(i + 1, inSteps.get(i), apps, declared));
        }
        String first = steps.get(0).action();
        if (!DesktopRecipeSpec.OPEN.equals(first) && !DesktopRecipeSpec.ACTIVATE.equals(first)) {
            throw new IllegalArgumentException(
                    "step 1: a recipe must start with an open or activate step");
        }
        return new DesktopRecipeSpec(
                DesktopRecipeSpec.CURRENT_VERSION, apps, params, List.copyOf(steps));
    }

    /**
     * Lowercases, trims and de-duplicates the allowed-app list; rejects blanks, control characters
     * and placeholders (an allowed app is a literal name).
     */
    static List<String> normalizeApps(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String app : raw) {
            if (app == null || app.isBlank()) {
                continue;
            }
            String name = app.strip();
            if (name.length() > MAX_APP_LENGTH) {
                throw new IllegalArgumentException(
                        "an allowed app name is at most " + MAX_APP_LENGTH + " characters");
            }
            for (int i = 0; i < name.length(); i++) {
                if (Character.isISOControl(name.charAt(i))) {
                    throw new IllegalArgumentException(
                            "an allowed app name must not contain control characters");
                }
            }
            if (DesktopRecipePlaceholders.hasMalformedBraces(name)
                    || !DesktopRecipePlaceholders.tokens(name).isEmpty()) {
                throw new IllegalArgumentException(
                        "an allowed app name must be a literal (no {{placeholders}})");
            }
            out.add(name.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static List<ParamSpec> validateParams(List<ParamSpec> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        if (in.size() > MAX_PARAMS) {
            throw new IllegalArgumentException(
                    "a recipe has at most " + MAX_PARAMS + " parameters");
        }
        Set<String> seen = new HashSet<>();
        List<ParamSpec> out = new ArrayList<>(in.size());
        for (ParamSpec p : in) {
            if (p == null
                    || p.name() == null
                    || !DesktopRecipePlaceholders.PARAM_NAME.matcher(p.name()).matches()) {
                throw new IllegalArgumentException(
                        "parameter names are letters, digits and underscores, start with a letter and are at most 32 characters");
            }
            if (DesktopRecipePlaceholders.isReservedParamName(p.name())) {
                throw new IllegalArgumentException(
                        "'" + p.name() + "' is a reserved name and cannot be a parameter");
            }
            if (!seen.add(p.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "parameter '" + p.name() + "' is declared twice");
            }
            out.add(new ParamSpec(p.name(), p.required()));
        }
        return List.copyOf(out);
    }

    private static RecipeStep validateStep(
            int n, RecipeStep step, List<String> apps, Set<String> declared) {
        String prefix = "step " + n + ": ";
        if (step == null || step.action() == null) {
            throw new IllegalArgumentException(prefix + "an action is required");
        }
        String action = step.action().strip().toLowerCase(Locale.ROOT);
        Integer timeout = step.timeoutMs();
        if (timeout != null && (timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS)) {
            throw new IllegalArgumentException(
                    prefix
                            + "timeoutMs must be between "
                            + MIN_TIMEOUT_MS
                            + " and "
                            + MAX_TIMEOUT_MS);
        }
        checkApp(prefix, step.app(), apps);
        checkField(prefix, "label", step.label(), MAX_LABEL_LENGTH, declared, false);
        checkField(prefix, "role", step.role(), MAX_APP_LENGTH, declared, false);
        checkField(prefix, "text", step.text(), MAX_TEXT_LENGTH, declared, true);
        checkField(prefix, "key", step.key(), MAX_KEY_LENGTH, declared, false);
        checkField(prefix, "target", step.target(), MAX_TARGET_LENGTH, declared, false);
        Expect expect = step.expect();
        if (expect != null) {
            checkField(
                    prefix,
                    "expect.elementLabel",
                    expect.elementLabel(),
                    MAX_LABEL_LENGTH,
                    declared,
                    false);
            checkApp(prefix, "expect.appFrontmost", expect.appFrontmost(), apps);
        }
        switch (action) {
            case DesktopRecipeSpec.OPEN -> requireText(prefix, "target", step.target());
            case DesktopRecipeSpec.ACTIVATE -> requireText(prefix, "app", step.app());
            case DesktopRecipeSpec.PRESS -> requireText(prefix, "label", step.label());
            case DesktopRecipeSpec.CLICK -> {
                if (step.x() == null || step.y() == null) {
                    throw new IllegalArgumentException(prefix + "a click step needs x and y");
                }
                if (step.x() < 0
                        || step.y() < 0
                        || step.x() > MAX_COORDINATE
                        || step.y() > MAX_COORDINATE) {
                    throw new IllegalArgumentException(
                            prefix + "click coordinates must be between 0 and " + MAX_COORDINATE);
                }
            }
            case DesktopRecipeSpec.TYPE -> {
                if (step.text() == null) {
                    throw new IllegalArgumentException(
                            prefix
                                    + "a type step needs text (use an empty string to type nothing)");
                }
            }
            case DesktopRecipeSpec.KEY -> {
                requireText(prefix, "key", step.key());
                if (DesktopRecipePlaceholders.tokens(step.key()).isEmpty()
                        && !KEY_NAME.matcher(step.key().strip()).matches()) {
                    throw new IllegalArgumentException(
                            prefix
                                    + "'"
                                    + abbreviate(step.key())
                                    + "' is not a key the desktop agent knows (return, enter, tab, space, delete, escape, the arrows or f1-f12)");
                }
            }
            case DesktopRecipeSpec.WAIT -> {
                if (step.waitMs() == null || step.waitMs() < 1 || step.waitMs() > MAX_WAIT_MS) {
                    throw new IllegalArgumentException(
                            prefix + "waitMs must be between 1 and " + MAX_WAIT_MS);
                }
            }
            default ->
                    throw new IllegalArgumentException(
                            prefix
                                    + "unknown action '"
                                    + abbreviate(step.action())
                                    + "' (open, activate, press, click, type, key or wait)");
        }
        return new RecipeStep(
                action,
                step.app(),
                step.label(),
                step.role(),
                step.x(),
                step.y(),
                step.text(),
                step.key(),
                step.target(),
                step.waitMs(),
                expect,
                timeout,
                step.irreversible());
    }

    /**
     * An app field must be a literal name inside the recipe's allowed apps. A placeholder cannot
     * pick the application a step drives - the same rule the browser recipes apply to a navigate
     * URL's host.
     */
    private static void checkApp(String prefix, String app, List<String> apps) {
        checkApp(prefix, "app", app, apps);
    }

    private static void checkApp(String prefix, String field, String app, List<String> apps) {
        if (app == null || app.isBlank()) {
            return;
        }
        String name = app.strip();
        if (name.length() > MAX_APP_LENGTH) {
            throw new IllegalArgumentException(
                    prefix + field + " is longer than " + MAX_APP_LENGTH + " characters");
        }
        if (DesktopRecipePlaceholders.hasMalformedBraces(name)
                || !DesktopRecipePlaceholders.tokens(name).isEmpty()) {
            throw new IllegalArgumentException(
                    prefix + field + " must be a literal app name (no {{placeholders}})");
        }
        if (!apps.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    prefix + "'" + name + "' is outside the recipe's allowed apps");
        }
    }

    private static void checkField(
            String prefix,
            String field,
            String value,
            int max,
            Set<String> declared,
            boolean secretsAllowed) {
        if (value == null) {
            return;
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(
                    prefix + field + " is longer than " + max + " characters");
        }
        if (DesktopRecipePlaceholders.hasMalformedBraces(value)) {
            throw new IllegalArgumentException(prefix + field + " has a malformed {{placeholder}}");
        }
        for (Token token : DesktopRecipePlaceholders.tokens(value)) {
            if (token.kind() == Kind.PARAM) {
                if (!declared.contains(token.name().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            prefix + field + " uses the undeclared parameter " + token.raw());
                }
            } else if (!secretsAllowed) {
                throw new IllegalArgumentException(
                        prefix
                                + field
                                + " must not contain a {{secret.NAME}} placeholder (only the text of a type step may)");
            }
        }
    }

    private static void requireText(String prefix, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(prefix + "a " + field + " is required");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }
}
