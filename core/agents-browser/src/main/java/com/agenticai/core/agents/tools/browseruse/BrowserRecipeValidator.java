package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.browseruse.BrowserRecipePlaceholders.Kind;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipePlaceholders.Token;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.Expect;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single gate every recipe passes before it is stored or replayed - recorded from a run,
 * created by hand, or read back from the table. Pure and dependency-free.
 *
 * <p>What it guarantees: a known action vocabulary and shape per action; the first step is a {@code
 * navigate} (a replay may start with no session); every navigate host sits inside the recipe's
 * allowed domains and is a literal (a placeholder cannot choose the host); every placeholder is
 * well-formed and every {@code {{param}}} is declared; secret/TOTP placeholders appear only as the
 * text typed into a field, never in URLs or selectors; and a value typed into a SENSITIVE field
 * (password, one-time code, card number, token...) is a {@code {{secret.NAME}}} or {@code
 * {{totp.NAME}}} placeholder, never a literal - which is what keeps credentials out of the table.
 */
final class BrowserRecipeValidator {

    static final int MAX_STEPS = 60;
    static final int MAX_PARAMS = 20;
    static final int MAX_URL_LENGTH = 2_048;
    static final int MAX_SELECTOR_LENGTH = 500;
    static final int MAX_TEXT_LENGTH = 2_000;
    static final int MAX_TIMEOUT_MS = 60_000;
    static final int MIN_TIMEOUT_MS = 100;

