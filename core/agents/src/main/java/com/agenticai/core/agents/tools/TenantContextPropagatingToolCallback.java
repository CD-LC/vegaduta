package com.agenticai.core.agents.tools;

import com.agenticai.core.chat.RedactingToolExecutionExceptionProcessor;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.util.function.UnaryOperator;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Fixes a real cross-tenant bug found during Stage C's regression pass (not introduced by Stage C -
 * present since Stage A's {@code GuardedToolCallback} first shipped, confirmed unchanged by
 * re-running the same repro against current code).
 *
 * <p><b>Root cause:</b> {@link TenantContext} is a plain {@code ThreadLocal}, populated once per
 * HTTP request ({@code TenantContextFilter} for JWT-authenticated requests, {@code
 * TwilioWebhookController} manually for inbound webhooks) on the servlet request thread. {@code
 * AgentToolProvider.resolveToolsFor} - which builds this exact tool list - runs synchronously on
 * that same, correctly-scoped thread. But the actual {@link ToolCallback#call} invocations happen
 * later, driven by Spring AI's reactive {@code ChatClient.stream()} inside {@code
 * AgentChatService.chat}: dispatched on Reactor/WebClient scheduler threads that never inherit a
 * plain {@code ThreadLocal} from whatever thread subscribed to the {@code Flux}. By the time {@code
 * GuardedToolCallback.call} or {@code LoggingToolCallback.call} actually ran, {@code
 * TenantContext.current()} was empty, so {@code TenantSchemaResolver} silently fell back to the
 * {@code public} schema for every tenant-scoped query made during a tool call: {@code
 * ApprovalPolicyService#requiresApproval} looked up (and always missed) a policy override in {@code
 * public} instead of the tenant's real schema - so a tenant-admin's "require approval for this
 * tool" setting was silently never enforced, letting a gated tool execute freely - and {@code
 * ToolCallLogRepository#save} wrote every tool-call log entry into {@code public} too, so the
 * correctly-schema-routed {@code GET /api/agents/{id}/tool-calls} read never found what it had just
 * written.
 *
 * <p><b>Fix:</b> capture the calling thread's {@link TenantContext} once, in {@code
 * AgentToolProvider.guardAndLog} (which runs on the correct thread, synchronously, before the
 * reactive stream is ever subscribed), and re-apply it explicitly at the top of every {@link #call}
 * - the same "capture on a known-correct thread, re-set explicitly before any tenant-scoped work"
 * pattern {@code TenantSchemaIterator} already uses for {@code @Scheduled} jobs (which have no
 * request thread at all), applied here to a reactive-thread-hop instead.
 *
 * <p>Wraps outermost - {@code AgentToolProvider.guardAndLog} wraps this around {@code
 * LoggingToolCallback}, which wraps {@code GuardedToolCallback}, which wraps the raw tool - so
 * every tenant-scoped call anywhere in that chain runs under the correct context: the guardrail's
 * policy check AND the logging decorator's audit write, not just one of the two.
 *
 * <p><b>Also the correct place for the "a failed tool call must never crash the whole chat turn"
 * catch (2026-08-13).</b> Spring AI's Gemini integration ({@code
 * GoogleGenAiChatModel#parseJsonToMap}) unconditionally parses every tool result as JSON when
 * building the next request, and throws - killing the entire HTTP turn (confirmed live via a real
 * {@code JsonParseException}) - on anything that isn't. That describes both a normal MCP tool's
 * prose output AND, worse, an uncaught exception's own message once it reaches whatever Spring AI
 * code turns a failed tool call into request content. This class is deliberately the seam: it wraps
 * {@code LoggingToolCallback} (catch-log-rethrow - see that class's own {@code call()}), so by the
 * time an exception reaches HERE, the failure has ALREADY been persisted to {@code ToolCallLog},
 * recorded as a platform issue, and counted against the tool's circuit breaker - the three real
 * mechanisms an earlier fix broke by catching too early, inside {@code SanitizedMcpToolCallback},
 * before {@code LoggingToolCallback} ever saw the exception (see that class's own "2026-08-13
 * correction" note). Converting to a safe result HERE, after logging, gets both properties at once:
 * real failure tracking (so {@code AgentChatService#firstToolFailureSince}'s auto-replan actually
 * fires) AND a turn that never crashes outright.
 */
public class TenantContextPropagatingToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    /**
     * The tenant scope every {@link #call} runs in, built once from the context handed in at
     * construction. On the chat turn that context is the turn's own (ADR-0022 stage 3: {@code
     * AgentToolProvider#resolveToolsFor(TurnContext, ...)} resolves inside {@code
     * TurnContext#scope()}), not whatever the resolving thread happened to hold.
     */
    private final TenantScope scope;

    /**
     * The calling thread's {@code AgentVersionPin} at tool-resolution time (C1 version stamping) -
     * captured and re-established around {@link #call} for exactly the same reason {@link
     * TenantContext} is: the tool executes on a worker/reactor thread where the request-thread
     * ThreadLocal is absent, and {@code tool_call_log.agent_version_id} would otherwise always
     * stamp null.
     */
    private final java.util.UUID capturedAgentVersionId;

    /**
     * Applied to the final model-facing result, inside the restored tenant scope (2026-09-28: the
     * tenant's LLM-gateway PII policy, see {@code LlmGatewayToolOutputRedactor}). {@code null} =
     * identity.
     */
    private final UnaryOperator<String> outputRedactor;

    /**
     * @param capturedContext the calling thread's {@code TenantContext.current()} at
     *     tool-resolution time, or {@code null} if there genuinely was none (e.g. the "security"
     *     profile is off). {@code null} means every {@link #call} behaves exactly as it did before
     *     this fix existed - deferring to whatever {@code TenantSchemaResolver}'s own default
     *     already does, not a behavior change for that case.
     */
    public TenantContextPropagatingToolCallback(
            ToolCallback delegate, TenantContext capturedContext) {
        this(delegate, capturedContext, null);
    }

    /**
     * @param outputRedactor nullable - run over the final result (success or friendly-failure
     *     alike) while the captured tenant context is in force, so a tenant-schema policy lookup
     *     resolves correctly. Must fail open itself; a throw here is caught and the unredacted
     *     result is returned rather than failing the call.
     */
    public TenantContextPropagatingToolCallback(
            ToolCallback delegate,
            TenantContext capturedContext,
            UnaryOperator<String> outputRedactor) {
        this.delegate = delegate;
        this.scope = TenantScope.of(capturedContext);
        this.capturedAgentVersionId = com.agenticai.core.security.AgentVersionPin.current();
        this.outputRedactor = outputRedactor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return scopedCall(toolInput, null);
    }

    /** Forwards Spring AI's {@link ToolContext} to the wrapped tool instead of dropping it. */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return scopedCall(toolInput, toolContext);
    }

    private String scopedCall(String toolInput, ToolContext toolContext) {
        // TenantScope restores whatever was there before (rather than unconditionally
        // clearing) so this never clobbers a context some other, already-correct
        // mechanism had set on this thread for an unrelated reason; null is a no-op.
        // The version pin gets the same restore-what-was-there treatment.
        java.util.UUID previousPin = com.agenticai.core.security.AgentVersionPin.current();
        if (capturedAgentVersionId != null) {
            com.agenticai.core.security.AgentVersionPin.set(capturedAgentVersionId);
        }
        try {
            return scope.get(() -> redactOutput(safeCall(toolInput, toolContext)));
        } finally {
            if (previousPin != null) {
                com.agenticai.core.security.AgentVersionPin.set(previousPin);
            } else {
                com.agenticai.core.security.AgentVersionPin.clear();
            }
        }
    }

    /**
     * See class javadoc's 2026-08-13 note. {@code delegate.call(...)} has already run through
     * {@code LoggingToolCallback} by the time control reaches here, so a thrown {@link
     * RuntimeException} has already been logged/recorded/circuit-broken (that class's contract is
     * catch-log-rethrow) - this is purely about what the MODEL sees next, not about whether the
     * failure was tracked. {@code RuntimeException} only, matching {@code LoggingToolCallback}'s
     * own catch - an {@link Error} (OOM, etc.) is a real system fault that must still propagate,
     * not get papered over as a friendly tool result.
     *
     * <p>2026-09-28: the exception message is now run through {@link
     * RedactingToolExecutionExceptionProcessor#redact} (credential-shaped text masked, length
     * capped) before it goes to the model - this string is sent to the LLM provider verbatim and
     * persisted in the conversation history.
     */
    private String safeCall(String toolInput, ToolContext toolContext) {
        try {
            String output =
                    toolContext != null
                            ? delegate.call(toolInput, toolContext)
                            : delegate.call(toolInput);
            return McpToolOutputSanitizer.ensureJsonObject(output);
        } catch (RuntimeException e) {
            String detail =
                    RedactingToolExecutionExceptionProcessor.redact(
                            e.getMessage() != null && !e.getMessage().isBlank()
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName());
            return McpToolOutputSanitizer.ensureJsonObject(
                    "This tool call failed: "
                            + detail
                            + ". Explain this to the user in plain language and suggest "
                            + "what to try instead (e.g. a different argument value, or ask them for the correct one) "
                            + "rather than retrying with the exact same input.");
        }
    }

    private String redactOutput(String result) {
        if (outputRedactor == null || result == null) {
            return result;
        }
        try {
            String redacted = outputRedactor.apply(result);
            return redacted != null ? redacted : result;
        } catch (RuntimeException e) {
            return result;
        }
    }
}
