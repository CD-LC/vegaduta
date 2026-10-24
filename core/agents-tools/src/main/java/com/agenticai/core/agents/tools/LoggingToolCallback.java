package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.agents.turn.ToolLedgerSummary;
import com.agenticai.core.agents.turn.TurnToolActivity;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.GuardrailPrompts;
import com.agenticai.core.guardrails.ToolCallOutputValidator;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolResultMarker;
import com.agenticai.core.guardrails.TurnApprovalState;
import com.agenticai.core.prompts.PromptResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Wraps an MCP-provided ToolCallback to log every invocation - name, input, output/error, timing -
 * to Postgres via ToolCallLogRepository. agentId and sessionId are captured in the constructor
 * closure (AgentChatService builds a fresh, wrapped tool list per chat call, so both are known at
 * wrap time) rather than threaded through Spring AI's ToolContext, which keeps this decorator's
 * contract to the plain, stable ToolCallback interface only.
 */
public class LoggingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);

    private final ToolCallback delegate;
    private final ToolCallLogRepository toolCallLogRepository;
    private final UUID agentId;
    private final String sessionId;
    private final ObjectProvider<PlatformIssueService> issueService;
    private final ToolCircuitBreakerService circuitBreakerService;
    private final UUID mcpGrantId;
    // Idempotency enforcement for DURABLE (write/send) tools; all null = off (legacy shape).
    private final EffectJournal effectJournal;
    private final Predicate<String> durableTool;
    private final String turnId;
    private final MeterRegistry meterRegistry;

    /**
     * The one tool-output cap ({@code app.tools.output.*}); the module defaults unless {@link
     * #withOutputLimits} sets the operator's. {@code GuardedToolCallback}, inside this wrapper, has
     * normally cut an over-long result already, and cutting a cut result changes nothing - this is the
     * same cap applied once more for the built-in tool that reaches this wrapper without that guard.
     */
    private volatile ToolOutputProperties outputLimits = new ToolOutputProperties();

    /**
     * The turn's record of what its tools are doing (null = not tracked): a call is reported in flight
     * while the tool works and ends as executed, journaled, failed, replayed or not-run. The stream idle
     * timer and the failover replay gate read it; see {@link TurnToolActivity}.
     */
    private volatile TurnToolActivity activity;

    /**
     * What the turn's tool ledger needs besides the activity record it lives in: the limits (null = the
     * ledger is off, nothing is recorded), the turn's approval state (null = no call is ever reported as
     * held for a human) and the redactor for model-facing text (null = credentials only).
     */
    private volatile ToolLedgerProperties ledgerLimits;

    private volatile TurnApprovalState approvalState;
    private volatile UnaryOperator<String> ledgerRedactor;

    /**
     * Records every call this wrapper sees into the turn's tool ledger ({@link
     * TurnToolActivity#ledger()}, so {@link #withActivity} must be set too), summarised to {@code limits}
     * and masked with {@code redactor} (the one the model-facing result goes through) on top of the
     * credential scrub. {@code null} limits turn the ledger off.
     */
    public LoggingToolCallback withLedger(
            ToolLedgerProperties limits, TurnApprovalState approval, UnaryOperator<String> redactor) {
        this.ledgerLimits = limits != null && limits.isEnabled() ? limits : null;
        this.approvalState = approval;
        this.ledgerRedactor = redactor;
        return this;
    }

    /**
     * The turn's tool timeout ({@code toolTimeoutSeconds} of the runtime settings): a call that outlives {@code
     * limit} is stopped and the model is told {@code message}. Null (the default) = no limit, the call runs inline.
     */
    public LoggingToolCallback withToolTimeout(java.time.Duration limit, String message) {
        this.timeout = limit == null || limit.isZero() || limit.isNegative() ? null : new ToolCallTimeout(limit, message);
        return this;
    }

    private volatile ToolCallTimeout timeout;

    /** Registry keys of the circuit-breaker result ({@code prompts/en/toolresult.tool_breaker.*.md}). */
    static final String DISABLED_KEY = "toolresult.tool_breaker.disabled";

    static final String LAST_ERROR_KEY = "toolresult.tool_breaker.last_error";
    static final String WAIT_ONE_KEY = "toolresult.tool_breaker.wait.one";
    static final String WAIT_MANY_KEY = "toolresult.tool_breaker.wait.many";

    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    /** Reads the circuit-breaker wording from the prompt registry for this agent; null keeps the shipped text. */
    public LoggingToolCallback withPrompts(PromptResolver resolver) {
        this.prompts = resolver == null ? PromptResolver.defaultsOnly() : resolver;
        return this;
    }

    /** Reports every call to {@code turnActivity}; null leaves the wrapper untracked. */
    public LoggingToolCallback withActivity(TurnToolActivity turnActivity) {
        this.activity = turnActivity;
        return this;
    }

    /** Uses the operator's tool-output cap; null keeps the module defaults. */
    public LoggingToolCallback withOutputLimits(ToolOutputProperties limits) {
        if (limits != null) {
            this.outputLimits = limits;
        }
        return this;
    }

    public LoggingToolCallback(
            ToolCallback delegate,
            ToolCallLogRepository toolCallLogRepository,
            UUID agentId,
            String sessionId,
            ObjectProvider<PlatformIssueService> issueService,
            ToolCircuitBreakerService circuitBreakerService,
            UUID mcpGrantId) {
        this(
                delegate,
                toolCallLogRepository,
                agentId,
                sessionId,
                issueService,
                circuitBreakerService,
                mcpGrantId,
                null,
                null,
                null,
                null);
    }

    public LoggingToolCallback(
            ToolCallback delegate,
            ToolCallLogRepository toolCallLogRepository,
            UUID agentId,
            String sessionId,
            ObjectProvider<PlatformIssueService> issueService,
            ToolCircuitBreakerService circuitBreakerService,
            UUID mcpGrantId,
            EffectJournal effectJournal,
            Predicate<String> durableTool,
            String turnId,
            MeterRegistry meterRegistry) {
        this.effectJournal = effectJournal;
        this.durableTool = durableTool;
        this.turnId = turnId;
        this.meterRegistry = meterRegistry;
        this.delegate = delegate;
        this.toolCallLogRepository = toolCallLogRepository;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.issueService = issueService;
        this.circuitBreakerService = circuitBreakerService;
        this.mcpGrantId = mcpGrantId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return loggedCall(toolInput, null);
    }

    /** Forwards Spring AI's {@link ToolContext} to the wrapped tool instead of dropping it. */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return loggedCall(toolInput, toolContext);
    }

    private String loggedCall(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();

        // #815 Governance follow-up (Fail-safe: circuit breakers on
        // repeated failures) - checked BEFORE anything else in this method,
        // including GuardedToolCallback's own step-count/approval/
        // verification checks below it: a tripped tool shouldn't consume
        // this turn's step budget or trigger an approval request, it should
        // just fail fast with a clear explanation. See
        // ToolCircuitBreakerService's javadoc for the trip/cooldown shape.
        ToolCircuitBreakerService.OpenInfo open = circuitBreakerService.openInfo(toolName);
        if (open != null) {
            String blocked = disabledMessage(toolName, open);
            // Blocked calls used to return without a trace - persist them so
            // "the agent kept hammering a tripped tool" is visible in
            // tool_call_log like every other outcome. Not counted as a
            // failure (the delegate never ran) and not as a success either.
            persistBlockedCall(toolName, toolInput, blocked);
            recordInLedger(toolName, toolInput, blocked, ToolLedger.Status.NOT_RUN);
            return blocked;
        }

        // Item 7 (idempotency): a DURABLE tool already executed successfully with the same
        // run/step/tool/args within the journal TTL returns the recorded result.
        String effectKey = null;
        if (effectJournal != null
                && effectJournal.isEnabled()
                && durableTool != null
                && isDurable(toolName)) {
            // Job sessions with the durable step journal on use a stable step id so a RESUMED
            // attempt
            // (new process, new random turn id) maps the same write/send call to the same key.
            String stepForKey = effectJournal.durableJobScope(sessionId) ? "job" : turnId;
            effectKey = EffectJournal.keyFor(sessionId, stepForKey, toolName, toolInput);
            java.util.Optional<String> prior = effectJournal.lookup(effectKey);
            if (prior.isPresent()) {
                log.warn(
                        "Idempotent replay: tool '{}' already executed in session {}; returning recorded result",
                        toolName,
                        sessionId);
                countEffect("agent.tool.effect_journal.replayed", toolName);
                TurnToolActivity replayed = activity;
                if (replayed != null) {
                    replayed.end(replayed.begin(toolName), TurnToolActivity.Outcome.REPLAYED);
                }
                recordInLedger(toolName, toolInput, prior.get(), ToolLedger.Status.REPLAYED);
                return prior.get();
            }
            // Durable job scope only (no-op otherwise): mark the call in flight BEFORE the provider
            // is reached.
            effectJournal.markDurableCallStarted(sessionId, effectKey);
        }

        Instant startedAt = Instant.now();
        String output = null;
        String error = null;
        boolean validationFailed = false;
        boolean toolOriginatedFailure = false;
        // What the model is shown as this call's result, for the turn's tool ledger.
        String shownResult = null;
        // From here the call is in flight until the finally block below: a long tool emits no token,
        // and the stream idle timer must not read that silence as a dead provider.
        TurnToolActivity turnActivity = activity;
        long activityCall = turnActivity == null ? -1 : turnActivity.begin(toolName);
        TurnToolActivity.Outcome outcome = TurnToolActivity.Outcome.NOT_RUN;
        try {
            ToolCallTimeout limit = timeout;
            output =
                    limit == null
                            ? invoke(toolInput, toolContext)
                            : limit.run(toolName, () -> invoke(toolInput, toolContext));
            // #815 Gap 1 - GuardedToolCallback (the delegate we're wrapping,
            // per AgentToolProvider#guardAndLog's wrapping order) tags a
            // structural-validation failure with this prefix instead of
            // throwing, since it's a normal tool-result the model should
            // see and react to. Strip the internal marker here before
            // either persisting or returning it - a human reading the log
            // or the model reading its own tool result should see a clean
            // sentence, and the boolean column is the durable signal for
            // "this was a validation failure," not the string contents.
            if (output != null
                    && output.startsWith(ToolCallOutputValidator.VALIDATION_FAILURE_PREFIX)) {
                validationFailed = true;
                output =
                        output.substring(
                                ToolCallOutputValidator.VALIDATION_FAILURE_PREFIX.length());
            }
            // Built-in tools only (no MCP grant): cap what goes back to the model at the same
            // app.tools.output.* limit as everything else (it used to be its own, lower, constant);
            // the output is still persisted to tool_call_log below. MCP results are already
            // bounded by SanitizedMcpToolCallback and by the guard inside this wrapper.
            String result = mcpGrantId == null ? outputLimits.limit(toolName, output) : output;
            shownResult = result;
            if (effectKey != null) {
                // The tool RETURNED (executed or gated): no longer in flight. A throw leaves it
                // STARTED on purpose:
                // a failure after the provider acted is indistinguishable from one before it.
                effectJournal.markDurableCallDone(sessionId, effectKey);
            }
            if (!validationFailed && wasExecuted(output)) {
                outcome = TurnToolActivity.Outcome.EXECUTED;
            }
            if (effectKey != null && !validationFailed && wasExecuted(output)) {
                effectJournal.record(effectKey, result);
                countEffect("agent.tool.effect_journal.recorded", toolName);
                outcome = TurnToolActivity.Outcome.JOURNALED;
            }
            return result;
        } catch (RuntimeException e) {
            // Persisted to tool_call_log and the admin Issues portal, both of
            // which render it - scrub credential-shaped text first, same pass
            // the model-facing error goes through. Rethrown untouched so
            // TenantContextPropagatingToolCallback still decides what the
            // model sees.
            toolOriginatedFailure = isToolOriginated(e);
            // A throw that came from the tool may have come after it acted; one the platform raised
            // around it (guard, step budget) means the tool never ran.
            outcome =
                    toolOriginatedFailure
                            ? TurnToolActivity.Outcome.FAILED
                            : TurnToolActivity.Outcome.NOT_RUN;
            String detail =
                    e.getMessage() != null && !e.getMessage().isBlank()
                            ? e.getMessage()
                            : e.getClass().getSimpleName();
            error = com.agenticai.core.chat.RedactingToolExecutionExceptionProcessor.redact(detail);
            throw e;
        } finally {
            if (turnActivity != null) {
                turnActivity.end(activityCall, outcome);
            }
            recordInLedger(
                    toolName,
                    toolInput,
                    error != null ? error : shownResult != null ? shownResult : output,
                    ledgerStatus(
                            toolName,
                            toolInput,
                            error,
                            validationFailed,
                            toolOriginatedFailure,
                            outcome,
                            ToolResultMarker.kindOf(output).filter(k -> k == ToolResultMarker.Kind.SANDBOXED).isPresent()));
            try {
                ToolCallLog row =
                        new ToolCallLog(
                                agentId,
                                sessionId,
                                toolName,
                                toolInput,
                                output,
                                error,
                                startedAt,
                                Instant.now(),
                                validationFailed,
                                mcpGrantId);
                // C1: stamp the version the turn pinned (null when unpinned -
                // TenantContextPropagatingToolCallback
                // re-establishes the pin on this worker thread, so this usually resolves).
                row.setAgentVersionId(com.agenticai.core.security.AgentVersionPin.current());
                toolCallLogRepository.save(row);
            } catch (RuntimeException logFailure) {
                // Never let logging itself break the tool call.
                log.warn("Failed to persist tool-call log for tool {}", toolName, logFailure);
            }
            if (error != null) {
                recordIssue(toolName, error);
            } else if (validationFailed) {
                recordIssue(toolName, "Tool output failed structural validation: " + output);
            }
            // #815 Governance follow-up - fed into the circuit breaker's
            // consecutive-failure streak, but ONLY for failures the tool
            // itself owns: GuardedToolCallback tags exceptions from its own
            // checks (approval-policy read, procedure guard, verifier, output
            // validation, step limit) as ToolInfrastructureException/known
            // stop types so a shared platform outage can no longer trip the
            // breakers of every tool at once ("why did ALL tools fail").
            // A clean call resets the streak; a platform-internal throw
            // records nothing either way - it says nothing about the tool.
            if (validationFailed) {
                circuitBreakerService.recordFailure(
                        toolName, "Tool output failed structural validation");
            } else if (toolOriginatedFailure) {
                circuitBreakerService.recordFailure(toolName, error);
            } else if (error == null) {
                circuitBreakerService.recordSuccess(toolName);
            }
        }
    }

    private String invoke(String toolInput, ToolContext toolContext) {
        return toolContext != null ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
    }

    /**
     * How the call ended for the turn's tool ledger: held for a human (the turn's approval state has a
     * pending request for exactly this call), failed (the tool threw or its result was withheld), not run
     * (a guard answered), or ok.
     */
    private ToolLedger.Status ledgerStatus(
            String toolName,
            String toolInput,
            String error,
            boolean validationFailed,
            boolean toolOriginatedFailure,
            TurnToolActivity.Outcome outcome,
            boolean simulated) {
        if (approvalRequestId(toolName, toolInput) != null) {
            return ToolLedger.Status.AWAITING_APPROVAL;
        }
        if (error != null) {
            return toolOriginatedFailure ? ToolLedger.Status.FAILED : ToolLedger.Status.NOT_RUN;
        }
        if (validationFailed) {
            return ToolLedger.Status.FAILED;
        }
        if (outcome == TurnToolActivity.Outcome.EXECUTED || outcome == TurnToolActivity.Outcome.JOURNALED) {
            return ToolLedger.Status.OK;
        }
        // An offline eval turn's sandbox answered with a simulated result: the tool did not run, and the record
        // says so in its own word instead of folding it into every other kind of "a guard answered".
        return simulated ? ToolLedger.Status.SIMULATED : ToolLedger.Status.NOT_RUN;
    }

    private String approvalRequestId(String toolName, String toolInput) {
        TurnApprovalState state = approvalState;
        return state == null ? null : state.requestIdFor(toolName, toolInput);
    }

    /**
     * Adds the call to the turn's tool ledger, summarised and masked ({@link ToolLedgerSummary}). Never
     * throws: the ledger is a record of the call, not part of it.
     */
    private void recordInLedger(String toolName, String toolInput, String result, ToolLedger.Status status) {
        ToolLedgerProperties limits = ledgerLimits;
        TurnToolActivity turnActivity = activity;
        if (limits == null || turnActivity == null) {
            return;
        }
        try {
            String requestId = status == ToolLedger.Status.AWAITING_APPROVAL ? approvalRequestId(toolName, toolInput) : null;
            turnActivity
                    .ledger()
                    .record(
                            ToolLedgerSummary.entry(
                                    toolName,
                                    toolInput,
                                    // A held call has no result: the park text is the platform's, and the
                                    // status and request id already say everything it does.
                                    status == ToolLedger.Status.AWAITING_APPROVAL ? null : result,
                                    status,
                                    requestId,
                                    limits,
                                    ledgerRedactor),
                            limits.getMaxEntriesPerTurn());
        } catch (RuntimeException e) {
            log.warn("Could not record tool call {} in the turn's tool ledger", toolName, e);
        }
    }

    private boolean isDurable(String toolName) {
        try {
            return durableTool.test(toolName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Guard/approval/block messages are returned (not thrown) as normal results; they mean the tool
     * did NOT run, so they must not be journaled as an executed effect.
     */
    static boolean wasExecuted(String output) {
        // A typed answer: the layer that replied registered its exact text with ToolResultMarker, whatever the wording
        // (the approval-park text is configurable). The old wording prefixes, the validation-failure prefix and the
        // budget-exhausted result are still recognised, for a producer that did not register itself.
        return output != null && !ToolResultMarker.isPlatformAnswer(output);
    }

    private void countEffect(String name, String toolName) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(name, "tool", toolName).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record metric {}", name, e);
        }
    }

    /**
     * True when the thrown exception came from the tool itself rather than the platform machinery
     * wrapped around it. {@code GuardedToolCallback} tags its own check failures with {@link
     * com.agenticai.core.guardrails.ToolInfrastructureException}; {@code
     * ToolStepLimitExceededException} is a deliberate turn-level stop, not a tool malfunction.
     * Everything else - including a raw exception from an unwrapped delegate (tests, direct
     * construction) - counts as the tool's failure, preserving the pre-tagging behavior.
     */
    private static boolean isToolOriginated(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.agenticai.core.guardrails.ToolInfrastructureException
                    || t instanceof com.agenticai.core.guardrails.ToolStepLimitExceededException) {
                return false;
            }
        }
        return true;
    }

    /**
     * The model-facing result for a circuit-blocked call - now carries the redacted error that
     * tripped the breaker and the remaining cooldown, so the model (and the user it relays to) can
     * tell "transient blip" from "this dependency is actually down" instead of seeing an
     * unexplained "temporarily disabled" forever.
     */
    private String disabledMessage(String toolName, ToolCircuitBreakerService.OpenInfo open) {
        String lastError =
                open.lastError() != null && !open.lastError().isBlank()
                        ? GuardrailPrompts.text(prompts, LAST_ERROR_KEY, agentId, "error", open.lastError())
                        : "";
        long minutesLeft = Math.max(1, Math.round(open.secondsRemaining() / 60.0));
        String wait =
                GuardrailPrompts.text(
                        prompts,
                        minutesLeft == 1 ? WAIT_ONE_KEY : WAIT_MANY_KEY,
                        agentId,
                        "minutes",
                        String.valueOf(minutesLeft));
        String message =
                GuardrailPrompts.text(
                        prompts,
                        DISABLED_KEY,
                        agentId,
                        "tool",
                        toolName,
                        "last_error",
                        lastError,
                        "wait",
                        wait);
        return ToolResultMarker.mark(message, ToolResultMarker.Kind.BLOCKED);
    }

    /**
     * A circuit-blocked call never reaches the try/finally below, so it used to leave no {@code
     * tool_call_log} row at all - invisible in the same audit surface every other outcome lands in.
     * Logged with {@code error=null} on purpose: a blocked call is neither a failure (the delegate
     * never ran) nor a success, and it must not feed the breaker streak or the Issues portal again.
     */
    private void persistBlockedCall(String toolName, String toolInput, String result) {
        try {
            Instant now = Instant.now();
            ToolCallLog row =
                    new ToolCallLog(
                            agentId,
                            sessionId,
                            toolName,
                            toolInput,
                            result,
                            null,
                            now,
                            now,
                            false,
                            mcpGrantId);
            row.setAgentVersionId(com.agenticai.core.security.AgentVersionPin.current());
            toolCallLogRepository.save(row);
        } catch (RuntimeException logFailure) {
            log.warn(
                    "Failed to persist circuit-blocked tool-call log for tool {}",
                    toolName,
                    logFailure);
        }
    }

    // Admin Issues portal (#425/#426) - sourceId is the tool NAME, not this
    // one call's input/agentId, so repeated failures of the same tool
    // (across agents/sessions) dedupe onto a single issue row - see
    // PlatformIssueService#fingerprint's javadoc. Best-effort: never let
    // issue-recording break the tool call, same posture as the
    // toolCallLogRepository.save() right above it.
    private void recordIssue(String toolName, String error) {
        PlatformIssueService service = issueService.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.record(
                    IssueSource.TOOL_CALL,
                    toolName,
                    IssueSeverity.WARNING,
                    "Tool call failed: " + toolName,
                    error);
        } catch (RuntimeException recordingFailure) {
            log.warn(
                    "Failed to record platform issue for failed tool call {}",
                    toolName,
                    recordingFailure);
        }
    }
}
