package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.budget.TurnReservationGuards;
import com.agenticai.core.security.RequestCorrelationFilter;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TurnContextTest {

    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @AfterEach
    void clear() {
        TenantContext.clear();
        MDC.remove(RequestCorrelationFilter.MDC_KEY);
    }

    @Test
    void capturesTheCallingThreadsTenantInstance() {
        TenantContext tenant = TenantContext.of("tenant_a", "user-1", Set.of("RUNNER"));
        TenantContext.set(tenant);

        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);

        assertThat(ctx.tenant()).isSameAs(tenant);
        assertThat(ctx.tenantId()).isEqualTo("tenant_a");
        assertThat(ctx.subject()).isEqualTo("user-1");
        assertThat(ctx.requestedAgentId()).isEqualTo(AGENT);
        assertThat(ctx.sessionId()).isEqualTo("s1");
        assertThat(ctx.requestId()).isEqualTo("req-1");
        assertThat(ctx.startedAt()).isEqualTo(T0);
        assertThat(ctx.deadline()).isNull();
        assertThat(ctx.budget()).isNull();
    }

    @Test
    void tenantLessTurnHasNullIdsAndAPassThroughScope() {
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);

        assertThat(ctx.tenant()).isNull();
        assertThat(ctx.tenantId()).isNull();
        assertThat(ctx.subject()).isNull();
        assertThat(ctx.tenantContext()).isEmpty();
        assertThat(ctx.scope().isEmpty()).isTrue();
    }

    @Test
    void capturedValueDoesNotFollowLaterThreadLocalChanges() {
        TenantContext.set(TenantContext.of("tenant_a", "user-1", Set.of()));
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);

        TenantContext.set(TenantContext.of("tenant_b", "user-2", Set.of()));

        assertThat(ctx.tenantId()).isEqualTo("tenant_a");
    }

    @Test
    void scopeReappliesTheTenantOnAnotherThreadAndRestores() throws Exception {
        TenantContext.set(TenantContext.of("tenant_a", "user-1", Set.of()));
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);
        TenantContext.clear();

        AtomicReference<String> inside = new AtomicReference<>();
        AtomicReference<Boolean> afterwardsEmpty = new AtomicReference<>();
        CompletableFuture.runAsync(
                        () -> {
                            ctx.scope()
                                    .run(
                                            () ->
                                                    inside.set(
                                                            TenantContext.current()
                                                                    .map(TenantContext::getTenantId)
                                                                    .orElse(null)));
                            afterwardsEmpty.set(TenantContext.current().isEmpty());
                        })
                .get();

        assertThat(inside.get()).isEqualTo("tenant_a");
        assertThat(afterwardsEmpty.get()).isTrue();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void blankOrMissingRequestIdIsReplacedWithAFreshOne() {
        TurnContext missing = TurnContext.fromCurrentThread(AGENT, "s1", null, T0, null);
        TurnContext blank = TurnContext.fromCurrentThread(AGENT, "s1", "  ", T0, null);

        assertThat(missing.requestId()).isNotBlank();
        assertThat(blank.requestId()).isNotBlank().isNotEqualTo(missing.requestId());
    }

    @Test
    void atEntryReadsTheCorrelationIdFromMdcAndTheRequestFields() {
        TenantContext.set(TenantContext.of("tenant_a", "user-1", Set.of()));
        MDC.put(RequestCorrelationFilter.MDC_KEY, "req-from-filter");
        ChatTurnRequest request = ChatTurnRequest.builder(AGENT, "s9", "subject", "hi").build();

        TurnContext ctx = TurnContext.atEntry(request);

        assertThat(ctx.requestId()).isEqualTo("req-from-filter");
        assertThat(ctx.requestedAgentId()).isEqualTo(AGENT);
        assertThat(ctx.sessionId()).isEqualTo("s9");
        assertThat(ctx.tenantId()).isEqualTo("tenant_a");
        assertThat(ctx.deadline()).isNull();
    }

    @Test
    void deadlineArithmetic() {
        TurnContext ctx =
                TurnContext.fromCurrentThread(AGENT, "s1", "r", T0, Duration.ofSeconds(30));

        assertThat(ctx.deadline()).isEqualTo(T0.plusSeconds(30));
        assertThat(ctx.remaining(T0.plusSeconds(10))).contains(Duration.ofSeconds(20));
        assertThat(ctx.remaining(T0.plusSeconds(45))).contains(Duration.ZERO);
        assertThat(ctx.isExpired(T0.plusSeconds(29))).isFalse();
        assertThat(ctx.isExpired(T0.plusSeconds(30))).isTrue();

        TurnContext open = TurnContext.fromCurrentThread(AGENT, "s1", "r", T0, null);
        assertThat(open.remaining(T0.plusSeconds(1_000_000))).isEmpty();
        assertThat(open.isExpired(T0.plusSeconds(1_000_000))).isFalse();
    }

    @Test
    void rejectsADeadlineBeforeTheStartAndMissingRequiredFields() {
        assertThatThrownBy(() -> new TurnContext(null, AGENT, "s", "r", T0, T0.minusMillis(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnContext(null, AGENT, "s", null, T0, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TurnContext(null, AGENT, "s", "r", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void withBudgetReturnsACopyAndLeavesTheOriginalUntouched() {
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "r", T0, null);
        TurnReservationGuards guards = new TurnReservationGuards();

        TurnContext withBudget = ctx.withBudget(guards);

        assertThat(withBudget.budget()).isSameAs(guards);
        assertThat(ctx.budget()).isNull();
        assertThat(withBudget.requestId()).isEqualTo(ctx.requestId());
        assertThat(withBudget.startedAt()).isEqualTo(ctx.startedAt());
    }
}
