package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.tools.browseruse.BrowserRecipePlaceholders.Kind;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.Expect;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Deterministic replay of a {@link BrowserRecipeSpec} with per-step verification - the "replay"
 * half of run-to-script. No AI in the loop: each step is executed through {@link BrowserPageOps},
 * then verified; on ANY failed verification (or failed action, missing parameter or secret, domain
 * violation, exhausted time budget) the replay STOPS and reports which step failed and why, leaving
 * the browser where it is so the agent can fall back to live browsing from that page. It never
 * skips a failed step and never silently continues.
 *
 * <p>Safety properties, each pinned by BrowserRecipeReplayerTest:
 *
 * <ul>
 *   <li>Secrets: {@code {{secret.NAME}}} / {@code {{totp.NAME}}} are resolved here through {@link
 *       SecretValues} (server-side), used for exactly one action, and scrubbed from every message
 *       in the {@link Outcome} - the model only ever sees placeholders and step numbers.
 *   <li>Domains: a navigate URL must be inside the recipe's allowed domains, and after every step
 *       the page must still be on an allowed host.
 *   <li>Irreversible steps (a click flagged as submit/buy/delete/send...) are NOT executed unless
 *       {@link Options#allowIrreversible()}; the replay pauses before them with {@link
 *       Status#NEEDS_CONFIRMATION}.
 *   <li>Time: each step's verification waits at most its timeout, and the whole replay at most
 *       {@link Options#maxTotalMs()}.
 * </ul>
 */
final class BrowserRecipeReplayer {

    static final long POLL_INTERVAL_MS = 250;
    private static final int MAX_PARAM_VALUE_LENGTH = 2_000;
    private static final int SETTLE_CAP_MS = 5_000;

    /**
     * Server-side resolution of vault-backed placeholders; values never leave the replay except
     * into the page.
     */
    interface SecretValues {

        Optional<String> secret(String name);

        Optional<String> totp(String name);

        /** No vault: every secret/TOTP placeholder fails the step. */
        SecretValues NONE =
                new SecretValues() {
                    @Override
                    public Optional<String> secret(String name) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> totp(String name) {
                        return Optional.empty();
                    }
                };
    }

    enum Status {
        SUCCESS,
        FAILED,
        NEEDS_CONFIRMATION
    }

    /**
     * Replay settings.
     *
     * @param allowIrreversible execute steps flagged irreversible (the caller has confirmed them)
     * @param defaultStepTimeoutMs verification budget for a step with no timeout of its own
     * @param maxTotalMs wall-clock budget for the whole replay
     * @param fromStep 1-based step to start at (resume after a confirmation); 1 = from the start
     */
    record Options(
            boolean allowIrreversible, int defaultStepTimeoutMs, long maxTotalMs, int fromStep) {

        static Options standard() {
            return new Options(false, 15_000, 120_000L, 1);
        }
    }

    /**
     * The result, safe to hand to the model: step numbers, action names, selector/URL TEMPLATES
     * (placeholders, never resolved values) and scrubbed reasons.
     *
     * @param failedStep 1-based step that failed or paused, 0 when the failure was before any step
     *     (a missing parameter)
     * @param target the selector or URL template of that step, or null
     */
    record Outcome(
            Status status,
            int stepsCompleted,
            int totalSteps,
            int failedStep,
            String action,
            String target,
            String reason,
            String currentUrl) {

        boolean success() {
            return status == Status.SUCCESS;
        }
    }

    private final LongSupplier nowMs;
    private final LongConsumer sleepMs;

    BrowserRecipeReplayer() {
        this(
                System::currentTimeMillis,
                ms -> {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    /** Test seam: a fake clock and sleeper make the polling paths instant and deterministic. */
    BrowserRecipeReplayer(LongSupplier nowMs, LongConsumer sleepMs) {
        this.nowMs = nowMs;
        this.sleepMs = sleepMs;
    }

    /** Thrown inside a step to stop the replay with a (pre-scrubbed) reason. */
    private static final class StepFailure extends RuntimeException {
        StepFailure(String message) {
            super(message, null, false, false);
        }
    }

    Outcome replay(
            BrowserRecipeSpec spec,
            Map<String, String> params,
            BrowserPageOps ops,
            SecretValues secrets,
            Options options) {
        List<RecipeStep> steps = spec.steps();
        int total = steps.size();
        Map<String, String> values = lowerCased(params);
        for (ParamSpec p : spec.params()) {
            String v = values.get(p.name().toLowerCase(Locale.ROOT));
            if (p.required() && (v == null || v.isBlank())) {
                return new Outcome(
                        Status.FAILED,
                        0,
                        total,
                        0,
                        null,
                        null,
                        "missing required parameter '" + p.name() + "'",
                        null);
            }
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() != null && e.getValue().length() > MAX_PARAM_VALUE_LENGTH) {
                return new Outcome(
                        Status.FAILED,
                        0,
                        total,
                        0,
                        null,
                        null,
                        "parameter '"
                                + e.getKey()
                                + "' is longer than "
                                + MAX_PARAM_VALUE_LENGTH
                                + " characters",
                        null);
            }
        }
        SecretValues vault = secrets == null ? SecretValues.NONE : secrets;
        Set<String> scrub = new LinkedHashSet<>();
        long start = nowMs.getAsLong();
        int first = Math.max(1, Math.min(options.fromStep(), total));
        for (int i = first - 1; i < total; i++) {
            RecipeStep step = steps.get(i);
            int n = i + 1;
            String target =
                    BrowserRecipeSpec.NAVIGATE.equals(step.action()) ? step.url() : step.selector();
            if (nowMs.getAsLong() - start > options.maxTotalMs()) {
                return fail(
                        ops,
                        n - 1,
                        total,
                        n,
                        step,
                        target,
                        "the replay time budget (" + options.maxTotalMs() / 1000 + "s) ran out");
            }
            if (step.irreversible() && !options.allowIrreversible()) {
                return new Outcome(
                        Status.NEEDS_CONFIRMATION,
                        n - 1,
                        total,
                        n,
                        step.action(),
                        target,
                        "step "
                                + n
                                + " looks like it submits or commits something and was NOT run. Ask the user to confirm, then "
                                + "call run_browser_recipe again with confirmIrreversible=true and fromStep="
                                + n
                                + ".",
                        safeUrl(ops));
            }
            try {
                runStep(step, n, spec, values, vault, scrub, ops, options);
            } catch (StepFailure failure) {
                return fail(
                        ops, n - 1, total, n, step, target, scrubbed(failure.getMessage(), scrub));
            } catch (RuntimeException e) {
                return fail(ops, n - 1, total, n, step, target, scrubbed(describe(e), scrub));
            }
        }
        return new Outcome(Status.SUCCESS, total, total, 0, null, null, null, safeUrl(ops));
    }

    // ---- one step ---------------------------------------------------------------------------

    private void runStep(
            RecipeStep step,
            int n,
            BrowserRecipeSpec spec,
            Map<String, String> values,
            SecretValues vault,
            Set<String> scrub,
            BrowserPageOps ops,
            Options options) {
        int timeout = step.timeoutMs() != null ? step.timeoutMs() : options.defaultStepTimeoutMs();
        switch (step.action()) {
            case BrowserRecipeSpec.NAVIGATE -> {
                String url = fill(step.url(), values, vault, scrub, true);
                String host = BrowserStorageState.httpHostOf(url);
                if (host == null || !BrowserStorageState.hostAllowed(host, spec.allowedDomains())) {
                    throw new StepFailure(
                            "the navigate target is outside the recipe's allowed domains");
                }
                ops.navigate(url);
            }
            case BrowserRecipeSpec.CLICK ->
                    ops.click(fill(step.selector(), values, vault, scrub, false));
            case BrowserRecipeSpec.TYPE ->
                    ops.type(
                            fill(step.selector(), values, vault, scrub, false),
                            fill(step.text(), values, vault, scrub, false));
            case BrowserRecipeSpec.SELECT ->
                    ops.select(
                            fill(step.selector(), values, vault, scrub, false),
                            fill(step.value(), values, vault, scrub, false));
            case BrowserRecipeSpec.WAIT_FOR -> {
                // The wait IS the expectation, verified below.
            }
            default -> throw new StepFailure("unknown action '" + step.action() + "'");
        }
        if (!BrowserRecipeSpec.WAIT_FOR.equals(step.action())) {
            ops.settle(Math.min(timeout, SETTLE_CAP_MS));
        }
        String problem = verify(step.expect(), spec.allowedDomains(), values, ops, timeout);
        if (problem != null) {
            throw new StepFailure("verification failed after step " + n + ": " + problem);
        }
    }

    /**
     * Null when the page satisfies the step's expectation (and is still on an allowed host), else
     * why not.
     */
    private String verify(
            Expect expect,
            List<String> allowedDomains,
            Map<String, String> values,
            BrowserPageOps ops,
            int timeoutMs) {
        String leftDomains = domainProblem(ops.currentUrl(), allowedDomains);
        if (leftDomains != null) {
            return leftDomains;
        }
        if (expect == null || expect.isEmpty()) {
            return null;
        }
        String urlHost = paramsOnly(expect.urlHost(), values);
        String pathPrefix = paramsOnly(expect.urlPathPrefix(), values);
        String urlContains = paramsOnly(expect.urlContains(), values);
        String textContains = paramsOnly(expect.textContains(), values);
        String selector = paramsOnly(expect.selector(), values);
        long deadline = nowMs.getAsLong() + Math.max(0, timeoutMs);
        String reason;
        while (true) {
            reason = checkOnce(urlHost, pathPrefix, urlContains, textContains, selector, ops);
            if (reason == null) {
                // Re-check the domain: the awaited navigation may have landed somewhere else.
                return domainProblem(ops.currentUrl(), allowedDomains);
            }
            if (nowMs.getAsLong() >= deadline) {
                return reason;
            }
            sleepMs.accept(POLL_INTERVAL_MS);
        }
    }

    private static String checkOnce(
            String urlHost,
            String pathPrefix,
            String urlContains,
            String textContains,
            String selector,
            BrowserPageOps ops) {
        String url = ops.currentUrl();
        if (notBlank(urlHost) || notBlank(pathPrefix) || notBlank(urlContains)) {
            String host = BrowserStorageState.httpHostOf(url);
            if (notBlank(urlHost) && (host == null || !host.equalsIgnoreCase(urlHost.strip()))) {
                return "expected the page host to be "
                        + urlHost.strip()
                        + " but it is "
                        + (host == null ? "not a web page" : host);
            }
            if (notBlank(pathPrefix)) {
                String path = pathOf(url);
                if (path == null || !path.startsWith(pathPrefix.strip())) {
                    return "expected the page path to start with " + pathPrefix.strip();
                }
            }
            if (notBlank(urlContains) && (url == null || !url.contains(urlContains.strip()))) {
                return "expected the page URL to contain " + urlContains.strip();
            }
        }
        if (notBlank(textContains)) {
            String text = ops.pageText();
            if (text == null
                    || !text.toLowerCase(Locale.ROOT)
                            .contains(textContains.strip().toLowerCase(Locale.ROOT))) {
                return "expected the page to show the text '"
                        + abbreviate(textContains.strip())
                        + "'";
            }
        }
        if (notBlank(selector) && !ops.waitForElement(selector.strip(), (int) POLL_INTERVAL_MS)) {
            return "expected an element matching '"
                    + abbreviate(selector.strip())
                    + "' to be on the page";
        }
        return null;
    }

    private static String domainProblem(String url, List<String> allowedDomains) {
        String host = BrowserStorageState.httpHostOf(url);
        if (host != null && !BrowserStorageState.hostAllowed(host, allowedDomains)) {
            return "the page left the recipe's allowed domains (now on " + host + ")";
        }
        return null;
    }

    // ---- placeholder resolution ------------------------------------------------------------

    /**
     * Resolves parameter, secret and TOTP placeholders; secret values are added to {@code scrub}.
     */
    private static String fill(
            String template,
            Map<String, String> values,
            SecretValues vault,
            Set<String> scrub,
            boolean urlEncode) {
        if (template == null) {
            return null;
        }
        return BrowserRecipePlaceholders.substitute(
                template,
                token -> {
                    switch (token.kind()) {
                        case PARAM -> {
                            String v = values.get(token.name().toLowerCase(Locale.ROOT));
                            if (v == null) {
                                throw new StepFailure("missing parameter '" + token.name() + "'");
                            }
                            return urlEncode
                                    ? URLEncoder.encode(v, StandardCharsets.UTF_8)
                                            .replace("+", "%20")
                                    : v;
                        }
                        case SECRET -> {
                            String v =
                                    vault.secret(token.name())
                                            .orElseThrow(
                                                    () ->
                                                            new StepFailure(
                                                                    "the vault secret "
                                                                            + token.name()
                                                                            + " is not available (missing, revoked or not visible)"));
                            scrub.add(v);
                            return v;
                        }
                        case TOTP -> {
                            String v =
                                    vault.totp(token.name())
                                            .orElseThrow(
                                                    () ->
                                                            new StepFailure(
                                                                    "could not compute a TOTP code from the vault secret "
                                                                            + token.name()));
                            scrub.add(v);
                            return v;
                        }
                        default -> throw new StepFailure("unsupported placeholder");
                    }
                });
    }

    /**
     * Expectation fields may only reference parameters; an undeclared/secret token is left as
     * literal text.
     */
    private static String paramsOnly(String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        return BrowserRecipePlaceholders.substitute(
                template,
                token -> {
                    if (token.kind() == Kind.PARAM) {
                        String v = values.get(token.name().toLowerCase(Locale.ROOT));
                        return v == null ? token.raw() : v;
                    }
                    return token.raw();
                });
    }

    // ---- outcome helpers ----------------------------------------------------------------------

    private Outcome fail(
            BrowserPageOps ops,
            int completed,
            int total,
            int n,
            RecipeStep step,
            String target,
            String reason) {
        return new Outcome(
                Status.FAILED, completed, total, n, step.action(), target, reason, safeUrl(ops));
    }

    private static String safeUrl(BrowserPageOps ops) {
        try {
            return ops.currentUrl();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String scrubbed(String text, Set<String> scrub) {
        String out = text == null ? "" : text;
        for (String secret : scrub) {
            if (secret != null && secret.length() >= 4) {
                out = out.replace(secret, "[redacted]");
            }
        }
        return out;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        // Playwright messages carry a multi-line call log; the first line is the actionable part.
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline).strip() : message.strip();
    }

    private static Map<String, String> lowerCased(Map<String, String> params) {
        Map<String, String> out = new HashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey().strip().toLowerCase(Locale.ROOT), e.getValue());
                }
            }
        }
        return out;
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }
}
