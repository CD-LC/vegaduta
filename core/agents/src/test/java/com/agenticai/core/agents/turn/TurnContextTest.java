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

    @Test
    void approvalStateTravelsWithTheContextAndSurvivesWithBudget() {
        com.agenticai.core.guardrails.TurnApprovalState state =
                new com.agenticai.core.guardrails.TurnApprovalState("c1:scoped");
        TurnContext base = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);
        assertThat(base.approval()).isNull();

        TurnContext withState = base.withApproval(state);
        TurnContext withBoth = withState.withBudget(null);

        assertThat(withState.approval()).isSameAs(state);
        assertThat(withBoth.approval()).as("a later copy keeps the same instance").isSameAs(state);
        assertThat(withState.requestId()).isEqualTo(base.requestId());
        assertThat(base.approval()).as("the original is untouched").isNull();
    }

    @Test
    void theTurnIdIsMadeOnceNeverTakenFromTheCallerAndKeptByEveryCopy() {
        TurnContext a = TurnContext.fromCurrentThread(AGENT, "s1", "same-request-id", T0, null);
        TurnContext b = TurnContext.fromCurrentThread(AGENT, "s1", "same-request-id", T0, null);

        assertThat(a.turnId()).isNotNull();
        assertThat(a.turnId()).as("two turns never share an id, even with the same request id").isNotEqualTo(b.turnId());
        assertThat(a.withBudget(new TurnReservationGuards()).turnId()).isEqualTo(a.turnId());
        assertThat(a.withApproval(new com.agenticai.core.guardrails.TurnApprovalState("c1:x")).turnId())
                .isEqualTo(a.turnId());
        assertThat(a.withDeadline(T0.plusSeconds(5)).turnId()).isEqualTo(a.turnId());
        assertThat(new TurnContext(null, AGENT, "s", "r", T0, null, null).turnId()).isNotNull();
    }

    // ---------------------------------------------------------- the continuation after an approval

    private static ApprovalContinuation continuation(String goal) {
        return new ApprovalContinuation(
                UUID.randomUUID(),
                ApprovalContinuation.Outcome.COMPLETED,
                "send_invoice",
                "{}",
                "sent",
                "admin-1",
                goal,
                AGENT,
                "s1",
                "user-1",
                "tenant_a",
                "user-1",
                "c1:x",
                java.util.List.of("send_invoice"),
                java.util.List.of());
    }

    @Test
    void anOrdinaryTurnDecidesOnTheUsersMessage() {
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);

        assertThat(ctx.continuation()).isNull();
        assertThat(ctx.decisionMessage("hello")).isEqualTo("hello");
        assertThat(ctx.withContinuation(null)).isSameAs(ctx);
    }

    @Test
    void aContinuationTurnDecidesOnTheOriginalGoalNotOnThePlatformWrittenMessage() {
        TurnContext ctx =
                TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null)
                        .withContinuation(continuation("Send invoice INV-7731."));

        assertThat(ctx.decisionMessage("<approval_result ...>tool output</approval_result>"))
                .isEqualTo("Send invoice INV-7731.");
    }

    @Test
    void aContinuationWithoutAGoalFallsBackToTheMessage() {
        TurnContext ctx =
                TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null).withContinuation(continuation("  "));

        assertThat(ctx.decisionMessage("the message")).isEqualTo("the message");
    }

    @Test
    void theEntryContextCarriesTheRequestsContinuation() {
        ApprovalContinuation c = continuation("Send invoice.");
        ChatTurnRequest request =
                ChatTurnRequest.builder(AGENT, "s1", "user-1", "<approval_result>...").continuation(c).build();

        TurnContext ctx = TurnContext.forEntry(TenantContext.of("tenant_a", "user-1", Set.of()), request);

        assertThat(ctx.continuation()).isSameAs(c);
        assertThat(ChatTurnRequest.builder(AGENT, "s1", "user-1", "hi").build().continuation()).isNull();
    }

    @Test
    void otherCopiesKeepTheContinuation() {
        ApprovalContinuation c = continuation("Send invoice.");
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null).withContinuation(c);

        assertThat(ctx.withBudget(null).continuation()).isSameAs(c);
        assertThat(ctx.withApproval(null).continuation()).isSameAs(c);
    }

    @Test
    void everyContextHasAReplyOutcomeThatEveryCopySharesByReference() {
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);

        assertThat(ctx.reply()).isNotNull();
        assertThat(ctx.reply().isComplete()).isTrue();
        assertThat(ctx.withBudget(null).reply()).isSameAs(ctx.reply());
        assertThat(ctx.withApproval(null).reply()).isSameAs(ctx.reply());
        assertThat(ctx.withContinuation(continuation("Send invoice.")).reply()).isSameAs(ctx.reply());
        // The stage that produces the reply marks it; the stage that caches it sees the mark through
        // a different copy of the context.
        ctx.withBudget(null).reply().mark(TurnReplyOutcome.Kind.TRUNCATED);
        assertThat(ctx.reply().isComplete()).isFalse();
        assertThat(ctx.reply().kind()).isEqualTo(TurnReplyOutcome.Kind.TRUNCATED);
    }

    @Test
    void aNewReplyAttemptForgetsHowTheLastOneEndedAndASnapshotCanBePutBack() {
        TurnReplyOutcome reply = new TurnReplyOutcome();
        reply.mark(TurnReplyOutcome.Kind.REFUSED, "SAFETY");
        assertThat(reply.detail()).isEqualTo("SAFETY");
        TurnReplyOutcome.State refused = reply.snapshot();

        reply.begin();
        assertThat(reply.isComplete()).isTrue();
        assertThat(reply.detail()).isNull();

        reply.restore(refused);
        assertThat(reply.kind()).isEqualTo(TurnReplyOutcome.Kind.REFUSED);
        assertThat(reply.detail()).isEqualTo("SAFETY");
    }

    @Test
    void theToolActivityIsOneInstanceSharedByEveryCopyOfTheContext() {
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);

        assertThat(ctx.toolActivity()).isNotNull();
        assertThat(ctx.withBudget(null).toolActivity()).isSameAs(ctx.toolActivity());
        assertThat(ctx.withApproval(null).toolActivity()).isSameAs(ctx.toolActivity());
        assertThat(ctx.withContinuation(continuation("Send invoice.")).toolActivity()).isSameAs(ctx.toolActivity());
        assertThat(ctx.withDeadline(T0.plusSeconds(5)).toolActivity()).isSameAs(ctx.toolActivity());
        ctx.withBudget(null).toolActivity().begin("send_email");
        assertThat(ctx.toolActivity().inFlightCount()).isEqualTo(1);
    }

    @Test
    void anEnclosingDeadlineCanBeSetAndIsNeverBeforeTheStart() {
        TurnContext ctx = TurnContext.fromCurrentThread(AGENT, "s1", "req-1", T0, null);

        assertThat(ctx.withDeadline(null)).isSameAs(ctx);
        assertThat(ctx.withDeadline(T0.plusSeconds(600)).deadline()).isEqualTo(T0.plusSeconds(600));
        assertThat(ctx.withDeadline(T0.plusSeconds(600)).remaining(T0.plusSeconds(100)))
                .contains(Duration.ofSeconds(500));
        assertThat(ctx.withDeadline(T0.minusMillis(3)).deadline())
                .as("a limit computed from a clock a moment ahead is clamped, not rejected")
                .isEqualTo(T0);
    }
}
