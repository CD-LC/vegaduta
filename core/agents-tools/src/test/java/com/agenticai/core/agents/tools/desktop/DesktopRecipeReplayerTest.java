package com.agenticai.core.agents.tools.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.Expect;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.RecipeStep;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The replay engine against a fake desktop agent: step ordering and endpoints, the stop-on-first-
 * failure contract, the irreversible pause, verification through {@code /ui}, secret resolution and
 * scrubbing, monitor pacing and the overall time budget.
 */
class DesktopRecipeReplayerTest {

    private final AtomicLong now = new AtomicLong();
    private final List<Long> sleeps = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private final Deque<Object> replies = new ArrayDeque<>();
    private DesktopRecipeReplayer replayer;

    private final DesktopRecipeReplayer.Executor fake =
            (path, body) -> {
                calls.add(path + " " + body);
                Object next = replies.poll();
                if (next instanceof IOException io) {
                    throw io;
                }
                return (JsonNode) next;
            };

    private static JsonNode json(String s) throws IOException {
        return JsonMappers.plain().readTree(s);
    }

    private static JsonNode ok() throws IOException {
        return json("{\"ok\":true}");
    }

    private static RecipeStep step(
            String action,
            String app,
            String label,
            Integer x,
            Integer y,
            String text,
            String key,
            String target,
            Integer waitMs,
            Expect expect,
            boolean irreversible) {
        return new RecipeStep(
                action,
                app,
                label,
                null,
                x,
                y,
                text,
                key,
                target,
                waitMs,
                expect,
                null,
                irreversible);
    }

    private static DesktopRecipeSpec spec(List<RecipeStep> steps) {
        return new DesktopRecipeSpec(
                DesktopRecipeSpec.CURRENT_VERSION, List.of("safari", "notes"), List.of(), steps);
    }

    @BeforeEach
    void setUp() {
        replayer = new DesktopRecipeReplayer(now::get, sleeps::add);
        calls.clear();
        replies.clear();
        sleeps.clear();
        now.set(0);
    }

    private DesktopRecipeReplayer.Options options(boolean irreversible, int fromStep, int monitor) {
        return new DesktopRecipeReplayer.Options(irreversible, 5_000, 60_000, fromStep, monitor);
    }

