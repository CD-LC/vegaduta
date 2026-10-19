package com.agenticai.core.agents.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.coding.CodingTaskDtos.Budget;
import com.agenticai.core.agents.coding.CodingTaskTestSupport.FakeSandbox;
import com.agenticai.core.sdlc.harness.CodingHarnessLoop;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LlmCodingFixerTest {

    private final FakeSandbox sandbox = new FakeSandbox();
    private final AtomicLong now = new AtomicLong(0);

    private CodingTaskBudget budget(int steps) {
        return new CodingTaskBudget(new Budget(steps, 600, 100_000, 3), now::get);
    }

    private LlmCodingFixer fixer(CodingModel model, CodingTaskBudget budget) {
        return new LlmCodingFixer(model, budget, sandbox.internal(), "Add validation", "mvn -q test");
    }

    private static CodingModel scripted(List<String> prompts, String... replies) {
        Deque<String> queue = new ArrayDeque<>(List.of(replies));
        return (system, user) -> {
            prompts.add(user);
            return queue.isEmpty() ? "{}" : queue.poll();
        };
    }

    @Test
    void aReadRoundThenAnEditRoundWritesOnlyTheValidatedPaths() {
        List<String> prompts = new ArrayList<>();
        LlmCodingFixer f = fixer(scripted(prompts,
            "I want to look first: {\"read\": [\"src/Foo.java\", \"../../etc/passwd\", \"/etc/shadow\"]}",
            "```json\n{\"edits\": [{\"path\": \"src/Foo.java\", \"content\": \"class Foo { int x; }\"}], \"note\": \"added x\"}\n```"),
            budget(10));
        String note = f.attemptFix(0, "");
        assertThat(note).contains("1 file(s) written").contains("added x");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("FILE src/Foo.java");
        synchronized (sandbox.internalCommands) {
            // The traversal and absolute paths were dropped before any shell command was built for them.
            assertThat(sandbox.internalCommands.stream().filter(c -> c.contains("passwd") || c.contains("shadow")).toList()).isEmpty();
            assertThat(sandbox.internalCommands.stream().filter(c -> c.contains("base64 -d")).toList()).hasSize(1);
        }
    }

    @Test
    void theThirdRoundMustEditAndAPersistentReaderGetsNothingApplied() {
        List<String> prompts = new ArrayList<>();
        LlmCodingFixer f = fixer(scripted(prompts, "{\"read\":[\"a\"]}", "{\"read\":[\"b\"]}", "{\"read\":[\"c\"]}"), budget(10));
        assertThat(f.attemptFix(1, "FAILED")).contains("no edits");
        assertThat(prompts).hasSize(3);
        assertThat(prompts.get(2)).contains("Answer now with the \"edits\" object");
        assertThat(sandbox.wroteAnything()).isFalse();
    }

    @Test
    void anUnusableAnswerChangesNothing() {
        LlmCodingFixer f = fixer(scripted(new ArrayList<>(), "sure, I will fix it"), budget(10));
        assertThat(f.attemptFix(1, "FAILED")).contains("did not return a usable answer");
        assertThat(sandbox.wroteAnything()).isFalse();
    }

    @Test
    void badEditsAreSkippedAndReported() {
        LlmCodingFixer f = fixer(scripted(new ArrayList<>(),
            "{\"edits\": [{\"path\": \".git/config\", \"content\": \"x\"}, {\"path\": \"-rf\", \"content\": \"x\"}, "
                + "{\"path\": \"ok.txt\"}, {\"path\": \"a b.txt\", \"content\": \"x\"}, {\"path\": \"good.txt\", \"content\": \"fine\"}]}"),
            budget(10));
        String note = f.attemptFix(0, "");
        assertThat(note).contains("1 file(s) written").contains("skipped");
        synchronized (sandbox.internalCommands) {
            assertThat(sandbox.internalCommands.stream().filter(c -> c.contains("base64 -d")).toList()).hasSize(1);
        }
    }

    @Test
    void tooManyEditsAreCapped() {
        StringBuilder edits = new StringBuilder("{\"edits\": [");
        for (int i = 0; i < 40; i++) {
            edits.append(i == 0 ? "" : ",").append("{\"path\": \"f").append(i).append(".txt\", \"content\": \"x\"}");
        }
        edits.append("]}");
        String note = fixer(scripted(new ArrayList<>(), edits.toString()), budget(10)).attemptFix(0, "");
        assertThat(note).contains(LlmCodingFixer.MAX_EDITS + " file(s) written").contains("more than");
    }

    @Test
    void anOversizedFileEditIsSkipped() {
        String big = "x".repeat(70_000);
        String note = fixer(scripted(new ArrayList<>(), "{\"edits\": [{\"path\": \"big.txt\", \"content\": \"" + big + "\"}]}"), budget(10))
            .attemptFix(0, "");
        assertThat(note).contains("0 file(s) written").contains("skipped");
        assertThat(sandbox.wroteAnything()).isFalse();
    }

    @Test
    void everyModelCallIsMeteredAndABlownBudgetStopsTheNextOne() {
        CodingTaskBudget b = budget(1);
        LlmCodingFixer f = fixer(scripted(new ArrayList<>(), "{\"edits\": []}", "{\"edits\": []}"), b);
        f.attemptFix(0, "");
        assertThat(b.steps()).isEqualTo(1);
        assertThat(b.exhausted()).isTrue();
        assertThatThrownBy(() -> f.attemptFix(1, "FAILED")).isInstanceOf(CodingTaskBudget.ExhaustedException.class);
    }

    @Test
    void theFixerIsAHarnessFixer() {
        CodingHarnessLoop.Fixer asFixer = fixer(scripted(new ArrayList<>(), "{\"edits\": []}"), budget(5));
        assertThat(asFixer.attemptFix(0, "")).contains("0 file(s) written");
    }

    // ------------------------------------------------------------------------------------ the reviewer

    @Test
    void theReviewerFailsClosedOnAnythingButAnExplicitApproval() {
        CodingTaskBudget b = budget(10);
        String diff = CodingTaskTestSupport.DIFF;
        assertThat(new LlmCodingReviewer((s, u) -> "{\"approved\": true, \"notes\": \"fine\"}", b, "task", null).review(diff).approved()).isTrue();
        assertThat(new LlmCodingReviewer((s, u) -> "{\"approved\": false, \"notes\": \"no\"}", b, "task", null).review(diff).approved()).isFalse();
        assertThat(new LlmCodingReviewer((s, u) -> "approved!", b, "task", null).review(diff).approved()).isFalse();
        assertThat(new LlmCodingReviewer((s, u) -> "{\"notes\": \"hm\"}", b, "task", null).review(diff).approved()).isFalse();
        assertThat(new LlmCodingReviewer((s, u) -> "{\"approved\": true}", b, "task", null).review("").approved()).isFalse();
        assertThat(new LlmCodingReviewer((s, u) -> null, b, "task", null).review(diff).approved()).isFalse();
    }

    @Test
    void theReviewerSeesTheFullDiffAndIsToldWhenTestsWereTouched() {
        List<String> prompts = new ArrayList<>();
        String full = "diff --git a/src/test/java/FooTest.java b/src/test/java/FooTest.java\n-assertEquals(1, x);\n+assertTrue(true);\n";
        LlmCodingReviewer r = new LlmCodingReviewer((s, u) -> {
            prompts.add(u);
            return "{\"approved\": false, \"notes\": \"weakened a test\"}";
        }, budget(10), "task", () -> full);
        assertThat(r.review("tail only").approved()).isFalse();
        assertThat(prompts.get(0)).contains("FooTest.java").contains("modifies test files").contains("assertTrue(true)");
    }

    // ------------------------------------------------------------------------------------ support

    @Test
    void jsonExtractionToleratesFencesProseAndBracesInStrings() {
        assertThat(CodingModelSupport.extractObject("noise {\"a\": \"}{\"} tail").orElseThrow().get("a").asText()).isEqualTo("}{");
        assertThat(CodingModelSupport.extractObject("```json\n{\"a\": 1}\n```").orElseThrow().get("a").asInt()).isEqualTo(1);
        assertThat(CodingModelSupport.extractObject("{broken {\"a\": 2}").orElseThrow().get("a").asInt()).isEqualTo(2);
        assertThat(CodingModelSupport.extractObject("no json here")).isEmpty();
        assertThat(CodingModelSupport.extractObject(null)).isEmpty();
        assertThat(CodingModelSupport.extractObject("[1,2,3]")).isEmpty();
    }

    @Test
    void testFilesAreRecognisedAcrossEcosystems() {
        String diff = String.join("\n",
            "diff --git a/src/test/java/FooTest.java b/src/test/java/FooTest.java",
            "diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java",
            "diff --git a/tests/test_api.py b/tests/test_api.py",
            "diff --git a/pkg/util_test.go b/pkg/util_test.go",
            "diff --git a/web/app.spec.ts b/web/app.spec.ts",
            "diff --git a/README.md b/README.md");
        assertThat(CodingModelSupport.touchedTestFiles(diff)).containsExactly(
            "src/test/java/FooTest.java", "tests/test_api.py", "pkg/util_test.go", "web/app.spec.ts");
        assertThat(CodingModelSupport.countFiles(diff)).isEqualTo(6);
        assertThat(CodingModelSupport.touchedTestFiles(null)).isEmpty();
    }

    @Test
    void theBudgetCountsStepsTokensAndWallTimeAndRefusesToStartAnotherCall() {
        CodingTaskBudget b = new CodingTaskBudget(new Budget(3, 60, 1000, 1), now::get);
        assertThat(b.exhausted()).isFalse();
        b.afterModelCall(2000, 1000);
        assertThat(b.tokens()).isEqualTo(750);
        assertThat(b.exhausted()).isFalse();
        b.afterModelCall(1000, 1000);
        assertThat(b.exhausted()).isTrue();
        assertThat(b.stopMessage()).contains("token budget");
        assertThatThrownBy(b::beforeModelCall).isInstanceOf(CodingTaskBudget.ExhaustedException.class);

        CodingTaskBudget timed = new CodingTaskBudget(new Budget(30, 60, 100_000, 1), now::get);
        now.addAndGet(61_000);
        assertThat(timed.exhausted()).isTrue();
        assertThat(timed.stopMessage()).contains("time budget");
        assertThat(timed.remainingWallSeconds()).isEqualTo(1);
    }

    @Test
    void settingsCannotBeConfiguredPastTheHardCeilings() {
        CodingTaskSettings s = new CodingTaskSettings(true, false, 99, 99999, 999999, 999999999, 99999, 999999, 999999999, 99,
            Long.MAX_VALUE, 99999999, 99999, 99999, 99999, true, "E2B", " a.com , *.b.org ", 99999, 99999999);
        assertThat(s.caps().maxSteps()).isEqualTo(CodingTaskSettings.HARD_MAX_STEPS);
        assertThat(s.caps().maxWallSeconds()).isEqualTo(CodingTaskSettings.HARD_MAX_WALL_SECONDS);
        assertThat(s.caps().maxTokens()).isEqualTo(CodingTaskSettings.HARD_MAX_TOKENS);
        assertThat(s.caps().maxFixIterations()).isEqualTo(CodingTaskSettings.HARD_MAX_FIX_ITERATIONS);
        assertThat(s.defaults().maxSteps()).isLessThanOrEqualTo(s.caps().maxSteps());
        assertThat(s.maxArchiveBytes()).isEqualTo(CodingTaskSettings.HARD_MAX_ARCHIVE_BYTES);
        assertThat(s.runner()).isEqualTo("e2b");
        assertThat(s.gitAllowedHosts()).containsExactly("a.com", "*.b.org");
        assertThat(s.approvalTimeoutSeconds()).isEqualTo(3600);
    }
}
