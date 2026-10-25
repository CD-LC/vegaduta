package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agents.tools.desktop.DesktopRecipePlaceholders.Kind;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.Expect;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.RecipeStep;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Deterministic replay of a {@link DesktopRecipeSpec} with per-step verification - the "replay"
 * half of run-to-script for the {@code local_desktop_*} tools. No AI in the loop: each step is
 * executed through an {@link Executor} (the {@link DesktopAgentClient} port), then verified
 * through the sidecar's {@code POST /ui} accessibility tree; on ANY failed verification (or
 * failed action, missing parameter or secret, exhausted time budget) the replay STOPS and
 * reports which step failed and why. It never skips a failed step and never silently continues.
 * Mirrors {@code BrowserRecipeReplayer}.
 *
 * <p>Safety properties, each pinned by DesktopRecipeReplayerTest:
 *
 * <ul>
 *   <li>Secrets: {@code {{secret.NAME}}} is resolved here through {@link SecretValues}
 *       (server-side), used for exactly one action, and scrubbed from every message in the {@link
 *       Outcome} - the model only ever sees placeholders and step numbers.
 *   <li>Irreversible steps (a press labelled submit/pay/delete/send, a bare Return) are NOT
 *       executed unless {@link Options#allowIrreversible()}; the replay pauses before them and
 *       says how to resume.
 *   <li>Time: each step's verification waits at most its timeout, and the whole replay at most
 *       {@link Options#maxReplayMs()}.
 *   <li>Pace: {@link Options#monitorStepDelayMs()} inserts a pause between steps so the person
 *       can watch the replay happen (the recipe's MONITOR mode).
 * </ul>
 */
public final class DesktopRecipeReplayer {

    static final long POLL_INTERVAL_MS = 250;
    private static final int MAX_PARAM_VALUE_LENGTH = 2_000;

    /**
     * What the replay asks of the desktop agent - one POST. {@link DesktopAgentClient} satisfies
     * it 1:1 (a method reference), and tests fake it with a lambda.
     */
    @FunctionalInterface
    public interface Executor {
        JsonNode post(String path, Map<String, Object> body) throws IOException;
    }

    /**
     * Server-side resolution of vault-backed {@code {{secret.NAME}}} placeholders; values never
     * leave the replay except into the laptop's keyboard focus.
     */
    @FunctionalInterface
    public interface SecretValues {

        Optional<String> secret(String name);

        /** No vault: every secret placeholder fails the step. */
        SecretValues NONE = name -> Optional.empty();
    }

    /**
     * Replay settings.
     *
     * @param allowIrreversible execute steps flagged irreversible (the caller has confirmed
     *     them)
     * @param stepTimeoutMs verification budget for a step with no timeout of its own
     * @param maxReplayMs wall-clock budget for the whole replay
     * @param fromStep 1-based step to start at (resume after a confirmation); 1 = from the start
     * @param monitorStepDelayMs pause inserted between steps in MONITOR mode; 0 = back-to-back
     */
    public record Options(
            boolean allowIrreversible,
            int stepTimeoutMs,
            long maxReplayMs,
            int fromStep,
            int monitorStepDelayMs) {

        public static Options standard() {
            return new Options(false, 15_000, 120_000L, 1, 0);
        }
    }

    /** One executed (or attempted) step: index is 1-based, {@code note} is scrubbed and short. */
    public record StepResult(int index, String action, boolean ok, String note) {}

    /**
     * The result, safe to hand to the model: step numbers, action names and scrubbed reasons. A
     * {@code null} {@code failure} means success; on failure it carries the 1-based step and the
     * remedy (for a confirmation pause: re-run with {@code confirmIrreversible=true} and {@code
     * fromStep=N}).
     *
     * @param stepsRun steps that completed successfully this call
     */
    public record Outcome(
            boolean success, int stepsRun, int stepsTotal, String failure, List<StepResult> steps) {}

    private final LongSupplier nowMs;
    private final LongConsumer sleepMs;

    public DesktopRecipeReplayer() {
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
    public DesktopRecipeReplayer(LongSupplier nowMs, LongConsumer sleepMs) {
        this.nowMs = nowMs;
        this.sleepMs = sleepMs;
    }

    /** Thrown inside a step to stop the replay with a (pre-scrubbed) reason. */
    private static final class StepFailure extends RuntimeException {
        StepFailure(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Runs {@code spec} against {@code executor}. Never throws for a step-level problem - it is
     * reported in the {@link Outcome} instead. {@code secrets} may be null (treated as {@link
     * SecretValues#NONE}).
     */
    public Outcome replay(
            DesktopRecipeSpec spec,
            Map<String, String> params,
            Executor executor,
            SecretValues secrets,
            Options options) {
        List<RecipeStep> steps = spec.steps();
        int total = steps.size();
        Map<String, String> values = lowerCased(params);
        for (ParamSpec p : spec.params()) {
            String v = values.get(p.name().toLowerCase(Locale.ROOT));
            if (p.required() && (v == null || v.isBlank())) {
                return new Outcome(
                        false,
                        0,
                        total,
                        "missing required parameter '" + p.name() + "'",
                        List.of());
            }
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() != null && e.getValue().length() > MAX_PARAM_VALUE_LENGTH) {
                return new Outcome(
                        false,
                        0,
                        total,
                        "parameter '"
                                + e.getKey()
                                + "' is longer than "
                                + MAX_PARAM_VALUE_LENGTH
                                + " characters",
                        List.of());
            }
        }
        SecretValues vault = secrets == null ? SecretValues.NONE : secrets;
        Set<String> scrub = new LinkedHashSet<>();
        List<StepResult> results = new ArrayList<>();
        long start = nowMs.getAsLong();
        int first = Math.max(1, Math.min(options.fromStep(), total));
        for (int i = first - 1; i < total; i++) {
            RecipeStep step = steps.get(i);
            int n = i + 1;
            if (nowMs.getAsLong() - start > options.maxReplayMs()) {
                return new Outcome(
                        false,
                        succeeded(results),
                        total,
                        "the replay time budget ("
                                + options.maxReplayMs() / 1000
                                + "s) ran out before step "
                                + n,
                        List.copyOf(results));
            }
            if (step.irreversible() && !options.allowIrreversible()) {
                return new Outcome(
                        false,
                        succeeded(results),
                        total,
                        "step "
                                + n
                                + " ("
                                + step.action()
                                + ") is flagged irreversible and was NOT run. Confirm it with the user, then "
                                + "run again with confirmIrreversible=true and fromStep="
                                + n
                                + ".",
                        List.copyOf(results));
            }
            try {
                String note = runStep(step, values, vault, scrub, executor, options);
                results.add(new StepResult(n, step.action(), true, note));
            } catch (StepFailure failure) {
                results.add(
                        new StepResult(
                                n,
                                step.action(),
                                false,
                                scrubbed(failure.getMessage(), scrub)));
                return new Outcome(
                        false,
                        succeeded(results),
                        total,
                        "step " + n + " failed: " + scrubbed(failure.getMessage(), scrub),
                        List.copyOf(results));
            } catch (RuntimeException e) {
                String reason = scrubbed(describe(e), scrub);
                results.add(new StepResult(n, step.action(), false, reason));
                return new Outcome(
                        false,
                        succeeded(results),
                        total,
                        "step " + n + " failed: " + reason,
                        List.copyOf(results));
            }
            if (options.monitorStepDelayMs() > 0 && i < total - 1) {
                sleepMs.accept(options.monitorStepDelayMs());
            }
        }
        return new Outcome(true, succeeded(results), total, null, List.copyOf(results));
    }

    private static int succeeded(List<StepResult> results) {
        int n = 0;
        for (StepResult r : results) {
            if (r.ok()) {
                n++;
            }
        }
        return n;
    }

    // ---- one step ---------------------------------------------------------------------------

    private String runStep(
            RecipeStep step,
            Map<String, String> values,
            SecretValues vault,
            Set<String> scrub,
            Executor executor,
            Options options) {
        int timeout = step.timeoutMs() != null ? step.timeoutMs() : options.stepTimeoutMs();
        String note;
        switch (step.action()) {
            case DesktopRecipeSpec.OPEN -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("target", fill(step.target(), values, vault, scrub));
                if (step.app() != null) {
                    body.put("application", step.app());
                }
                post(executor, "/open", body);
                note = "opened " + abbreviate(step.target());
            }
            case DesktopRecipeSpec.ACTIVATE -> {
                post(executor, "/activate", Map.of("app", step.app()));
                note = "frontmost app " + step.app();
            }
            case DesktopRecipeSpec.PRESS -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("label", fill(step.label(), values, vault, scrub));
                if (step.role() != null) {
                    body.put("role", step.role());
                }
                if (step.app() != null) {
                    body.put("app", step.app());
                }
                JsonNode reply = post(executor, "/ui/press", body);
                String matched = reply.path("matched").path("label").asText(null);
                note = matched == null ? "pressed " + abbreviate(step.label()) : "matched '" + matched + "'";
            }
            case DesktopRecipeSpec.CLICK -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("x", step.x());
                body.put("y", step.y());
                post(executor, "/click", body);
                note = "clicked (" + step.x() + "," + step.y() + ")";
            }
            case DesktopRecipeSpec.TYPE -> {
                String text = fill(step.text(), values, vault, scrub);
                post(executor, "/type", Map.of("text", text));
                note = "typed text";
            }
            case DesktopRecipeSpec.KEY -> {
                post(executor, "/key", Map.of("key", fill(step.key(), values, vault, scrub)));
                note = "pressed " + abbreviate(step.key());
            }
            case DesktopRecipeSpec.WAIT -> {
                sleepMs.accept(step.waitMs());
                note = "waited " + step.waitMs() + "ms";
            }
            default -> throw new StepFailure("unknown action '" + step.action() + "'");
        }
        String problem = verify(step.expect(), values, executor, timeout);
        if (problem != null) {
            throw new StepFailure("verification failed after this step: " + problem);
        }
        return note;
    }

    /**
     * The desktop agent answers failures with an HTTP error (surfaced as {@link IOException}) or,
     * defensively, a 200 body carrying {@code error}; both stop the step.
     */
    private static JsonNode post(Executor executor, String path, Map<String, Object> body) {
        JsonNode reply;
        try {
            reply = executor.post(path, body);
        } catch (IOException e) {
            throw new StepFailure(
                    e.getMessage() == null ? "the desktop agent call failed" : e.getMessage());
        }
        if (reply == null) {
            throw new StepFailure("the desktop agent sent an empty reply");
        }
        String error = reply.path("error").asText(null);
        if (error != null && !error.isBlank()) {
            throw new StepFailure(error);
        }
        return reply;
    }

    /**
     * Null when the frontmost app's UI tree satisfies the step's expectation, else why not.
     * Polls {@code POST /ui} until the expectation holds or the step's timeout expires.
     */
    private String verify(
            Expect expect, Map<String, String> values, Executor executor, int timeoutMs) {
        if (expect == null || expect.isEmpty()) {
            return null;
        }
        String elementLabel = paramsOnly(expect.elementLabel(), values);
        String appFrontmost = expect.appFrontmost(); // always a literal (the validator)
        long deadline = nowMs.getAsLong() + Math.max(0, timeoutMs);
        while (true) {
            String reason = checkOnce(elementLabel, appFrontmost, executor);
            if (reason == null) {
                return null;
            }
            if (nowMs.getAsLong() >= deadline) {
                return reason;
            }
            sleepMs.accept(POLL_INTERVAL_MS);
        }
    }

    private static String checkOnce(
            String elementLabel, String appFrontmost, Executor executor) {
        JsonNode ui;
        try {
            ui = executor.post("/ui", Map.of());
        } catch (IOException e) {
            return "could not inspect the screen: "
                    + (e.getMessage() == null ? "the desktop agent call failed" : e.getMessage());
        }
        if (ui == null || ui.path("error").isTextual()) {
            return "could not inspect the screen: "
                    + (ui == null ? "the desktop agent sent an empty reply" : ui.path("error").asText());
        }
        if (appFrontmost != null && !appFrontmost.isBlank()) {
            String front = ui.path("app").asText("");
            if (!front.equalsIgnoreCase(appFrontmost.strip())) {
                return "expected the frontmost app to be "
                        + appFrontmost.strip()
                        + " but it is "
                        + (front.isEmpty() ? "unknown" : front);
            }
        }
        if (elementLabel != null && !elementLabel.isBlank()) {
            String needle = elementLabel.strip().toLowerCase(Locale.ROOT);
            boolean found = false;
            for (JsonNode element : ui.path("elements")) {
                if (element.path("label").asText("").toLowerCase(Locale.ROOT).contains(needle)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return "expected an element labelled '" + abbreviate(elementLabel.strip()) + "' on screen";
            }
        }
        return null;
    }

    // ---- placeholder resolution ------------------------------------------------------------

    /**
     * Resolves parameter and secret placeholders; resolved secret values are added to {@code
     * scrub} so they can never reach an outcome message.
     */
    private static String fill(
            String template, Map<String, String> values, SecretValues vault, Set<String> scrub) {
        if (template == null) {
            return null;
        }
        return DesktopRecipePlaceholders.substitute(
                template,
                token -> {
                    if (token.kind() == Kind.PARAM) {
                        String v = values.get(token.name().toLowerCase(Locale.ROOT));
                        if (v == null) {
                            throw new StepFailure("missing parameter '" + token.name() + "'");
                        }
                        return v;
                    }
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
                });
    }

    /**
     * Expectation fields may only reference parameters; an undeclared/secret token is left as
     * literal text (the validator refuses them anyway).
     */
    private static String paramsOnly(String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        return DesktopRecipePlaceholders.substitute(
                template,
                token ->
                        token.kind() == Kind.PARAM
                                ? values.getOrDefault(
                                        token.name().toLowerCase(Locale.ROOT), token.raw())
                                : token.raw());
    }

    // ---- helpers ----------------------------------------------------------------------------

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
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline).strip() : message.strip();
    }

    private static Map<String, String> lowerCased(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() != null) {
                    out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }
            }
        }
        return out;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 40 ? s : s.substring(0, 37) + "...";
    }
}