    @Test
    void replaysEveryStepToItsEndpoint() throws Exception {
        replies.add(ok());
        replies.add(ok());
        replies.add(ok());
        replies.add(ok());
        replies.add(ok());
        replies.add(ok());
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "open",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "https://x.example",
                                        null,
                                        null,
                                        false),
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "press", "Safari", "Allow", null, null, null, null, null,
                                        null, null, false),
                                step(
                                        "click", null, null, 10, 20, null, null, null, null, null,
                                        false),
                                step(
                                        "type", null, null, null, null, "hi", null, null, null,
                                        null, false),
                                step(
                                        "key", null, null, null, null, null, "tab", null, null,
                                        null, false)));

        DesktopRecipeReplayer.Outcome o =
                replayer.replay(s, Map.of(), fake, null, options(false, 1, 0));

        assertThat(o.success()).isTrue();
        assertThat(o.stepsRun()).isEqualTo(6);
        assertThat(o.stepsTotal()).isEqualTo(6);
        assertThat(o.failure()).isNull();
        assertThat(calls.get(0)).startsWith("/open");
        assertThat(calls.get(1)).isEqualTo("/activate {app=Safari}");
        assertThat(calls.get(2)).startsWith("/ui/press");
        assertThat(calls.get(3)).isEqualTo("/click {x=10, y=20}");
        assertThat(calls.get(4)).isEqualTo("/type {text=hi}");
        assertThat(calls.get(5)).isEqualTo("/key {key=tab}");
        assertThat(o.steps()).hasSize(6).allMatch(DesktopRecipeReplayer.StepResult::ok);
    }

    @Test
    void aWaitStepSleeps() throws Exception {
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "wait", null, null, null, null, null, null, null, 1_500,
                                        null, false)));
        replies.add(ok());
        DesktopRecipeReplayer.Outcome o =
                replayer.replay(s, Map.of(), fake, null, options(false, 1, 0));
        assertThat(o.success()).isTrue();
        assertThat(sleeps).containsExactly(1_500L);
    }

    @Test
    void stopsAtTheFirstFailedStepAndNeverContinues() throws Exception {
        replies.add(ok());
        replies.add(new IOException("The desktop agent refused the action: no UI element"));
        replies.add(ok());
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "press", "Safari", "Save", null, null, null, null, null,
                                        null, null, false),
                                step(
                                        "type", null, null, null, null, "x", null, null, null, null,
                                        false)));

        DesktopRecipeReplayer.Outcome o =
                replayer.replay(s, Map.of(), fake, null, options(false, 1, 0));

        assertThat(o.success()).isFalse();
        assertThat(o.stepsRun()).isEqualTo(1);
        assertThat(o.failure()).contains("step 2").contains("no UI element");
        assertThat(calls).hasSize(2); // the third step was never attempted
        assertThat(o.steps().get(1).ok()).isFalse();
    }

    @Test
    void pausesBeforeAnIrreversibleStepAndSaysHowToResume() throws Exception {
        replies.add(ok());
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "press", "Safari", "Pay now", null, null, null, null, null,
                                        null, null, true),
                                step(
                                        "type", null, null, null, null, "x", null, null, null, null,
                                        false)));

        DesktopRecipeReplayer.Outcome o =
                replayer.replay(s, Map.of(), fake, null, options(false, 1, 0));

        assertThat(o.success()).isFalse();
        assertThat(o.stepsRun()).isEqualTo(1);
        assertThat(o.failure())
                .contains("irreversible")
                .contains("confirmIrreversible=true")
                .contains("fromStep=2");
        assertThat(calls).hasSize(1);

        // Resuming with confirmation and fromStep=2 runs the rest.
        replies.add(ok());
        replies.add(ok());
        DesktopRecipeReplayer.Outcome resumed =
                replayer.replay(s, Map.of(), fake, null, options(true, 2, 0));
        assertThat(resumed.success()).isTrue();
        assertThat(resumed.stepsRun()).isEqualTo(2);
    }

    @Test
    void aMissingRequiredParameterFailsBeforeAnyStep() {
        DesktopRecipeSpec s =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("safari"),
                        List.of(new ParamSpec("city", true)),
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false)));

        DesktopRecipeReplayer.Outcome o =
                replayer.replay(s, Map.of(), fake, null, options(false, 1, 0));
        assertThat(o.success()).isFalse();
        assertThat(o.stepsRun()).isZero();
        assertThat(o.failure()).contains("city");
        assertThat(calls).isEmpty();
    }

    @Test
    void parametersFillLabelsTargetsAndText() throws Exception {
        replies.add(ok());
        replies.add(ok());
        DesktopRecipeSpec s =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("safari"),
                        List.of(new ParamSpec("name", true)),
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "type",
                                        null,
                                        null,
                                        null,
                                        null,
                                        "hello {{name}}",
                                        null,
                                        null,
                                        null,
                                        null,
                                        false)));

        replayer.replay(s, Map.of("name", "Ada"), fake, null, options(false, 1, 0));
        assertThat(calls.get(1)).isEqualTo("/type {text=hello Ada}");
    }

    @Test
    void secretsAreResolvedUsedOnceAndScrubbed() throws Exception {
        replies.add(ok());
        replies.add(new IOException("the agent echoed hunter2secret back"));
        DesktopRecipeSpec s =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("safari"),
                        List.of(),
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "type",
                                        null,
                                        null,
                                        null,
                                        null,
                                        "{{secret.DESKTOP_PW}}",
                                        null,
                                        null,
                                        null,
                                        null,
                                        false)));

        DesktopRecipeReplayer.Outcome o =
                replayer.replay(
                        s,
                        Map.of(),
                        fake,
                        name ->
                                "DESKTOP_PW".equals(name)
                                        ? java.util.Optional.of("hunter2secret")
                                        : java.util.Optional.empty(),
                        options(false, 1, 0));

        assertThat(calls.get(1)).isEqualTo("/type {text=hunter2secret}");
        assertThat(o.success()).isFalse();
        assertThat(o.failure()).doesNotContain("hunter2secret").contains("[redacted]");
    }

    @Test
    void anUnresolvedSecretFailsTheStep() throws Exception {
        replies.add(ok());
        DesktopRecipeSpec s =
                new DesktopRecipeSpec(
                        DesktopRecipeSpec.CURRENT_VERSION,
                        List.of("safari"),
                        List.of(),
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "type",
                                        null,
                                        null,
                                        null,
                                        null,
                                        "{{secret.DESKTOP_PW}}",
                                        null,
                                        null,
                                        null,
                                        null,
                                        false)));
        DesktopRecipeReplayer.Outcome o =
                replayer.replay(
                        s,
                        Map.of(),
                        fake,
                        DesktopRecipeReplayer.SecretValues.NONE,
                        options(false, 1, 0));
        assertThat(o.success()).isFalse();
        assertThat(o.failure()).contains("DESKTOP_PW");
    }

    @Test
    void verificationPollsTheUiTreeUntilItMatches() throws Exception {
        replies.add(ok()); // the activate
        replies.add(ok()); // the press itself
        replies.add(json("{\"app\":\"Safari\",\"elements\":[{\"label\":\"Loading...\"}]}"));
        replies.add(
                json(
                        "{\"app\":\"Safari\",\"elements\":[{\"label\":\"Sent\",\"role\":\"AXStaticText\"}]}"));
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "press",
                                        "Safari",
                                        "Send",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        new Expect("Sent", "safari"),
                                        true)));

        DesktopRecipeReplayer.Outcome o =
                replayer.replay(s, Map.of(), fake, null, options(true, 1, 0));

        assertThat(o.success()).isTrue();
        assertThat(calls).hasSize(4); // activate + press + two /ui polls
        assertThat(calls.get(2)).isEqualTo("/ui {}");
        assertThat(calls.get(3)).isEqualTo("/ui {}");
        assertThat(sleeps).contains(DesktopRecipeReplayer.POLL_INTERVAL_MS);
    }

    @Test
    void verificationFailsTheStepWhenTheTimeoutRunsOut() throws Exception {
        replies.add(ok());
        replies.add(ok());
        replies.add(json("{\"app\":\"Finder\",\"elements\":[]}"));
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "press",
                                        "Safari",
                                        "Go",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        new Expect("Done", null),
                                        false)));

        // The clock reads: start, the two per-step budget checks, the verify deadline, then the
        // post-poll re-check - that last one lands past the step's 5s timeout so one poll runs
        // and the step gives up (without tripping the 60s overall budget).
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        DesktopRecipeReplayer fast =
                new DesktopRecipeReplayer(
                        () -> reads.getAndIncrement() >= 4 ? 60_000L : 0L, sleeps::add);

        DesktopRecipeReplayer.Outcome o =
                fast.replay(s, Map.of(), fake, null, options(false, 1, 0));

        assertThat(o.success()).isFalse();
        assertThat(o.stepsRun()).isEqualTo(1);
        assertThat(o.failure()).contains("verification failed").contains("Done");
    }

    @Test
    void monitorModePausesBetweenSteps() throws Exception {
        replies.add(ok());
        replies.add(ok());
        replies.add(ok());
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "press", "Safari", "A", null, null, null, null, null, null,
                                        null, false),
                                step(
                                        "press", "Safari", "B", null, null, null, null, null, null,
                                        null, false)));

        replayer.replay(s, Map.of(), fake, null, options(false, 1, 800));
        assertThat(sleeps).containsExactly(800L, 800L); // between steps, not after the last
    }

    @Test
    void theOverallBudgetStopsTheReplay() throws Exception {
        replies.add(ok());
        replies.add(ok());
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "press", "Safari", "A", null, null, null, null, null, null,
                                        null, false),
                                step(
                                        "press", "Safari", "B", null, null, null, null, null, null,
                                        null, false)));
        // The second clock read lands past the 60s budget, so the replay stops before step 1.
        AtomicLong clock = new AtomicLong();
        DesktopRecipeReplayer fast =
                new DesktopRecipeReplayer(() -> clock.getAndAdd(61_000), sleeps::add);

        DesktopRecipeReplayer.Outcome o =
                fast.replay(s, Map.of(), fake, null, options(false, 1, 0));
        assertThat(o.success()).isFalse();
        assertThat(o.failure()).contains("time budget");
    }

    @Test
    void aTwoHundredReplyCarryingAnErrorStillStopsTheStep() throws Exception {
        replies.add(ok());
        replies.add(json("{\"error\":\"weird agent state\"}"));
        DesktopRecipeSpec s =
                spec(
                        List.of(
                                step(
                                        "activate",
                                        "Safari",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false),
                                step(
                                        "press", "Safari", "X", null, null, null, null, null, null,
                                        null, false)));
        DesktopRecipeReplayer.Outcome o =
                replayer.replay(s, Map.of(), fake, null, options(false, 1, 0));
        assertThat(o.success()).isFalse();
        assertThat(o.failure()).contains("weird agent state");
    }
}