    /**
     * Field names/labels that mean "a credential or one-time secret goes here". Matched
     * case-insensitively against the selector or description the model used (e.g. {@code
     * input[type=password]}, {@code #otp}, "the card number field").
     */
    private static final Pattern SENSITIVE_TARGET =
            Pattern.compile(
                    "password|passwd|passcode|passphrase|pwd|(?<![a-z])pass(?![a-z])|secret|token|api[-_ ]?key"
                            + "|(?<![a-z])(otp|mfa|2fa|totp|pin|ssn|cvv|cvc)(?![a-z])|one[-_ ]?time|verification|authenticator"
                            + "|card[-_ ]?(number|no|num)|ccnum|cc[-_]?number|social[-_ ]?security|security[-_ ]?code",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Click targets whose label says the click commits something: submit, pay, buy, delete, send,
     * publish...
     */
    private static final Pattern IRREVERSIBLE_TARGET =
            Pattern.compile(
                    "\\b(submit|pay|purchase|buy|order|checkout|check[-_ ]?out|confirm|delete|remove|cancel|send|post|publish|transfer"
                            + "|withdraw|subscribe|unsubscribe|sign[-_ ]?out|log[-_ ]?out|approve|reject|apply|book|reserve|donate)\\b"
                            + "|type=.?submit",
                    Pattern.CASE_INSENSITIVE);

    private BrowserRecipeValidator() {}

    /** True when {@code selector} names a credential/one-time-secret field. */
    static boolean isSensitiveTarget(String selector) {
        return selector != null && SENSITIVE_TARGET.matcher(selector).find();
    }

    /** True when clicking {@code selector} plausibly commits something that cannot be undone. */
    static boolean looksIrreversible(String selector) {
        return selector != null && IRREVERSIBLE_TARGET.matcher(selector).find();
    }

    /**
     * Validates {@code spec} and returns a normalized copy (domains lower-cased and de-duplicated,
     * actions lower-cased).
     *
     * @throws IllegalArgumentException with a "step N: ..." message naming the first problem found
     */
    static BrowserRecipeSpec validate(BrowserRecipeSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("a recipe is required");
        }
        if (spec.version() != BrowserRecipeSpec.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported recipe version " + spec.version());
        }
        List<String> domains = BrowserStorageState.normalizeDomains(spec.allowedDomains());
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
            steps.add(validateStep(i + 1, inSteps.get(i), domains, declared));
        }
        if (!BrowserRecipeSpec.NAVIGATE.equals(steps.get(0).action())) {
            throw new IllegalArgumentException("step 1: a recipe must start with a navigate step");
        }
        return new BrowserRecipeSpec(
                BrowserRecipeSpec.CURRENT_VERSION, domains, params, List.copyOf(steps));
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
                    || !BrowserRecipePlaceholders.PARAM_NAME.matcher(p.name()).matches()) {
                throw new IllegalArgumentException(
                        "parameter names are letters, digits and underscores, start with a letter and are at most 32 characters");
            }
            if (BrowserRecipePlaceholders.isReservedParamName(p.name())) {
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
            int n, RecipeStep step, List<String> domains, Set<String> declared) {
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
        checkField(prefix, "url", step.url(), MAX_URL_LENGTH, declared, false);
        checkField(prefix, "selector", step.selector(), MAX_SELECTOR_LENGTH, declared, false);
        checkField(prefix, "text", step.text(), MAX_TEXT_LENGTH, declared, true);
        checkField(prefix, "value", step.value(), MAX_TEXT_LENGTH, declared, false);
        Expect expect = step.expect();
        if (expect != null) {
            checkField(
                    prefix,
                    "expect.urlHost",
                    expect.urlHost(),
                    MAX_SELECTOR_LENGTH,
                    declared,
                    false);
            checkField(
                    prefix,
                    "expect.urlPathPrefix",
                    expect.urlPathPrefix(),
                    MAX_SELECTOR_LENGTH,
                    declared,
                    false);
            checkField(
                    prefix,
                    "expect.urlContains",
                    expect.urlContains(),
                    MAX_SELECTOR_LENGTH,
                    declared,
                    false);
            checkField(
                    prefix,
                    "expect.textContains",
                    expect.textContains(),
                    MAX_SELECTOR_LENGTH,
                    declared,
                    false);
            checkField(
                    prefix,
                    "expect.selector",
                    expect.selector(),
                    MAX_SELECTOR_LENGTH,
                    declared,
                    false);
        }
        switch (action) {
            case BrowserRecipeSpec.NAVIGATE -> validateNavigateUrl(prefix, step.url(), domains);
            case BrowserRecipeSpec.CLICK -> requireText(prefix, "selector", step.selector());
            case BrowserRecipeSpec.TYPE -> {
                requireText(prefix, "selector", step.selector());
                if (step.text() == null) {
                    throw new IllegalArgumentException(
                            prefix
                                    + "a type step needs text (use an empty string to clear the field)");
                }
                if (isSensitiveTarget(step.selector())
                        && !step.text().isEmpty()
                        && !isSecretOrTotpPlaceholder(step.text())) {
                    throw new IllegalArgumentException(
                            prefix
                                    + "'"
                                    + abbreviate(step.selector())
                                    + "' looks like a credential or one-time-code field, so its text must be exactly a {{secret.NAME}} or "
                                    + "{{totp.NAME}} placeholder, never a literal value");
                }
            }
            case BrowserRecipeSpec.SELECT -> {
                requireText(prefix, "selector", step.selector());
                requireText(prefix, "value", step.value());
            }
            case BrowserRecipeSpec.WAIT_FOR -> {
                if (expect == null || expect.isEmpty()) {
                    throw new IllegalArgumentException(
                            prefix + "a wait_for step needs an expect condition");
                }
            }
            default ->
                    throw new IllegalArgumentException(
                            prefix
                                    + "unknown action '"
                                    + abbreviate(step.action())
                                    + "' (navigate, click, type, select or wait_for)");
        }
        return new RecipeStep(
                action,
                step.url(),
                step.selector(),
                step.text(),
                step.value(),
                expect,
                timeout,
                step.irreversible());
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
        if (BrowserRecipePlaceholders.hasMalformedBraces(value)) {
            throw new IllegalArgumentException(prefix + field + " has a malformed {{placeholder}}");
        }
        for (Token token : BrowserRecipePlaceholders.tokens(value)) {
            if (token.kind() == Kind.PARAM) {
                if (!declared.contains(token.name().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            prefix + field + " uses the undeclared parameter " + token.raw());
                }
            } else if (!secretsAllowed) {
                throw new IllegalArgumentException(
                        prefix
                                + field
                                + " must not contain a secret or TOTP placeholder (only the text typed into a field may)");
            }
        }
    }

    private static boolean isSecretOrTotpPlaceholder(String text) {
        if (!BrowserRecipePlaceholders.isSinglePlaceholder(text)) {
            return false;
        }
        List<Token> tokens = BrowserRecipePlaceholders.tokens(text.strip());
        return tokens.size() == 1 && tokens.get(0).kind() != Kind.PARAM;
    }

    private static void validateNavigateUrl(String prefix, String url, List<String> domains) {
        requireText(prefix, "url", url);
        String lower = url.strip().toLowerCase(Locale.ROOT);
        int schemeEnd = lower.startsWith("https://") ? 8 : lower.startsWith("http://") ? 7 : -1;
        if (schemeEnd < 0) {
            throw new IllegalArgumentException(
                    prefix + "a navigate url must start with http:// or https://");
        }
        int end = lower.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int idx = lower.indexOf(stop, schemeEnd);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        String authority = lower.substring(schemeEnd, end);
        if (authority.contains("@") || authority.contains("{{")) {
            throw new IllegalArgumentException(
                    prefix
                            + "the host of a navigate url must be a literal (no credentials, no placeholders)");
        }
        int colon = authority.lastIndexOf(':');
        String host = colon >= 0 ? authority.substring(0, colon) : authority;
        if (!BrowserStorageState.hostAllowed(host, domains)) {
            throw new IllegalArgumentException(
                    prefix + "'" + host + "' is outside the recipe's allowed domains");
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
