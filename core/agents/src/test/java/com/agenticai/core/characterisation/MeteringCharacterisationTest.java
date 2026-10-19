package com.agenticai.core.characterisation;

import static com.agenticai.core.characterisation.ScriptedChatModel.call;
import static com.agenticai.core.characterisation.ScriptedChatModel.text;
import static com.agenticai.core.characterisation.ScriptedChatModel.toolCalls;
import static com.agenticai.core.characterisation.ToolLoopHarness.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.budget.PerUserBudgetTrackingAdvisor;
import com.agenticai.core.agents.hosted.FreeTierTokenTrackingAdvisor;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.LlmCallMetricsService;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * P0 characterisation (ADR-0012, section 6 P0.4, the most important suite): the token totals that the
 * four metering consumers record for a plain turn and for a 3-iteration tool turn, streaming and
 * non-streaming, with a scripted model that reports a KNOWN usage on every model call.
 *
 * <p>Consumers under test, wired exactly as a provider factory wires them (default advisors of one
 * ChatClient): {@link LlmMetricsAdvisor} (llm_call_log row + Micrometer meters, including
 * {@code CacheTokenAccounting}), {@link FreeTierTokenTrackingAdvisor} (token-window reconcile) and
 * {@link PerUserBudgetTrackingAdvisor} (per-user spend reconcile).
 *
 * <h2>What is pinned today (Spring AI 1.1.7)</h2>
 * Each provider {@code ChatModel} runs the tool loop internally and hands back ONE final
 * {@code ChatResponse} whose usage is already the cumulative sum over the loop
 * ({@code UsageCalculator.getCumulativeUsage}), with the provider-native usage payload discarded. The
 * advisors sit around that single call, so a turn is recorded exactly ONCE with the cumulative
 * totals: 3 iterations of (100,10), (150,10), (200,30) prompt/completion tokens are one row of
 * 450/50/500. Anthropic cache read/write counters survive only on a single-call turn; on a tool turn
 * the native usage is gone, so they read as ABSENT (null, no meter series), not zero.
 *
 * <h2>What P3 is expected to change (so a failure here is explainable)</h2>
 * In Spring AI 2 the loop runs in the ChatClient's {@code ToolCallingAdvisor} and it, not the model,
 * accumulates usage across all internal calls. The totals asserted here stay valid ONLY if the
 * metering advisors are ordered OUTSIDE the tool advisor (they then see the accumulated total once).
 * If they end up INSIDE it, the failing assertion is the row count: {@code rowsOf(...)} becomes 3
 * (one per iteration, prompt 100/150/200) or, worse, 3 cumulative rows (100/250/450) that
 * double-count. The other value that may legitimately change is the tool-turn cache accounting (the
 * accumulated usage may now carry native usage, so {@code cacheReadTokens} could become non-null).
 * Decide deliberately and update the two tests marked "P3-DECISION".
 */
class MeteringCharacterisationTest {

    private static final String TENANT = "tenant-1";
    private static final String USER = "user-1";
    private static final String MODEL = "claude-sonnet-4-5";
    private static final long FREE_TIER_RESERVED = 5_000L;
    private static final long BUDGET_RESERVED_MICRO_CENTS = 150_000L;

    /** One captured {@code LlmCallLogService.record(...)} invocation (full 21-arg form, incl. billable reasoning tokens). */
    private record LogRow(Long prompt, Long completion, Long total, boolean success, Long cacheCreation, Long cacheRead) {}

    /** One captured {@code reconcileTokenReservation}. */
    private record FreeTierReconcile(String tenant, long reserved, long actual) {}

    /** One captured {@code reconcileSpendReservationForCall} / {@code reconcileSpendReservationForUsage}. */
    private record BudgetReconcile(long reserved, Long prompt, Long completion) {}

    private final List<LogRow> rows = new CopyOnWriteArrayList<>();
    private final List<FreeTierReconcile> freeTier = new CopyOnWriteArrayList<>();
    private final List<BudgetReconcile> budget = new CopyOnWriteArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicInteger toolExecutions = new AtomicInteger();

