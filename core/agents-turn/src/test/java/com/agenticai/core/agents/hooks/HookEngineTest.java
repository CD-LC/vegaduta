package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.ToolDispatchGate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every action at every point it is allowed, the latency budget, and both failure modes. The
 * network is a fake transport; time is a fake clock; the executor for informational points runs
 * inline.
 */
class HookEngineTest extends HookEngineTestBase {

    // --------------------------------------------------------------- dispatch gate

    @Test
    void aBlockHookDeniesAndAnApprovalHookRequiresApproval() {
        HookEngine engine = engine();
        EffectiveHook block =
                hook(
                        "no-delete",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.BLOCK,
                        Map.of("reason", "Deletes are not allowed"));
        EffectiveHook approval =
                hook("ask", HookPoint.BEFORE_TOOL, HookActionType.REQUIRE_APPROVAL, Map.of());

        ToolDispatchGate.Verdict denied = engine.gateVerdict(List.of(block));
        assertEquals(ToolDispatchGate.Outcome.DENY, denied.outcome());
        assertTrue(denied.reason().contains("agent:no-delete"), denied.reason());
        assertTrue(denied.reason().contains("Deletes are not allowed"), denied.reason());
        assertTrue(
                denied.reason().endsWith("."),
                "GuardedToolCallback appends its own sentence to this reason");

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                engine.gateVerdict(List.of(approval)).outcome());
        assertEquals(
                ToolDispatchGate.Outcome.DENY,
                engine.gateVerdict(List.of(approval, block)).outcome(),
                "deny outranks approval");
        assertEquals(ToolDispatchGate.Outcome.ALLOW, engine.gateVerdict(List.of()).outcome());
    }

    @Test
    void aDisabledHookNeverDecidesAnything() {
        HookDefinition off =
                new HookDefinition(
                        "off",
                        false,
                        HookPoint.BEFORE_TOOL,
                        HookMatch.ANY,
                        HookActionType.BLOCK,
                        Map.of(),
                        FailMode.CLOSED,
                        2_000);

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                engine().gateVerdict(List.of(new EffectiveHook("agent", off))).outcome());
    }

    // ------------------------------------------------------------------ before tool

    @Test
    void redactRewritesToolArgumentsAndRecordsTheDecision() {
        HookEngine engine = engine();
        EffectiveHook redact =
                hook(
                        "scrub",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.REDACT,
                        Map.of("patterns", List.of("email")));

        HookEngine.ToolPre pre =
                engine.beforeTool(
                        ctx,
                        List.of(redact),
                        "send_email",
                        "{\"to\":\"jo@example.com\",\"note\":\"hi\"}",
                        new HookRateLimiter.RunCounters());

        assertFalse(pre.denied());
        assertFalse(pre.args().contains("jo@example.com"));
        assertTrue(pre.args().contains("[REDACTED_EMAIL]"));
        assertTrue(HookEngine.jsonStillValid("{\"a\":1}", pre.args()), "still a JSON document");
        verify(audit)
                .record(
                        eq(ctx.actor()),
                        eq("agent.hook.decision"),
                        eq("agent"),
                        eq(AGENT.toString()),
                        eq(AuditOutcome.SUCCESS),
                        contains("decision=redacted"));
        assertTrue(sawEvent("redacted"));
        assertEquals(1.0, decisions("redacted"));
    }

    @Test
    void redactNeverRunsOnACallThatNeedsNoRedaction() {
        HookEngine engine = engine();
        EffectiveHook redact =
                hook(
                        "scrub",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.REDACT,
                        Map.of("patterns", List.of("email")));

        HookEngine.ToolPre pre =
                engine.beforeTool(ctx, List.of(redact), "t", "{\"q\":\"weather\"}", null);

        assertEquals("{\"q\":\"weather\"}", pre.args());
        verify(audit, never())
                .record(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        anyString());
    }

    @Test
    void aRedactionThatWouldBreakTheArgumentsJsonIsRefusedNotPassedThrough() {
        HookEngine engine = engine();
        EffectiveHook redact =
                hook(
                        "breaker",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.REDACT,
                        Map.of("regex", List.of("\"to\"")));

        HookEngine.ToolPre pre =
                engine.beforeTool(ctx, List.of(redact), "send_email", "{\"to\":\"x\"}", null);

        assertTrue(pre.denied());
        assertTrue(pre.denial().contains("could not be redacted safely"), pre.denial());
        assertTrue(sawEvent("error"));
    }

    @Test
    void aRedactionThatRunsOutOfTimeFailsClosed() {
        HookEngine engine = engine(100);
        EffectiveHook redact =
                hook(
                        "evil",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.REDACT,
                        Map.of("regex", List.of("a*c")));

        HookEngine.ToolPre pre =
                engine.beforeTool(ctx, List.of(redact), "t", "a".repeat(100_000) + "!", null);

        assertTrue(pre.denied(), "an unredacted pass-through would defeat the hook");
    }

    @Test
    void limitPerRunRefusesTheCallPastTheCap() {
        HookEngine engine = engine();
        EffectiveHook limit =
                hook(
                        "cap",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.LIMIT,
                        Map.of("maxPerRun", 2, "message", "Search budget used up"));
        HookRateLimiter.RunCounters run = new HookRateLimiter.RunCounters();

        assertFalse(engine.beforeTool(ctx, List.of(limit), "search", "{}", run).denied());
        assertFalse(engine.beforeTool(ctx, List.of(limit), "search", "{}", run).denied());
        HookEngine.ToolPre third = engine.beforeTool(ctx, List.of(limit), "search", "{}", run);

        assertTrue(third.denied());
        assertTrue(third.denial().contains("Search budget used up"), third.denial());
        assertTrue(
                third.denial().startsWith("This tool call ('search') was not executed"),
                third.denial());
        assertTrue(sawEvent("limited"));
        assertFalse(
                engine.beforeTool(
                                ctx,
                                List.of(limit),
                                "search",
                                "{}",
                                new HookRateLimiter.RunCounters())
                        .denied(),
                "a new run starts from zero");
    }

    @Test
    void limitPerMinuteIsASlidingWindow() {
        HookEngine engine = engine();
        EffectiveHook limit =
                hook(
                        "rate",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.LIMIT,
                        Map.of("maxPerMinute", 2));

        assertFalse(engine.beforeTool(ctx, List.of(limit), "t", "{}", null).denied());
        assertFalse(engine.beforeTool(ctx, List.of(limit), "t", "{}", null).denied());
        assertTrue(engine.beforeTool(ctx, List.of(limit), "t", "{}", null).denied());

        clock.addAndGet(HookRateLimiter.MINUTE_MILLIS + 1);
        assertFalse(
                engine.beforeTool(ctx, List.of(limit), "t", "{}", null).denied(),
                "the window slid");
    }

    @Test
    void aWebhookAllowChangesNothing() {
        HookEngine engine = engine();

        HookEngine.ToolPre pre =
                engine.beforeTool(
                        ctx,
                        List.of(webhook("vet", HookPoint.BEFORE_TOOL, FailMode.CLOSED, false)),
                        "t",
                        "{\"q\":1}",
                        null);

        assertFalse(pre.denied());
        assertEquals("{\"q\":1}", pre.args());
        assertEquals(1, payloads.size());
        assertFalse(
                payloads.get(0).contains("\"data\""),
                "arguments are only sent when the hook asks for them");
        assertTrue(payloads.get(0).contains("\"tool\":\"t\""));
        assertTrue(payloads.get(0).contains("\"point\":\"BEFORE_TOOL\""));
    }

    @Test
    void aWebhookThatAsksForDataGetsItRedactedAndCapped() throws Exception {
        HookEngine engine = engine();
        String args = "{\"password\":\"hunter2hunter2\",\"q\":\"" + "x".repeat(5_000) + "\"}";

        engine.beforeTool(
                ctx,
                List.of(webhook("vet", HookPoint.BEFORE_TOOL, FailMode.CLOSED, true)),
                "t",
                args,
                null);

        String data = MAPPER.readTree(payloads.get(0)).get("data").asText();
        assertFalse(
                data.contains("hunter2hunter2"),
                "credential-shaped fields never leave the platform");
        assertTrue(data.length() <= 2_000);
    }

    @Test
    void aWebhookDenyRefusesTheCallWithItsReason() {
        responder =
                body ->
                        new HookWebhookClient.Response(
                                200, "{\"decision\":\"deny\",\"reason\":\"vendor on hold\"}");

        HookEngine.ToolPre pre =
                engine().beforeTool(
                                ctx,
                                List.of(
                                        webhook(
                                                "vet",
                                                HookPoint.BEFORE_TOOL,
                                                FailMode.OPEN,
                                                false)),
                                "pay_vendor",
                                "{}",
                                null);

        assertTrue(pre.denied());
        assertTrue(pre.denial().contains("vendor on hold"), pre.denial());
        assertTrue(sawEvent("deny"));
    }

    @Test
    void aWebhookTimeoutFailsClosedByDefaultAtBeforeTool() {
        throwOnWebhook("the webhook timed out");

        HookEngine.ToolPre pre =
                engine().beforeTool(
                                ctx,
                                List.of(
                                        webhook(
                                                "vet",
                                                HookPoint.BEFORE_TOOL,
                                                FailMode.CLOSED,
                                                false)),
                                "t",
                                "{}",
                                null);

        assertTrue(pre.denied());
        assertTrue(pre.denial().contains("could not complete"), pre.denial());
        assertTrue(sawEvent("error"));
        verify(audit)
                .record(
                        anyString(),
                        eq("agent.hook.decision"),
                        eq("agent"),
                        anyString(),
                        eq(AuditOutcome.FAILURE),
                        contains("timed out"));
    }

    @Test
    void aWebhookTimeoutFailsOpenWhenTheHookSaysSo() {
        throwOnWebhook("the webhook timed out");

        HookEngine.ToolPre pre =
                engine().beforeTool(
                                ctx,
                                List.of(
                                        webhook(
                                                "vet",
                                                HookPoint.BEFORE_TOOL,
                                                FailMode.OPEN,
                                                false)),
                                "t",
                                "{\"q\":1}",
                                null);

        assertFalse(pre.denied());
        assertEquals("{\"q\":1}", pre.args());
        assertTrue(sawEvent("error"), "an open failure is still recorded");
    }

    @Test
    void aMalformedWebhookReplyIsAFailureNotAnAllow() {
        responder = body -> new HookWebhookClient.Response(200, "<html>ok</html>");

        assertTrue(
                engine().beforeTool(
                                ctx,
                                List.of(
                                        webhook(
                                                "vet",
                                                HookPoint.BEFORE_TOOL,
                                                FailMode.CLOSED,
                                                false)),
                                "t",
                                "{}",
                                null)
                        .denied());
    }

    @Test
    void aWebhookToAHostOffTheAllowListFailsLikeAnyOtherFailure() {
        EffectiveHook offList =
                hook(
                        "vet",
                        HookPoint.BEFORE_TOOL,
                        HookActionType.WEBHOOK,
                        HookMatch.ANY,
                        Map.of("url", "https://evil.example.net/x"),
                        FailMode.CLOSED);

        assertTrue(engine().beforeTool(ctx, List.of(offList), "t", "{}", null).denied());
        assertTrue(payloads.isEmpty(), "nothing was sent");
    }

    @Test
    void hooksShareOneLatencyBudgetSoTheySumToAtMostIt() throws Exception {
        HookEngine engine = engine(100);
        responder =
                body -> {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new HookWebhookClient.Response(200, ALLOW);
                };
        EffectiveHook slowButOpen = webhook("slow", HookPoint.BEFORE_TOOL, FailMode.OPEN, false);
        EffectiveHook next = webhook("next", HookPoint.BEFORE_TOOL, FailMode.CLOSED, false);

        HookEngine.ToolPre pre =
                engine.beforeTool(ctx, List.of(slowButOpen, next), "t", "{}", null);

        assertTrue(
                pre.denied(),
                "the second hook found the budget spent and, being fail-closed, refused");
        assertEquals(1, payloads.size(), "the second webhook was never called");
    }

    @Test
    void theBudgetIsNeverAboveTheHardCap() {
        HookEngine engine =
                new HookEngine(
                        new HookWebhookClient(
                                url -> Optional.empty(),
                                List.of(),
                                "",
                                (u, p, h, t) -> new HookWebhookClient.Response(200, ALLOW)),
                        new HookRateLimiter(),
                        new HookDecisionSink(provider(audit), provider(meters)),
                        60_000,
                        9_000,
                        Runnable::run);

        assertEquals(HookSchema.MAX_TIMEOUT_MS, engine.hardCapMillis());
        assertEquals(HookSchema.MAX_TIMEOUT_MS, engine.budgetMillis());
    }

    @Test
    void aLogHookOnlyRecords() {
        HookEngine.ToolPre pre =
                engine().beforeTool(
                                ctx,
                                List.of(
                                        hook(
                                                "audit",
                                                HookPoint.BEFORE_TOOL,
                                                HookActionType.LOG,
                                                Map.of("label", "tool call"))),
                                "t",
                                "{\"q\":1}",
                                null);

        assertFalse(pre.denied());
        assertEquals("{\"q\":1}", pre.args());
        verify(audit)
                .record(
                        anyString(),
                        eq("agent.hook.decision"),
                        eq("agent"),
                        anyString(),
                        eq(AuditOutcome.SUCCESS),
                        contains("decision=logged"));
        assertTrue(sawEvent("logged"));
    }
}
