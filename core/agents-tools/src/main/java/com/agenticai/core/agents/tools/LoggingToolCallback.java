package com.agenticai.core.agents.tools;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.ToolCallOutputValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
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
        try {
            output =
                    toolContext != null
                            ? delegate.call(toolInput, toolContext)
                            : delegate.call(toolInput);
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
            // Built-in tools only (no MCP grant): cap what goes back to the model; the full
            // output is still persisted to tool_call_log below. MCP results are already
            // capped by SanitizedMcpToolCallback.
            String result =
                    mcpGrantId == null ? McpToolOutputSanitizer.capBuiltInResult(output) : output;
            if (effectKey != null) {
                // The tool RETURNED (executed or gated): no longer in flight. A throw leaves it
                // STARTED on purpose:
                // a failure after the provider acted is indistinguishable from one before it.
                effectJournal.markDurableCallDone(sessionId, effectKey);
            }
            if (effectKey != null && !validationFailed && wasExecuted(output)) {
                effectJournal.record(effectKey, result);
                countEffect("agent.tool.effect_journal.recorded", toolName);
            }
            return result;
        } catch (RuntimeException e) {
            // Persisted to tool_call_log and the admin Issues portal, both of
            // which render it - scrub credential-shaped text first, same pass
            // the model-facing error goes through. Rethrown untouched so
            // TenantContextPropagatingToolCallback still decides what the
            // model sees.
            toolOriginatedFailure = isToolOriginated(e);
            String detail =
                    e.getMessage() != null && !e.getMessage().isBlank()
                            ? e.getMessage()
                            : e.getClass().getSimpleName();
            error = com.agenticai.core.chat.RedactingToolExecutionExceptionProcessor.redact(detail);
            throw e;
        } finally {
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
        if (output == null) {
            return false;
        }
        return !(output.startsWith("This tool call (")
                || output.startsWith("This tool call failed")
                || output.startsWith("This action (")
                || output.startsWith("This tool (")
                || output.startsWith("Tool output from '")
                || output.startsWith(ToolCallOutputValidator.VALIDATION_FAILURE_PREFIX));
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
    private static String disabledMessage(
            String toolName, ToolCircuitBreakerService.OpenInfo open) {
        StringBuilder message =
                new StringBuilder("This tool ('")
                        .append(toolName)
                        .append("') has been temporarily disabled after repeated failures");
        if (open.lastError() != null && !open.lastError().isBlank()) {
            message.append(" (most recent error: ").append(open.lastError()).append(")");
        }
        long minutesLeft = Math.max(1, Math.round(open.secondsRemaining() / 60.0));
        message.append(". It becomes available again automatically in about ")
                .append(minutesLeft)
                .append(minutesLeft == 1 ? " minute" : " minutes")
                .append(
                        ". Try a different approach, or let the user know this capability is briefly unavailable.");
        return message.toString();
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
