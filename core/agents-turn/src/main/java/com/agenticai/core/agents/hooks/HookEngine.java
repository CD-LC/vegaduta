package com.agenticai.core.agents.hooks;

import com.agenticai.core.agents.tools.AgentStepEventRedactor;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates hooks at each point. Holds no per-run state of its own (that lives in the {@link
 * HookCtx} and the per-run counters the caller passes in), so one instance serves every run.
 *
 * <h2>Invariants</h2>
 *
 * <ul>
 *   <li><b>Tighten only.</b> Nothing here can approve, widen a tool list, or turn a refusal into a
 *       success. The only outcomes are "carry on unchanged", "carry on with the arguments / result
 *       / reply redacted or annotated", "refuse", and "send to a human". A webhook's {@code allow}
 *       is a no-op, not an approval: callers run this inside - never instead of - the confirmation
 *       gate.
 *   <li><b>Bounded latency.</b> All synchronous hooks of one point share one budget ({@code
 *       app.agent-hooks.latency-budget-ms}, default 2s, never above the hard cap of 5s); each
 *       webhook's timeout is the smaller of its own, the cap and what is left of the budget.
 *       Informational points ({@code ON_ERROR}, {@code ON_RUN_END}) run off the calling thread
 *       entirely.
 *   <li><b>Explicit failure semantics.</b> A webhook that cannot give a verdict follows its {@link
 *       FailMode} (closed = deny, open = carry on, both recorded). A redaction that cannot be
 *       applied is always closed: the call is refused / the output withheld, never passed through
 *       unredacted. An unexpected exception in a hook is treated like a failed webhook of that
 *       hook's mode at before-points, and withholds at after-points for the actions that protect
 *       (block, redact).
 *   <li><b>Every decision is recorded</b> through {@link HookDecisionSink}: step event, audit
 *       entry, counter.
 * </ul>
 */
final class HookEngine {

    private static final Logger log = LoggerFactory.getLogger(HookEngine.class);

    private static final int MAX_WEBHOOK_DATA_CHARS = 2_000;
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * A before-tool result: the (possibly redacted) arguments to run with, or the text to return
     * instead of running.
     */
    record ToolPre(String args, String denial) {
        boolean denied() {
            return denial != null;
        }

        static ToolPre ok(String args) {
            return new ToolPre(args, null);
        }

        static ToolPre deny(String message) {
            return new ToolPre(null, message);
        }
    }

    /**
     * A before-model result: a refusal to deliver instead of calling the model, and/or a context
     * note to add.
     */
    record ModelPre(String refusal, String note) {}

    private record Verdict(boolean allow, String reason) {
        static final Verdict ALLOW_VERDICT = new Verdict(true, null);
    }

    private record Redacted(String text, int count, boolean failed) {}

    /** Wall-clock budget shared by the hooks of one point. */
    static final class Budget {
        private final long deadlineNanos;

        Budget(long millis) {
            this.deadlineNanos = System.nanoTime() + Math.max(0L, millis) * 1_000_000L;
        }

        long remainingMillis() {
            return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        }
    }

    private final HookWebhookClient webhook;
    private final HookRateLimiter limiter;
    private final HookDecisionSink sink;
    private final long budgetMillis;
    private final long hardCapMillis;
    private final Executor async;

    /** The words this engine writes; the shipped ones until {@link #usePrompts} wires the registry. */
    private volatile HookTexts texts = HookTexts.DEFAULTS;

    /** Wires the prompt registry ({@code message.hook.*}); null restores the shipped wording. */
    void usePrompts(com.agenticai.core.prompts.PromptResolver resolver) {
        this.texts = resolver == null ? HookTexts.DEFAULTS : new HookTexts(resolver);
    }

