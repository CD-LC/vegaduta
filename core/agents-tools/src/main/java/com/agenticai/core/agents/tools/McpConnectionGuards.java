package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import java.util.Optional;
import java.util.UUID;

/**
 * The connect-time guard chain, in the order {@link DynamicMcpConnectionManager#connect} applies
 * it: platform review gate, then the save-time SSRF / launcher guards re-checked, then (for
 * remotes, after the pre-probe) a freshly-resolved DNS re-check. Each method persists a
 * structured refusal and returns true when the connection must not be attempted. The review gate
 * routes through {@code markConnectionBlockedByReview}, deliberately NOT the markError/backoff path.
 */
final class McpConnectionGuards {

    private final McpRegistryService registryService;
    private final McpRemoteUrlGuard remoteUrlGuard;
    private final McpStdioCommandGuard stdioCommandGuard;

    McpConnectionGuards(
            McpRegistryService registryService,
            McpRemoteUrlGuard remoteUrlGuard,
            McpStdioCommandGuard stdioCommandGuard) {
        this.registryService = registryService;
        this.remoteUrlGuard = remoteUrlGuard;
        this.stdioCommandGuard = stdioCommandGuard;
    }

    /**
     * MCP-STDIO-SANDBOXING (2026-08-05): the platform-staff review gate. True when this
     * registration is PENDING or REJECTED - a structured refusal has been persisted and the
     * connection must not be attempted. Always false for remote registrations and catalog-sourced
     * STDIO (both NOT_REQUIRED at register() time).
     */
    boolean rejectIfReviewBlocked(UUID registrationId, McpDynamicConfig config) {
        if (config.reviewStatus() == McpReviewStatus.PENDING) {
            registryService.markConnectionBlockedByReview(
                    registrationId,
                    "Awaiting platform review before this custom server can connect. Self-service STDIO (process-"
                            + "launching) MCP servers that aren't from the built-in catalog are reviewed by platform staff "
                            + "before they're allowed to run - this protects the shared platform from a malicious or "
                            + "compromised third-party package.");
            return true;
        }
        if (config.reviewStatus() == McpReviewStatus.REJECTED) {
            String reason = config.reviewRejectionReason();
            registryService.markConnectionBlockedByReview(
                    registrationId,
                    "This registration was rejected by platform review"
                            + (reason == null || reason.isBlank() ? "." : ": " + reason)
                            + " Edit the command/args/env and it will be queued for review again.");
            return true;
        }
        return false;
    }

    /**
     * MCP-DOCTOR: connect-time re-check of the save-time guards. True when a guard blocked this
     * config - a structured FAILED diagnosis has been persisted.
     */
    boolean rejectIfGuardBlocked(UUID registrationId, McpDynamicConfig config) {
        if (config.isRemote()) {
            // Pentest H2 (DNS rebinding): checkResolved (not check) - resolves the hostname
            // and checks EVERY returned address, not just the string.
            Optional<String> blocked = remoteUrlGuard.checkResolved(config.url());
            if (blocked.isPresent()) {
                registryService.markErrorDiagnosis(
                        registrationId,
                        "Connection blocked: " + blocked.get(),
                        McpConnectionPhase.HTTP,
                        "Remote MCP servers must be public http(s) endpoints - URLs pointing inside the platform's own network are not allowed.");
                return true;
            }
        } else {
            // env included (security audit 2026-07-29): a row saved before the env rule existed,
            // or written around McpRegistryService, is caught here with a real diagnosis.
            Optional<String> blocked =
                    stdioCommandGuard.check(config.command(), config.args(), config.env());
            if (blocked.isPresent()) {
                registryService.markErrorDiagnosis(
                        registrationId,
                        "Connection blocked: " + blocked.get(),
                        McpConnectionPhase.SPAWN,
                        "Local MCP servers must be launched via an allowed runtime, with no code-loading flags or "
                                + "runtime-control environment variables - edit the registration's command, args or env.");
                return true;
            }
        }
        return false;
    }

    /**
     * Pentest H2 (DNS rebinding): a second, freshly-resolved re-check of a remote registration's
     * URL, invoked immediately before the real SDK connect. Persists a FAILED diagnosis and returns
     * true if this later check now fails.
     */
    boolean rejectIfResolvedBlockedNow(UUID registrationId, McpDynamicConfig config) {
        Optional<String> blocked = remoteUrlGuard.checkResolved(config.url());
        if (blocked.isPresent()) {
            registryService.markErrorDiagnosis(
                    registrationId,
                    "Connection blocked: " + blocked.get(),
                    McpConnectionPhase.HTTP,
                    "This hostname's DNS now resolves somewhere disallowed - it either changed after the initial "
                            + "check (DNS rebinding) or was never actually reachable at a safe address. Point this "
                            + "registration at a stable public endpoint.");
            return true;
        }
        return false;
    }
}