    // --------------------------------------------------------------- rig

    private ChatClient rig(ScriptedChatModel model) {
        LlmCallLogService logService = Mockito.mock(LlmCallLogService.class, invocation -> {
            Object[] a = invocation.getArguments();
            if ("record".equals(invocation.getMethod().getName()) && a.length == 21) {
                rows.add(new LogRow((Long) a[4], (Long) a[5], (Long) a[6], (Boolean) a[7], (Long) a[17], (Long) a[18]));
            }
            return null;
        });
        FreeTierUsageLimiterService limiter = Mockito.mock(FreeTierUsageLimiterService.class, invocation -> {
            Object[] a = invocation.getArguments();
            if ("reconcileTokenReservation".equals(invocation.getMethod().getName())) {
                freeTier.add(new FreeTierReconcile((String) a[0], (Long) a[1], (Long) a[2]));
            }
            return null;
        });
        PerUserBudgetService budgetService = Mockito.mock(PerUserBudgetService.class, invocation -> {
            Object[] a = invocation.getArguments();
            if ("reconcileSpendReservationForCall".equals(invocation.getMethod().getName())) {
                budget.add(new BudgetReconcile((Long) a[3], (Long) a[5], (Long) a[6]));
            } else if ("reconcileSpendReservationForUsage".equals(invocation.getMethod().getName())) {
                // The Usage-carrying twin (prices reasoning tokens too); same reservation contract.
                org.springframework.ai.chat.metadata.Usage usage = (org.springframework.ai.chat.metadata.Usage) a[5];
                budget.add(new BudgetReconcile((Long) a[3],
                    usage == null || usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue(),
                    usage == null || usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().longValue()));
            }
            return null;
        });
        return ToolLoopHarness.client(model,
            new LlmMetricsAdvisor(new LlmCallMetricsService(), logService, ProviderName.ANTHROPIC, MODEL,
                meters, new LlmPricingCatalog()),
            new FreeTierTokenTrackingAdvisor(limiter, TENANT, FREE_TIER_RESERVED),
            new PerUserBudgetTrackingAdvisor(budgetService, TENANT, USER, Modality.TEXT, MODEL,
                BUDGET_RESERVED_MICRO_CENTS, TenantPlan.FREE));
    }

