package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.security.TenantContext;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Small single-purpose helpers of {@link AgentToolGuard}, extracted so the guard stays under the
 * file-size cap. Package-private.
 */
final class AgentToolGuardSupport {

    private AgentToolGuardSupport() {
        // utility class
    }

    /**
     * Data-governance audit fix (2026-07-20) - turns {@link TenantContext#getExternalTriggerId()}
     * into the typed {@code UUID} {@link LoggingToolCallback} persists on {@code
     * tool_call_log.mcp_grant_id}. Defensive, not authoritative: a malformed/non-UUID
     * externalTriggerId (a future non-MCP caller of this same generic seam, e.g. a workflow webhook
     * token) degrades to "not attributed" rather than ever throwing out of this per-tool-call hot
     * path - see this field's own javadoc on TenantContext.
     */
    static UUID extractMcpGrantId(TenantContext capturedContext) {
        if (capturedContext == null || capturedContext.getExternalTriggerId() == null) {
            return null;
        }
        try {
            return UUID.fromString(capturedContext.getExternalTriggerId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * #815 Governance follow-up - see {@code AgentAutonomyTier}'s javadoc. {@code SEMI_AUTONOMOUS}
     * returns the tenant-configured {@code maxToolCallsPerTurn} unchanged (today's exact behavior);
     * {@code SUPERVISED} is tighter and {@code FULLY_AUTONOMOUS} looser, by {@code
     * app.guardrails.step-limit.supervised-divisor} / {@code supervised-min-steps} / {@code
     * autonomous-multiplier} (defaults: half, floored at 3 so a supervised agent can still complete
     * a trivial multi-step task; double). The ceiling counts CALLS across the whole turn; see
     * {@link ToolStepLimitProperties} for how it relates to the tool loop's per-invocation round
     * cap.
     */
    static int effectiveMaxStepsFor(AgentToolGuard owner, AgentAutonomyTier tier) {
        ToolStepLimitProperties limits = owner.stepLimits();
        return switch (tier) {
            case SUPERVISED -> limits.supervisedCeiling(owner.maxToolCallsPerTurn);
            case SEMI_AUTONOMOUS -> owner.maxToolCallsPerTurn;
            case FULLY_AUTONOMOUS -> limits.autonomousCeiling(owner.maxToolCallsPerTurn);
        };
    }

    /**
     * The step limits of a turn: the platform's, with the grace the turn's runtime settings
     * resolved for the tenant and the agent ({@code agent.steps.graceCalls}) when they carry one.
     */
    static ToolStepLimitProperties stepLimits(
            AgentToolGuard owner, com.agenticai.core.agents.runtime.AgentRuntimeSettings runtime) {
        ToolStepLimitProperties base = owner.stepLimits();
        return runtime != null
                        && runtime.hasGraceCalls()
                        && runtime.graceCalls() != base.getGraceCalls()
                ? base.withGraceCalls(runtime.graceCalls())
                : base;
    }

    /**
     * The tool-output cap for a turn of the agent: the agent's own default cap with the operator's
     * per-tool caps kept.
     */
    static ToolOutputProperties outputLimits(
            AgentToolGuard owner, com.agenticai.core.agents.runtime.AgentRuntimeSettings runtime) {
        ToolOutputProperties base = owner.outputLimits();
        return runtime == null ? base : base.withMaxChars(runtime.toolOutputMaxChars());
    }

    static com.agenticai.core.agents.runtime.AgentRuntime runtimeResolver(AgentToolGuard owner) {
        ObjectProvider<com.agenticai.core.agents.runtime.AgentRuntime> provider =
                owner.runtimeResolver;
        return provider == null ? null : provider.getIfAvailable();
    }

    static String toolTimeoutMessage(AgentToolGuard owner) {
        com.agenticai.core.agents.runtime.AgentRuntime resolver = runtimeResolver(owner);
        return resolver == null ? null : resolver.toolTimeoutMessage();
    }
}
