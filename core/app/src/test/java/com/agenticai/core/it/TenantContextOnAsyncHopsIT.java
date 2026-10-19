package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.LlmCallMetricsService;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.eventbus.webhook.WebhookSubscription;
import com.agenticai.core.eventbus.webhook.WebhookSubscriptionRepository;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Work that hops onto a thread with no {@code TenantContext} must still land in the right tenant's
 * schema, on a real Postgres: the explicit-tenant {@code agent.message.completed} publish (tenant
 * webhooks) and the sampled LLM judge's {@code llm_call_log} row. Two tenants, so a leak into the
 * other schema is visible as well as a lost write.
 */
@Tag("integration")
@TestPropertySource(properties = "app.llm.judge.sample-rate=1.0")
class TenantContextOnAsyncHopsIT extends AbstractContextIT {

    private static final String A = "tenant_it_async_hop_a";
    private static final String B = "tenant_it_async_hop_b";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private WorkflowEventPublisher publisher;
    @Autowired private WebhookSubscriptionRepository subscriptions;
    @Autowired private LlmCallLogService callLog;
    @Autowired private LlmQualityJudgeService judge;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void tenantSchemas() {
        boolean created = false;
        for (String schema : List.of(A, B)) {
            if (tenants.findBySchemaName(schema).isEmpty()) {
                Tenant tenant =
                        new Tenant(
                                schema,
                                TenantPlan.PREMIUM,
                                schema,
                                "agentic-ai",
                                AccountType.B2B,
                                ExperienceMode.SIMPLE);
                tenant.markActive();
                tenants.save(tenant);
                created = true;
            }
        }
        if (created) {
            assertThat(migrations.migrateAllActiveTenants()).as("schemas that failed").isEmpty();
        }
        for (String schema : List.of(A, B)) {
            jdbc.execute(
                    "TRUNCATE "
                            + schema
                            + ".webhook_delivery, "
                            + schema
                            + ".webhook_subscription CASCADE");
            jdbc.execute("TRUNCATE " + schema + ".llm_call_log");
            TenantScope.of(TenantContext.of(schema, "it-seed", Set.of()))
                    .run(
                            () ->
                                    subscriptions.save(
                                            new WebhookSubscription(
                                                    "hook-" + schema,
                                                    "https://hooks.example.com/" + schema,
                                                    "not-a-real-secret",
                                                    Set.of("agent.message.completed"),
                                                    "it")));
        }
        TenantContext.clear();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private long count(String schema, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + "." + table, Long.class);
    }

    @Test
    void explicitTenantPublishFromAContextlessThreadRecordsTheDeliveryInThatTenantOnly()
            throws Exception {
        CompletableFuture.runAsync(
                        () -> {
                            assertThat(TenantContext.current()).isEmpty();
                            publisher.publish(
                                    WorkflowEventTypes.AGENT_MESSAGE_COMPLETED,
                                    "session-1",
                                    Map.of("answerExcerpt", "hello"),
                                    A);
                            assertThat(TenantContext.current()).isEmpty();
                        })
                .get();

        assertThat(count(A, "webhook_delivery")).as("deliveries in A").isEqualTo(1);
        assertThat(count(B, "webhook_delivery")).as("deliveries in B").isZero();
    }

    @Test
    void llmCallLogWrittenUnderTheReappliedTenantLandsInThatSchemaOnly() throws Exception {
        Optional<TenantContext> captured = Optional.of(TenantContext.of(A, "it-user", Set.of()));

        CompletableFuture.runAsync(
                        () -> {
                            assertThat(TenantContext.current()).isEmpty();
                            TenantScope.of(captured)
                                    .run(
                                            () ->
                                                    callLog.record(
                                                            null,
                                                            "s-" + UUID.randomUUID(),
                                                            ProviderName.OPENAI,
                                                            "gpt-4o-mini",
                                                            10L,
                                                            5L,
                                                            15L,
                                                            true,
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            10L,
                                                            java.time.Instant.now(),
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            null));
                            assertThat(TenantContext.current()).isEmpty();
                        })
                .get();

        assertThat(count(A, "llm_call_log")).isEqualTo(1);
        assertThat(count(B, "llm_call_log")).isZero();
    }

    @Test
    void sampledJudgeStartedUnderTheReappliedTenantWritesItsCallLogRowToThatSchemaOnly()
            throws Exception {
        ChatModel model =
                prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("OK"))));
        ChatClient client =
                ChatClient.builder(model)
                        .defaultAdvisors(
                                new LlmMetricsAdvisor(
                                        new LlmCallMetricsService(),
                                        callLog,
                                        ProviderName.OPENAI,
                                        "gpt-4o-mini",
                                        new SimpleMeterRegistry(),
                                        new LlmPricingCatalog()))
                        .build();
        Optional<TenantContext> captured = Optional.of(TenantContext.of(A, "it-user", Set.of()));

        CompletableFuture.runAsync(
                        () ->
                                TenantScope.of(captured)
                                        .run(
                                                () ->
                                                        judge.sampleAsync(
                                                                client,
                                                                UUID.randomUUID(),
                                                                "judge-session",
                                                                "question",
                                                                "answer")))
                .get();

        long deadline = System.currentTimeMillis() + 15_000;
        while (count(A, "llm_call_log") == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(count(A, "llm_call_log")).as("judge call logged in A").isEqualTo(1);
        assertThat(count(B, "llm_call_log")).as("judge call logged in B").isZero();
    }
}