    HookEngine(
            HookWebhookClient webhook,
            HookRateLimiter limiter,
            HookDecisionSink sink,
            long budgetMillis,
            long hardCapMillis,
            Executor async) {
        this.webhook = webhook;
        this.limiter = limiter;
        this.sink = sink;
        this.hardCapMillis = clamp(hardCapMillis, 100L, HookSchema.MAX_TIMEOUT_MS);
        this.budgetMillis = clamp(budgetMillis, 100L, this.hardCapMillis);
        this.async = async;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    long budgetMillis() {
        return budgetMillis;
    }

    long hardCapMillis() {
        return hardCapMillis;
    }

    // ------------------------------------------------------------- tool: gate

    /**
     * The static (arguments-free) part of {@code BEFORE_TOOL}: {@code block} and {@code
     * require_approval}. Decided at the dispatch gate so a blocked call never reaches the approval
     * inbox. Returns {@link ToolDispatchGate.Verdict#allow()} when none of the given hooks applies.
     */
    ToolDispatchGate.Verdict gateVerdict(List<EffectiveHook> gateHooks) {
        EffectiveHook approval = null;
        for (EffectiveHook eh : gateHooks) {
            HookDefinition h = eh.def();
            if (!h.enabled() || h.point() != HookPoint.BEFORE_TOOL) {
                continue;
            }
            if (h.action() == HookActionType.BLOCK) {
                return ToolDispatchGate.Verdict.deny(
                        gateReason(
                                "a workspace hook (" + eh.key() + ") blocks this tool",
                                h.str("reason")));
            }
            if (h.action() == HookActionType.REQUIRE_APPROVAL && approval == null) {
                approval = eh;
            }
        }
        if (approval != null) {
            return ToolDispatchGate.Verdict.requireApproval(
                    gateReason(
                            "a workspace hook (" + approval.key() + ") requires approval",
                            approval.def().str("reason")));
        }
        return ToolDispatchGate.Verdict.allow();
    }

    /** The hook that produced a non-allow {@link #gateVerdict}, for the decision record. */
    EffectiveHook decidingGateHook(
            List<EffectiveHook> gateHooks, ToolDispatchGate.Outcome outcome) {
        HookActionType wanted =
                outcome == ToolDispatchGate.Outcome.DENY
                        ? HookActionType.BLOCK
                        : HookActionType.REQUIRE_APPROVAL;
        for (EffectiveHook eh : gateHooks) {
            if (eh.def().enabled()
                    && eh.def().point() == HookPoint.BEFORE_TOOL
                    && eh.def().action() == wanted) {
                return eh;
            }
        }
        return null;
    }

    void recordGateDecision(
            HookCtx ctx,
            EffectiveHook hook,
            ToolDispatchGate.Outcome outcome,
            String tool,
            String reason) {
        sink.record(
                ctx,
                hook,
                outcome == ToolDispatchGate.Outcome.DENY ? "deny" : "require_approval",
                tool,
                reason);
    }

    private static String gateReason(String lead, String authored) {
        String reason =
                authored == null || authored.isBlank() ? lead : lead + ": " + authored.strip();
        return reason.endsWith(".") ? reason : reason + ".";
    }

    // ------------------------------------------------------------- tool: before

    /**
     * {@code BEFORE_TOOL} hooks that need the arguments or the clock: limit, redact, webhook, log.
     */
    ToolPre beforeTool(
            HookCtx ctx,
            List<EffectiveHook> hooks,
            String tool,
            String args,
            HookRateLimiter.RunCounters run) {
        Budget budget = new Budget(budgetMillis);
        String current = args;
        for (EffectiveHook eh : hooks) {
            HookDefinition h = eh.def();
            if (!h.enabled() || h.point() != HookPoint.BEFORE_TOOL) {
                continue;
            }
            switch (h.action()) {
                case LIMIT -> {
                    if (!withinLimit(ctx, eh, run)) {
                        String reason = limitReason(ctx, h);
                        sink.record(ctx, eh, "limited", tool, reason);
                        return ToolPre.deny(texts.toolDenied(ctx, tool, reason));
                    }
                }
                case REDACT -> {
                    Redacted r = redact(ctx, eh, tool, current, budget);
                    if (r.failed() || (r.count() > 0 && !jsonStillValid(current, r.text()))) {
                        if (!r.failed()) {
                            sink.record(
                                    ctx,
                                    eh,
                                    "error",
                                    tool,
                                    "redaction would have produced invalid arguments");
                        }
                        return ToolPre.deny(
                                texts.toolDenied(
                                        ctx, tool, texts.text(ctx, HookTexts.ARGS_NOT_REDACTABLE)));
                    }
                    current = r.text();
                }
                case WEBHOOK -> {
                    Verdict v = webhookVerdict(ctx, eh, tool, current, budget);
                    if (!v.allow()) {
                        return ToolPre.deny(texts.toolDenied(ctx, tool, v.reason()));
                    }
                }
                case LOG -> sink.record(ctx, eh, "logged", tool, label(h));
                default -> {
                    // block and require_approval are decided at the dispatch gate (see
                    // gateVerdict).
                }
            }
        }
        return ToolPre.ok(current);
    }

    // -------------------------------------------------------------- tool: after

    /**
     * {@code AFTER_TOOL} hooks, applied to the model-facing result. Returns the text the model
     * should see.
     */
    String afterTool(HookCtx ctx, List<EffectiveHook> hooks, String tool, String result) {
        Budget budget = new Budget(budgetMillis);
        String current = result == null ? "" : result;
        for (EffectiveHook eh : hooks) {
            HookDefinition h = eh.def();
            if (!h.enabled() || h.point() != HookPoint.AFTER_TOOL) {
                continue;
            }
            switch (h.action()) {
                case BLOCK -> {
                    String reason =
                            authoredOr(
                                    h.str("reason"), texts.text(ctx, HookTexts.DEFAULT_BLOCK_REASON));
                    sink.record(ctx, eh, "deny", tool, reason);
                    return texts.withheldResult(ctx, tool, reason);
                }
                case REDACT -> {
                    Redacted r = redact(ctx, eh, tool, current, budget);
                    if (r.failed()) {
                        return texts.withheldResult(
                                ctx, tool, texts.text(ctx, HookTexts.OUTPUT_NOT_REDACTABLE));
                    }
                    current = r.text();
                }
                case APPEND_CONTEXT -> {
                    current =
                            current
                                    + "\n\n"
                                    + texts.text(
                                            ctx,
                                            HookTexts.TOOL_RESULT_NOTE,
                                            Map.of("note", String.valueOf(h.str("note"))));
                    sink.record(ctx, eh, "note", tool, null);
                }
                case WEBHOOK -> {
                    Verdict v = webhookVerdict(ctx, eh, tool, current, budget);
                    if (!v.allow()) {
                        return texts.withheldResult(ctx, tool, v.reason());
                    }
                }
                case LOG -> sink.record(ctx, eh, "logged", tool, label(h));
                default -> {
                    // limit / require_approval do not apply after a tool ran (rejected at save
                    // time).
                }
            }
        }
        return current;
    }

    // ------------------------------------------------------------------ model

    /** {@code BEFORE_MODEL}: refuse the turn, and/or contribute a note to the turn's context. */
    ModelPre beforeModel(HookCtx ctx, List<EffectiveHook> hooks, String userMessage) {
        Budget budget = new Budget(budgetMillis);
        StringBuilder note = null;
        for (EffectiveHook eh : hooks) {
            HookDefinition h = eh.def();
            if (!h.enabled() || h.point() != HookPoint.BEFORE_MODEL) {
                continue;
            }
            switch (h.action()) {
                case BLOCK -> {
                    String reason =
                            authoredOr(
                                    h.str("reason"), texts.text(ctx, HookTexts.REQUEST_BLOCKED));
                    sink.record(ctx, eh, "deny", null, reason);
                    return new ModelPre(reason, null);
                }
                case LIMIT -> {
                    Integer perMinute = h.intParam("maxPerMinute");
                    if (perMinute != null
                            && !limiter.tryAcquire(
                                    limitKey(ctx, eh), perMinute, HookRateLimiter.MINUTE_MILLIS)) {
                        String reason =
                                authoredOr(
                                        h.str("message"), texts.text(ctx, HookTexts.RATE_LIMITED));
                        sink.record(ctx, eh, "limited", null, reason);
                        return new ModelPre(reason, null);
                    }
                }
                case APPEND_CONTEXT -> {
                    if (note == null) {
                        note =
                                new StringBuilder(
                                        texts.text(
                                                ctx,
                                                HookTexts.CONTEXT_NOTE,
                                                Map.of("note", String.valueOf(h.str("note")))));
                    } else {
                        note.append(' ').append(h.str("note"));
                    }
                    sink.record(ctx, eh, "note", null, null);
                }
                case WEBHOOK -> {
                    Verdict v = webhookVerdict(ctx, eh, null, userMessage, budget);
                    if (!v.allow()) {
                        return new ModelPre(
                                authoredOr(v.reason(), texts.text(ctx, HookTexts.REQUEST_BLOCKED)),
                                null);
                    }
                }
                case LOG -> sink.record(ctx, eh, "logged", null, label(h));
                default -> {
                    // redact / require_approval do not apply before the model (rejected at save
                    // time).
                }
            }
        }
        return new ModelPre(null, note == null ? null : note.toString());
    }

    /**
     * {@code AFTER_MODEL}: returns the reply to deliver - the model's own text, redacted, or a
     * refusal.
     *
     * @param reply the complete reply; may be empty for a hook set that only logs
     */
    String afterModel(HookCtx ctx, List<EffectiveHook> hooks, String reply) {
        Budget budget = new Budget(budgetMillis);
        String current = reply == null ? "" : reply;
        for (EffectiveHook eh : hooks) {
            HookDefinition h = eh.def();
            if (!h.enabled() || h.point() != HookPoint.AFTER_MODEL) {
                continue;
            }
            switch (h.action()) {
                case BLOCK -> {
                    String reason =
                            authoredOr(
                                    h.str("reason"), texts.text(ctx, HookTexts.REPLY_WITHHELD));
                    sink.record(ctx, eh, "deny", null, reason);
                    return reason;
                }
                case REDACT -> {
                    Redacted r = redact(ctx, eh, null, current, budget);
                    if (r.failed()) {
                        return texts.text(ctx, HookTexts.REPLY_NOT_REDACTABLE);
                    }
                    current = r.text();
                }
                case WEBHOOK -> {
                    Verdict v = webhookVerdict(ctx, eh, null, current, budget);
                    if (!v.allow()) {
                        return authoredOr(v.reason(), texts.text(ctx, HookTexts.REPLY_WITHHELD));
                    }
                }
                case LOG -> sink.record(ctx, eh, "logged", null, label(h));
                default -> {
                    // limit / append_context / require_approval do not apply after the model
                    // (rejected at save time).
                }
            }
        }
        return current;
    }

    // ----------------------------------------------------------- informational

    /**
     * The log-only {@code AFTER_MODEL} audit rows, off the calling thread (the reply is already
     * delivered unchanged).
     */
    void afterModelLogAsync(HookCtx ctx, List<EffectiveHook> hooks) {
        Runnable work =
                () -> {
                    try {
                        afterModel(ctx, hooks, "");
                    } catch (RuntimeException e) {
                        log.warn("AFTER_MODEL log hooks failed", e);
                    }
                };
        try {
            async.execute(ctx.scope().wrapRunnable(work));
        } catch (RejectedExecutionException e) {
            log.debug("Dropping AFTER_MODEL log hooks - executor rejected the task");
        }
    }

    /** {@code ON_ERROR} for a tool, off the calling thread. */
    void onToolError(HookCtx ctx, List<EffectiveHook> hooks, String tool, Throwable error) {
        String detail = error == null ? "error" : error.getClass().getSimpleName();
        informationalAsync(ctx, hooks, HookPoint.ON_ERROR, tool, detail);
    }

    /** {@code ON_ERROR} for the run, off the calling thread. */
    void onRunError(HookCtx ctx, List<EffectiveHook> hooks, Throwable error) {
        String detail = error == null ? "error" : error.getClass().getSimpleName();
        informationalAsync(ctx, hooks, HookPoint.ON_ERROR, null, detail);
    }

    /**
     * {@code ON_RUN_END}, off the calling thread. {@code signal} is {@code onComplete}, {@code
     * onError} or {@code cancel}.
     */
    void onRunEnd(HookCtx ctx, List<EffectiveHook> hooks, String signal) {
        informationalAsync(ctx, hooks, HookPoint.ON_RUN_END, null, signal);
    }

    private void informationalAsync(
            HookCtx ctx, List<EffectiveHook> hooks, HookPoint point, String tool, String detail) {
        Runnable work = () -> informational(ctx, hooks, point, tool, detail);
        try {
            async.execute(ctx.scope().wrapRunnable(work));
        } catch (RejectedExecutionException e) {
            log.debug("Dropping informational hooks at {} - executor rejected the task", point);
        }
    }

    /** Synchronous body of the informational points; package-private so tests can run it inline. */
    void informational(
            HookCtx ctx, List<EffectiveHook> hooks, HookPoint point, String tool, String detail) {
        Budget budget = new Budget(budgetMillis);
        for (EffectiveHook eh : hooks) {
            HookDefinition h = eh.def();
            if (!h.enabled() || h.point() != point) {
                continue;
            }
            try {
                switch (h.action()) {
                    case LOG -> sink.record(ctx, eh, "logged", tool, detail);
                    case WEBHOOK -> webhookVerdict(ctx, eh, tool, detail, budget);
                    default -> {
                        // only log and webhook are accepted at these points (rejected at save
                        // time).
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Informational hook {} failed", eh.key(), e);
            }
        }
    }

    // ------------------------------------------------------------------ pieces

    private boolean withinLimit(HookCtx ctx, EffectiveHook eh, HookRateLimiter.RunCounters run) {
        HookDefinition h = eh.def();
        Integer perRun = h.intParam("maxPerRun");
        Integer perMinute = h.intParam("maxPerMinute");
        if (perRun != null && run != null && !run.tryIncrement(eh.key(), perRun)) {
            return false;
        }
        return perMinute == null
                || limiter.tryAcquire(limitKey(ctx, eh), perMinute, HookRateLimiter.MINUTE_MILLIS);
    }

    private static String limitKey(HookCtx ctx, EffectiveHook eh) {
        return ctx.tenantId() + "|" + ctx.agentId() + "|" + eh.key();
    }

    private String limitReason(HookCtx ctx, HookDefinition h) {
        return authoredOr(h.str("message"), texts.text(ctx, HookTexts.DEFAULT_LIMIT_REASON));
    }

    /**
     * Redaction as a fail-closed step: any failure is reported as {@code failed}, never as "nothing
     * matched".
     */
    private Redacted redact(
            HookCtx ctx, EffectiveHook eh, String tool, String text, Budget budget) {
        HookDefinition h = eh.def();
        try {
            long remaining = Math.max(1L, budget.remainingMillis());
            HookRedactor.Result r =
                    HookRedactor.redact(
                            text,
                            h.strList("patterns"),
                            h.strList("regex"),
                            h.str("replacement"),
                            remaining);
            if (r.count() > 0) {
                sink.record(
                        ctx,
                        eh,
                        "redacted",
                        tool,
                        r.count() + (r.count() == 1 ? " match" : " matches"));
            }
            return new Redacted(r.text(), r.count(), false);
        } catch (RuntimeException e) {
            sink.record(
                    ctx, eh, "error", tool, "redaction failed: " + e.getClass().getSimpleName());
            return new Redacted(text, 0, true);
        }
    }

    private Verdict webhookVerdict(
            HookCtx ctx, EffectiveHook eh, String tool, String data, Budget budget) {
        HookDefinition h = eh.def();
        long timeout =
                Math.min(Math.min((long) h.timeoutMs(), hardCapMillis), budget.remainingMillis());
        if (timeout <= 0L) {
            return webhookFailed(ctx, eh, tool, "the hook latency budget was exhausted");
        }
        try {
            String payload = payloadJson(ctx, eh, tool, data);
            HookWebhookClient.Reply reply =
                    webhook.post(
                            h.str("url"), payload, Duration.ofMillis(timeout), ctx.webhookHosts());
            if (reply.allow()) {
                sink.record(ctx, eh, "allow", tool, reply.reason());
                return Verdict.ALLOW_VERDICT;
            }
            sink.record(ctx, eh, "deny", tool, reply.reason());
            // Null when the webhook gave no reason: each caller then words its own default for its
            // own audience.
            return new Verdict(false, reply.reason());
        } catch (RuntimeException e) {
            return webhookFailed(
                    ctx,
                    eh,
                    tool,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private Verdict webhookFailed(HookCtx ctx, EffectiveHook eh, String tool, String why) {
        sink.record(ctx, eh, "error", tool, why);
        if (eh.def().failMode() == FailMode.CLOSED) {
            return new Verdict(false, texts.text(ctx, HookTexts.CHECK_INCOMPLETE));
        }
        return Verdict.ALLOW_VERDICT;
    }

    private static String payloadJson(HookCtx ctx, EffectiveHook eh, String tool, String data) {
        HookDefinition h = eh.def();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "agent.hook");
        event.put("point", h.point().name());
        event.put("hook", eh.key());
        event.put("agentId", String.valueOf(ctx.agentId()));
        event.put("sessionId", ctx.sessionId());
        event.put("tenantId", ctx.tenantId());
        if (tool != null) {
            event.put("tool", tool);
        }
        event.put("timestamp", Instant.now().toString());
        if (h.bool("includeData") && data != null) {
            String safe = AgentStepEventRedactor.redactPayload(data);
            event.put(
                    "data",
                    safe.length() > MAX_WEBHOOK_DATA_CHARS
                            ? safe.substring(0, MAX_WEBHOOK_DATA_CHARS)
                            : safe);
        }
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise the hook event", e);
        }
    }

    /** If the original looked like a JSON document, the redacted text must still parse as one. */
    static boolean jsonStillValid(String before, String after) {
        if (before == null) {
            return true;
        }
        String trimmed = before.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return true;
        }
        try {
            MAPPER.readTree(after);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The shipped wording of the result for a stopped call (hook-free callers, tests). */
    static String toolDenied(String tool, String reason) {
        return HookTexts.DEFAULTS.toolDenied(null, tool, reason);
    }

    /** The shipped wording of the result for withheld output (hook-free callers, tests). */
    static String withheldResult(String tool, String reason) {
        return HookTexts.DEFAULTS.withheldResult(null, tool, reason);
    }

    private static String authoredOr(String value, String fallback) {
        return HookTexts.authoredOr(value, fallback);
    }

    private static String label(HookDefinition h) {
        return h.str("label");
    }
}
