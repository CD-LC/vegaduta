package com.agenticai.core.redteam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.agents.budget.PerUserBudgetTrackingAdvisor;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Family 6 - budget accounting against a real {@code redis-server} (skipped when the binary is
 * absent). Two ways to spend for free: a refund that lands after the window expired (the counter
 * goes negative, which is credit nobody paid for) and a response that was produced but never
 * measured (usage metadata missing or zero).
 */
@Tag("redteam")
class BudgetAccountingRedTeamTest {

    private static LocalRedis redis;

    private static final String TENANT = "t-redteam";
    private static final String USER = "u-1";

    @BeforeAll
    static void startRedis() throws Exception {
        assumeTrue(LocalRedis.binaryAvailable(), "redis-server is not installed");
        redis = LocalRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void flush() {
        redis.template.execute(
                (org.springframework.data.redis.core.RedisCallback<Object>)
                        c -> {
                            c.serverCommands().flushAll();
                            return null;
                        });
    }

    private static PlatformSettingsService defaultsOnly() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        return settings;
    }

    @SuppressWarnings("unchecked")
    private PerUserBudgetService perUser() {
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan(anyString())).thenReturn(TenantPlan.FREE);
        return new PerUserBudgetService(
                redis.template,
                defaultsOnly(),
                mock(LlmPricingCatalog.class),
                0L,
                limits,
                2,
                3,
                mock(ObjectProvider.class));
    }

    private Set<String> keys(String prefix) {
        Set<String> k = redis.template.keys(prefix + "*");
        return k == null ? Set.of() : k;
    }

    private void expireWindow(String prefix) {
        redis.template.delete(keys(prefix));
    }

    private void assertNoNegativeCounters(String prefix) {
        for (String key : keys(prefix)) {
            String v = redis.template.opsForValue().get(key);
            if (v != null && v.matches("-?\\d+")) {
                assertTrue(Long.parseLong(v) >= 0, key + " went negative: " + v);
            }
        }
    }

    // ---- refund underflow
    // --------------------------------------------------------------------------------------------

    @Test
    void perUserRefundAfterTheWindowExpiredDoesNotCreateNegativeCredit() {
        PerUserBudgetService budget = perUser();
        long reserved =
                budget.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 4_000, TenantPlan.FREE);
        assertTrue(reserved > 0, "control: something was reserved");
        expireWindow("agentic:per-user-budget:");

        budget.releaseSpendReservation(TENANT, USER, Modality.TEXT, reserved, TenantPlan.FREE);

        assertNoNegativeCounters("agentic:per-user-budget:");
    }

    @Test
    void perUserRefundLargerThanWhatIsLeftClampsAtZero() {
        PerUserBudgetService budget = perUser();
        long reserved =
                budget.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 4_000, TenantPlan.FREE);
        // Release twice: the second refund exceeds what the counter holds.
        budget.releaseSpendReservation(TENANT, USER, Modality.TEXT, reserved, TenantPlan.FREE);
        budget.releaseSpendReservation(TENANT, USER, Modality.TEXT, reserved, TenantPlan.FREE);

        assertNoNegativeCounters("agentic:per-user-budget:");
    }

    @Test
    void platformFeatureRefundAfterTheWindowExpiredDoesNotCreateNegativeCredit() {
        PlatformFeatureBudgetService budget =
                new PlatformFeatureBudgetService(redis.template, defaultsOnly(), 0L);
        PlatformFeatureBudgetService.Reservation r =
                budget.reserve("summaries", new BigDecimal("0.05"));
        assertTrue(r.allowed(), "control: " + r.reason());
        expireWindow("agentic:smart-ai-budget:");

        budget.release(r);

        assertNoNegativeCounters("agentic:smart-ai-budget:");
    }

    @Test
    void platformFeatureSettleBelowZeroClampsAtZero() {
        PlatformFeatureBudgetService budget =
                new PlatformFeatureBudgetService(redis.template, defaultsOnly(), 0L);
        PlatformFeatureBudgetService.Reservation a =
                budget.reserve("summaries", new BigDecimal("0.05"));
        PlatformFeatureBudgetService.Reservation b =
                budget.reserve("summaries", new BigDecimal("0.05"));
        expireWindow("agentic:smart-ai-budget:");
        budget.release(a);
        budget.release(b);

        assertNoNegativeCounters("agentic:smart-ai-budget:");
    }

    // ---- unmeasured streams
    // ------------------------------------------------------------------------------------------

    private long spentMicroCents(String prefix) {
        long total = 0;
        for (String key : keys(prefix)) {
            if (key.contains("credit") || key.endsWith(":notified")) {
                continue;
            }
            String v = redis.template.opsForValue().get(key);
            if (v != null && v.matches("-?\\d+")) {
                total += Long.parseLong(v);
            }
        }
        return total;
    }

    private void streamThrough(PerUserBudgetService budget, long reserved, ChatResponse... chunks) {
        PerUserBudgetTrackingAdvisor advisor =
                new PerUserBudgetTrackingAdvisor(
                        budget, TENANT, USER, Modality.TEXT, "hosted-x", reserved, TenantPlan.FREE);
        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        List<ChatClientResponse> responses =
                java.util.Arrays.stream(chunks)
                        .map(
                                c ->
                                        ChatClientResponse.builder()
                                                .chatResponse(c)
                                                .context(Map.of())
                                                .build())
                        .toList();
        when(chain.nextStream(any())).thenReturn(Flux.fromIterable(responses));
        advisor.adviseStream(request, chain).blockLast();
    }

    private static ChatResponse text(String s, DefaultUsage usage) {
        ChatResponse.Builder b =
                ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(s))));
        if (usage != null) {
            b.metadata(ChatResponseMetadata.builder().usage(usage).build());
        }
        return b.build();
    }

    @Test
    void aStreamedAnswerWithNoUsageMetadataIsStillCharged() {
        PerUserBudgetService budget = perUser();
        long reserved =
                budget.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 4_000, TenantPlan.FREE);

        streamThrough(
                budget, reserved, text("x".repeat(2_000), null), text("y".repeat(2_000), null));

        assertTrue(
                spentMicroCents("agentic:per-user-budget:") >= reserved,
                "an answer was delivered for free: spent "
                        + spentMicroCents("agentic:per-user-budget:")
                        + " < reserved "
                        + reserved);
    }

    @Test
    void aStreamedAnswerReportingZeroTokensIsStillCharged() {
        PerUserBudgetService budget = perUser();
        long reserved =
                budget.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 4_000, TenantPlan.FREE);

        streamThrough(budget, reserved, text("z".repeat(4_000), new DefaultUsage(0, 0)));

        assertTrue(spentMicroCents("agentic:per-user-budget:") >= reserved);
    }

    @Test
    void anAnswerWithNoTextAndNoUsageReleasesTheReservation() {
        PerUserBudgetService budget = perUser();
        long reserved =
                budget.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 4_000, TenantPlan.FREE);

        streamThrough(budget, reserved, text("", null));

        assertEquals(
                0L,
                spentMicroCents("agentic:per-user-budget:"),
                "control: nothing was produced, nothing is owed");
    }

    @Test
    void unmeasuredAnswersCannotBeRepeatedUntilTheCapIsHit() {
        PerUserBudgetService budget = perUser();
        int served = 0;
        try {
            for (int i = 0; i < 2000; i++) {
                long reserved =
                        budget.checkAndReserveBudget(
                                TENANT, USER, Modality.TEXT, 4_000, TenantPlan.FREE);
                streamThrough(budget, reserved, text("x".repeat(4_000), null));
                served++;
            }
        } catch (com.agenticai.core.security.TenantLimitExceededException capReached) {
            // expected: the free-tier cap must stop an endless run of unmeasured answers
        }
        assertTrue(
                served < 2000,
                "2000 unmeasured answers all got through; the cap never engaged; spent="
                        + spentMicroCents("agentic:per-user-budget:"));
    }
}
