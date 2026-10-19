package com.agenticai.core.agents.tools.e2b;

/**
 * What an agent response advertises about the data-analysis sandbox: whether data files (CSV / XLSX
 * / JSON / PDF / TXT) can be attached for analysis, and - only when the platform flag is on and
 * they cannot - the user-facing reason from {@link SandboxFileRegistry#unavailableReason}.
 *
 * <p>The reason is deliberately null while {@code app.sandbox.data-analysis.enabled} is false (or
 * the registry bean is absent): a platform that has not switched the feature on must not show users
 * a hint about a feature they cannot enable, so the agent response is unchanged by default.
 *
 * @param available same answer as {@link SandboxFileRegistry#availableFor}
 * @param unavailableReason null when available or when the platform flag is off
 */
public record SandboxDataAvailability(boolean available, String unavailableReason) {

    public static final SandboxDataAvailability OFF = new SandboxDataAvailability(false, null);

    /** One registry call: the reason doubles as the availability answer. */
    public static SandboxDataAvailability of(
            SandboxFileRegistry registry, boolean agentCodeExecutionEnabled) {
        if (registry == null || !registry.isEnabled()) {
            return OFF;
        }
        String reason = registry.unavailableReason(agentCodeExecutionEnabled);
        return reason == null
                ? new SandboxDataAvailability(true, null)
                : new SandboxDataAvailability(false, reason);
    }
}