    private ToolCallback tool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("lookup")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }

            @Override
            public String call(String toolInput) {
                toolExecutions.incrementAndGet();
                return "{\"ok\":true}";
            }
        };
    }

    private static ScriptedChatModel model(ScriptedChatModel.Step... steps) {
        return new ScriptedChatModel(List.of(steps), ToolLoopHarness.resilientManager());
    }

    /** Waits for the (stream-terminal) callbacks, then returns a stable snapshot of the rows. */
    private List<LogRow> rowsOf(int expectedAtLeast) {
        await().atMost(Duration.ofSeconds(5)).until(() -> rows.size() >= expectedAtLeast);
        return List.copyOf(rows);
    }

    private void settle() throws InterruptedException {
        // Lets any (wrongly) duplicated terminal callback land before asserting "exactly once".
        Thread.sleep(150);
    }

    private double count(String meter) {
        return meters.find(meter).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private void runNonStreaming(ChatClient client, boolean withTool) {
        ChatClient.ChatClientRequestSpec spec = client.prompt().user("hello");
        if (withTool) {
            spec = spec.toolCallbacks(tool());
        }
        spec.call().content();
    }

    private void runStreaming(ChatClient client, boolean withTool) {
        ChatClient.ChatClientRequestSpec spec = client.prompt().user("hello");
        if (withTool) {
            spec = spec.toolCallbacks(tool());
        }
        spec.stream().content().collectList().block(Duration.ofSeconds(10));
    }

    private void assertTurnTotals(long prompt, long completion) throws InterruptedException {
        List<LogRow> logged = rowsOf(1);
        settle();
        logged = List.copyOf(rows);
        assertThat(logged).as("one llm_call_log row per user turn - never one per loop iteration").hasSize(1);
        assertThat(logged.get(0).success()).isTrue();
        assertThat(logged.get(0).prompt()).isEqualTo(prompt);
        assertThat(logged.get(0).completion()).isEqualTo(completion);
        assertThat(logged.get(0).total()).isEqualTo(prompt + completion);
        assertThat(count("llm.calls")).as("llm.calls counts turns, not iterations").isEqualTo(1.0);

        assertThat(freeTier).as("free-tier reservation is reconciled exactly once").hasSize(1);
        assertThat(freeTier.get(0).tenant()).isEqualTo(TENANT);
        assertThat(freeTier.get(0).reserved()).isEqualTo(FREE_TIER_RESERVED);
        assertThat(freeTier.get(0).actual()).isEqualTo(prompt + completion);

        assertThat(budget).as("per-user budget reservation is reconciled exactly once").hasSize(1);
        assertThat(budget.get(0).reserved()).isEqualTo(BUDGET_RESERVED_MICRO_CENTS);
        assertThat(budget.get(0).prompt()).isEqualTo(prompt);
        assertThat(budget.get(0).completion()).isEqualTo(completion);
    }

    // -------------------------------------------------------- plain turn

    @Test
    void plainTurnNonStreamingRecordsTheModelUsageExactlyOnce() throws Exception {
        ScriptedChatModel model = model(text(usage(100, 20), "hi there"));

        runNonStreaming(rig(model), false);

        assertThat(model.modelCalls()).isEqualTo(1);
        assertTurnTotals(100, 20);
        assertThat(rows.get(0).cacheCreation()).isNull();
        assertThat(rows.get(0).cacheRead()).isNull();
    }

    @Test
    void plainTurnStreamingRecordsTheModelUsageExactlyOnce() throws Exception {
        ScriptedChatModel model = model(text(usage(100, 20), "hi ", "there"));

        runStreaming(rig(model), false);

        assertThat(model.modelCalls()).isEqualTo(1);
        assertTurnTotals(100, 20);
    }

    // ------------------------------------------------- 3-iteration tool turn

    /** Iteration usages: (100,10) + (150,10) + (200,30) = 450 prompt, 50 completion, 500 total. */
    private ScriptedChatModel threeIterations() {
        return model(
            toolCalls(usage(100, 10), call("c1", "lookup", "{}")),
            toolCalls(usage(150, 10), call("c2", "lookup", "{}")),
            text(usage(200, 30), "final ", "answer"));
    }

    /**
     * P3-DECISION: totals must stay 450/50 in ONE row. See the class javadoc for what changes when
     * the metering advisors move inside Spring AI 2's ToolCallingAdvisor.
     */
    @Test
    void threeIterationToolTurnNonStreamingIsOneRowOfCumulativeTotals() throws Exception {
        ScriptedChatModel model = threeIterations();

        runNonStreaming(rig(model), true);

        assertThat(model.modelCalls()).isEqualTo(3);
        assertThat(toolExecutions.get()).isEqualTo(2);
        assertTurnTotals(450, 50);
    }

    /** P3-DECISION: same as above for the streaming path (the FAST reasoning depth, i.e. most traffic). */
    @Test
    void threeIterationToolTurnStreamingIsOneRowOfCumulativeTotals() throws Exception {
        ScriptedChatModel model = threeIterations();

        runStreaming(rig(model), true);

        assertThat(model.modelCalls()).isEqualTo(3);
        assertThat(toolExecutions.get()).isEqualTo(2);
        assertTurnTotals(450, 50);
    }

    @Test
    void noIterationIsCountedTwiceAcrossTheFourConsumers() throws Exception {
        runNonStreaming(rig(threeIterations()), true);
        rowsOf(1);
        settle();

        long promptSum = rows.stream().mapToLong(LogRow::prompt).sum();
        long budgetPromptSum = budget.stream().mapToLong(BudgetReconcile::prompt).sum();
        long freeTierSum = freeTier.stream().mapToLong(FreeTierReconcile::actual).sum();

        // The naive per-iteration sum would be 100+150+200 = 450 and the cumulative-per-iteration
        // sum 100+250+450 = 800. Only the first is right; a double count would show as 800 or 1350.
        assertThat(promptSum).isEqualTo(450);
        assertThat(budgetPromptSum).isEqualTo(450);
        assertThat(freeTierSum).isEqualTo(500);
    }

    // -------------------------------------------------------- cache tokens

    /**
     * Anthropic-shaped usage: promptTokens is the uncached input only, the cache counters live in the
     * native payload. P3 touch point: {@code AnthropicApi.Usage} moves package in Spring AI 2 (the
     * official anthropic-java SDK), so this helper is the only line to adapt.
     */
    private static Usage anthropicUsage(int input, int output, int cacheWrite, int cacheRead) {
        return new DefaultUsage(input, output, input + output,
            new AnthropicApi.Usage(input, output, cacheWrite, cacheRead));
    }

    @Test
    void singleCallTurnKeepsAnthropicCacheReadAndWriteTokens() throws Exception {
        ScriptedChatModel model = model(text(anthropicUsage(300, 40, 100, 500), "cached answer"));

        runNonStreaming(rig(model), false);

        List<LogRow> logged = rowsOf(1);
        assertThat(logged.get(0).prompt()).isEqualTo(300);
        assertThat(logged.get(0).completion()).isEqualTo(40);
        assertThat(logged.get(0).cacheCreation()).isEqualTo(100);
        assertThat(logged.get(0).cacheRead()).isEqualTo(500);
        assertThat(count("llm.cache.accounted.calls")).isEqualTo(1.0);
        assertThat(count("llm.input.tokens.cache.read")).isEqualTo(500.0);
        assertThat(count("llm.input.tokens.cache.write")).isEqualTo(100.0);
        assertThat(count("llm.input.tokens.uncached")).isEqualTo(300.0);
    }

    /**
     * P3-DECISION: today a tool turn loses the provider-native usage (UsageCalculator builds a plain
     * DefaultUsage), so cache counters are ABSENT - null on the row and no cache meter series at all -
     * while prompt/completion stay cumulative. If Spring AI 2 accumulates native usage, cacheRead
     * becomes non-null here; that is an improvement, but the cost estimate then changes, so decide.
     */
    @Test
    void toolTurnDropsAnthropicCacheCountersButKeepsCumulativeTokens() throws Exception {
        ScriptedChatModel model = model(
            toolCalls(anthropicUsage(300, 10, 100, 0), call("c1", "lookup", "{}")),
            text(anthropicUsage(50, 30, 0, 400), "final answer"));

        runNonStreaming(rig(model), true);

        List<LogRow> logged = rowsOf(1);
        settle();
        assertThat(rows).hasSize(1);
        assertThat(logged.get(0).prompt()).isEqualTo(350);
        assertThat(logged.get(0).completion()).isEqualTo(40);
        assertThat(logged.get(0).cacheCreation()).as("absent, not zero").isNull();
        assertThat(logged.get(0).cacheRead()).as("absent, not zero").isNull();
        assertThat(meters.find("llm.cache.accounted.calls").counters()).as("no series for unmeasured cache").isEmpty();
    }

    // -------------------------------------------------------- cancellation

    @Test
    void aCancelledStreamStillWritesExactlyOneRowAndReleasesTheReservationOnce() throws Exception {
        ScriptedChatModel model = model(text(usage(100, 20), "one ", "two ", "three"));

        rig(model).prompt().user("hello").stream().content().take(1).collectList().block(Duration.ofSeconds(10));

        rowsOf(1);
        settle();
        assertThat(rows).as("cancel writes one llm_call_log row").hasSize(1);
        assertThat(rows.get(0).success()).as("a cancelled stream is recorded as not successful").isFalse();
        assertThat(freeTier).as("cancel releases the token reservation exactly once").hasSize(1);
        assertThat(freeTier.get(0).reserved()).isEqualTo(FREE_TIER_RESERVED);
        assertThat(budget).hasSize(1);
        assertThat(budget.get(0).reserved()).isEqualTo(BUDGET_RESERVED_MICRO_CENTS);
    }
}
