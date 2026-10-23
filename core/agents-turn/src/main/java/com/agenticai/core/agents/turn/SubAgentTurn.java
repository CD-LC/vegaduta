package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.hooks.AgentHookRuntime;
import com.agenticai.core.agents.hooks.HookRun;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turnjournal.TurnRecorder;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * The turn a delegated or specialist agent runs for another agent's tool call ({@link ChatDelegateCall}, {@link
 * ChatSpecialistHandoff}): one question in, one answer out, on a throwaway session.
 *
 * <p>Until now that turn was a bare model call. It had the tool guard chain (its tools come through {@code
 * AgentToolGuard}) and nothing else: no screening of the question, no per-agent content guardrails on the answer,
 * no model-level hooks, no turn journal, no finish-reason handling and no continuation of a reply that stops at an
 * announced step. A task handed to a sub-agent therefore ran under fewer controls than the same task typed into
 * the chat, so the controls were bypassed by asking the main agent to delegate. This runs the sub-turn through the
 * same stages a buffered turn has, in the same order:
 *
 * <ol>
 *   <li>inbound moderation and the delegate's own inbound content guardrails on the question - a refusal is
 *       returned to the calling agent as the tool result, never thrown, like every other soft stop;
 *   <li>the delegate's {@code BEFORE_MODEL} hooks (a refusal ends the sub-turn; {@code append_context} extends its
 *       system prompt);
 *   <li>the model call with provider fallback, finish-reason handling and the one continuation policy ({@link
 *       BufferedTurnCalls#callBufferedTurn}), over tools wrapped for the turn journal;
 *   <li>the delegate's outbound content guardrails on the answer (a violating answer is withheld, the replacement
 *       is the tool result), then its {@code AFTER_MODEL}, {@code ON_ERROR} and {@code ON_RUN_END} hooks;
 *   <li>quality-judge sampling, and the journal's COMMIT.
 * </ol>
 *
 * <p>The sub-turn has its own journal record (its own turn id, its own session) whose INTAKE names the parent turn
 * when the calling tool call runs inside a journaled turn ({@link TurnRecorder#currentTurnId()}), so the two can be
 * followed from one to the other. It shares nothing else with the parent: no history, no memory, no approval state
 * (a gated call in the sub-turn parks through the guard chain exactly as before).
 */
final class SubAgentTurn {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /** What the sub-turn runs on; everything is resolved by the caller, which owns target resolution and access. */
    record Request(
            String kind,
            Agent agent,
            UUID agentId,
            String sessionId,
            String question,
            String systemPrompt,
            ChatClient chatClient,
            List<ToolCallback> tools,
            String experimentVariants,
            String tenantId,
            String emptyAnswer) {}

    private final BufferedTurnCalls calls;
    private final ChatGuardrailGate guardrails;
    private final Supplier<AgentHookRuntime> hooks;
    private final Function<String, TenantPlan> planOf;
    private final LlmQualityJudgeService qualityJudge;
    private final ProviderErrorTranslator providerErrorTranslator;

    /**
     * @param hooks read at turn time: the hook runtime is set after construction (null = no hooks)
     * @param planOf the tenant's plan for the plan-gated guardrails (null or null result = tenant-level config only)
     */
    SubAgentTurn(
            BufferedTurnCalls calls,
            ChatGuardrailGate guardrails,
            Supplier<AgentHookRuntime> hooks,
            Function<String, TenantPlan> planOf,
            LlmQualityJudgeService qualityJudge,
            ProviderErrorTranslator providerErrorTranslator) {
        this.calls = calls;
        this.guardrails = guardrails;
        this.hooks = hooks;
        this.planOf = planOf;
        this.qualityJudge = qualityJudge;
        this.providerErrorTranslator = providerErrorTranslator;
    }

    /** Runs the sub-turn; the answer, or the refusal / withheld notice the calling agent is shown instead. */
    String run(Request r) {
        TurnContext turn =
                TurnContext.fromCurrentThread(r.agentId(), r.sessionId(), null, Instant.now(), null);
        // What client selection records for a normal turn: a local server's text-only "next step" is checked.
        turn.modelTraits().weakToolCaller(calls.weakToolCaller(r.agent()));
        TurnRecorder journal =
                TurnJournals.begin(
                        turn,
                        r.agentId(),
                        r.sessionId(),
                        r.question(),
                        0,
                        r.tools(),
                        TurnRecorder.currentTurnId());
        journal.guard("sub-agent", r.kind());

        TenantPlan plan = r.tenantId() == null || planOf == null ? null : planOf.apply(r.tenantId());
        String refusal =
                firstOf(
                        guardrails.moderateInbound(
                                r.agentId(), r.sessionId(), r.sessionId(), r.question(), r.tenantId(), plan));
        if (refusal == null) {
            refusal =
                    firstOf(
                            guardrails.enforceInboundGuardrails(
                                    r.agent(), r.agentId(), r.sessionId(), r.question(), r.tenantId(), plan));
        }
        if (refusal != null) {
            journal.guard("inbound", "refused");
            journal.commit(refusal, "refused");
            return refusal;
        }
        journal.guard("inbound", "passed");

        AgentHookRuntime runtime = hooks == null ? null : hooks.get();
        HookRun hookRun = runtime == null ? null : runtime.beginRun(r.agentId(), r.sessionId(), null);
        String hookRefusal = hookRun == null ? null : hookRun.beforeModel(r.question());
        if (hookRefusal != null) {
            journal.guard("hook-before-model", "refused");
            String shown = drain(hookRun.refusal(hookRefusal), hookRefusal);
            journal.commit(shown, "refused");
            return shown;
        }
        String note = hookRun == null ? null : hookRun.contextNote();
        String systemPrompt =
                note == null || note.isBlank() ? r.systemPrompt() : r.systemPrompt() + "\n\n" + note;

        List<ToolCallback> journaledTools = journal.wrapTools(r.tools(), turn.approval());
        journal.modelCall(String.valueOf(r.agent().getProviderName()), r.agent().getModel(), r.kind());
        String answer;
        try {
            answer =
                    calls.callBufferedTurn(
                            r.agent(),
                            r.agentId(),
                            r.sessionId(),
                            r.chatClient(),
                            systemPrompt,
                            List.of(),
                            r.question(),
                            journaledTools,
                            List.of(),
                            turn,
                            r.experimentVariants());
        } catch (RuntimeException e) {
            log.warn("Sub-agent ({}) call to agent {} failed", r.kind(), r.agentId(), e);
            journal.commit(null, "error:" + e.getClass().getSimpleName());
            if (hookRun != null) {
                // ON_ERROR / ON_RUN_END observers see the failure like they do on a normal turn; the stream
                // re-raises it, which is the point of passing it through and not something to act on here.
                try {
                    drain(hookRun.wrap(Flux.error(e)), "");
                } catch (RuntimeException expected) {
                    log.debug("Hook observers ran for the failed sub-agent turn", expected);
                }
            }
            if (e instanceof ToolStepLimitExceededException || e instanceof GuardrailViolationException) {
                throw e;
            }
            // translate() wraps every Throwable, so it is never null in production; the guard only keeps a mocked
            // translator from turning the real failure into an NPE.
            RuntimeException translated = providerErrorTranslator.translate(e, r.agent().getProviderName());
            throw translated != null ? translated : e;
        }

        String text = answer == null ? "" : answer;
        String beforeGuardrails = text;
        text =
                guardrails.enforceOutboundGuardrails(
                        r.agent(), r.agentId(), r.sessionId(), r.tenantId(), r.chatClient(), text);
        journal.guard(
                "outbound-enforced", java.util.Objects.equals(text, beforeGuardrails) ? "passed" : "withheld");

        TurnReplyOutcome reply = turn.reply();
        if (reply.isComplete() && text.isBlank()) {
            // Nothing came back: the calling agent is told so in the same words as before.
            journal.commit(null, "empty");
            return afterModel(hookRun, r.emptyAnswer());
        }
        if (!reply.isComplete()) {
            // Cut off, refused by the provider's safety system, or nothing at all: the calling agent is told so
            // in the platform's words, not handed a fragment as if it were an answer.
            TurnReplyOutcome.State state = reply.snapshot();
            String notice = calls.finishNotice(r.agentId(), state);
            String shown = notice == null ? text : ReplyFinish.join(text, notice);
            journal.commit(shown, state.kind().name().toLowerCase(java.util.Locale.ROOT));
            return afterModel(hookRun, shown);
        }
        journal.commit(text, "completed");
        // Gap 2 - delegated calls are sampled too, same as any other turn.
        qualityJudge.sampleAsync(r.chatClient(), r.agentId(), r.sessionId(), r.question(), text);
        return afterModel(hookRun, text);
    }

    /** {@code AFTER_MODEL} (which may redact or block the reply) and the run-end observers, on the finished answer. */
    private static String afterModel(HookRun hookRun, String answer) {
        // Nothing out of a hook pipeline that was given an answer means a hook withheld it: it is not replaced by the
        // original.
        return hookRun == null ? answer : drain(hookRun.wrap(Flux.just(answer)), "");
    }

    /** A refusal Flux as its text, or null when there is none. */
    private static String firstOf(Flux<String> refusal) {
        return refusal == null ? null : drain(refusal, null);
    }

    /**
     * Joins a finished stream into one string without {@code block()}: a tool call may run on a thread Reactor marks
     * non-blocking, where {@code block()} throws; waiting on a future does not.
     */
    private static String drain(Flux<String> stream, String fallback) {
        try {
            List<String> chunks = stream.collectList().toFuture().get();
            String joined = String.join("", chunks);
            return joined.isEmpty() ? fallback : joined;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while finishing a delegated turn", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
