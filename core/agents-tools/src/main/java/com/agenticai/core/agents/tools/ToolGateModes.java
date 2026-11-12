package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.tools.search.CallToolToolCallback;
import com.agenticai.core.agents.tools.search.SearchToolsToolCallback;
import com.agenticai.core.guardrails.ApprovalGateMode;
import java.util.UUID;

/** Maps an autonomy tier and tool to the approval gate mode, for {@link AgentToolGuard}. */
final class ToolGateModes {

    private ToolGateModes() {}

    static ApprovalGateMode resolve(
            AgentToolGovernance governance, AgentAutonomyTier tier, String toolName, UUID agentId) {
        // search_tools and call_tool are dispatch plumbing, never gated themselves: a parked call
        // could not be
        // replayed (approval replay resolves tools by exact name without deferral, so neither
        // exists there) and
        // the deferred target already carries its own guard chain and approval gate, so gating the
        // dispatcher
        // would ask a human twice. The classification dispatch gate (DENY) still runs on the
        // wrapper.
        if (SearchToolsToolCallback.NAME.equals(toolName)
                || CallToolToolCallback.NAME.equals(toolName)) {
            return ApprovalGateMode.NEVER_GATE;
        }
        ApprovalGateMode mode = forTier(tier);
        if (mode == ApprovalGateMode.ALWAYS_GATE
                && governance != null
                && governance.supervisedAutoAllowsReadOnly(agentId)) {
            mode = ApprovalGateMode.GATE_UNLESS_READ_ONLY;
        }
        // The pinned set consults the install-level shell-approval dial when governance is wired;
        // a null governance (hand-built fixture) keeps the static set exactly as before.
        boolean pinned =
                governance != null
                        ? governance.alwaysConfirm(toolName)
                        : AgentToolGuard.ALWAYS_CONFIRM_TOOLS.contains(toolName);
        if (mode == ApprovalGateMode.NEVER_GATE && pinned) {
            return ApprovalGateMode.POLICY_DEFAULT;
        }
        return mode;
    }

    /**
     * #815 Governance follow-up - see {@code AgentAutonomyTier}'s javadoc for the mapping's
     * rationale.
     */
    static ApprovalGateMode forTier(AgentAutonomyTier tier) {
        return switch (tier) {
            case SUPERVISED -> ApprovalGateMode.ALWAYS_GATE;
            case SEMI_AUTONOMOUS -> ApprovalGateMode.POLICY_DEFAULT;
            case FULLY_AUTONOMOUS -> ApprovalGateMode.NEVER_GATE;
        };
    }
}
