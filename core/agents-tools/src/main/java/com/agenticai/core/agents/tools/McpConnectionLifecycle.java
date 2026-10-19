package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.dto.McpConnectionTestResult;
import com.agenticai.core.mcp.dto.McpConnectionTestResult.SuggestedFix;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The persisted half of the connection state machine (connecting / connected / errored / blocked
 * by review): every transition that writes registration status goes through here, so the secret
 * redaction, the failure metric and the self-heal suggestion are applied identically on the
 * connect, health-check and test paths. The in-memory half (live clients, tool callbacks, STDIO
 * slots) stays in {@link DynamicMcpConnectionManager}.
 */
final class McpConnectionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionLifecycle.class);

    private final McpRegistryService registryService;
    private final MeterRegistry meterRegistry;
    private final McpStdioSelfHealer selfHealer;
    private final McpDiagnostics diagnostics;

    McpConnectionLifecycle(
            McpRegistryService registryService,
            MeterRegistry meterRegistry,
            McpStdioSelfHealer selfHealer,
            McpDiagnostics diagnostics) {
        this.registryService = registryService;
        this.meterRegistry = meterRegistry;
        this.selfHealer = selfHealer;
        this.diagnostics = diagnostics;
    }

    /**
     * The catch-block of connect(): persists a structured, secret-redacted diagnosis (plus the
     * failure metric and, for STDIO, a self-heal suggestion). Never throws - persisting the error
     * must not escape connect().
     */
    void recordConnectFailure(
            UUID registrationId,
            McpDynamicConfig config,
            RemoteMcpPreProbe.ProbeResult probe,
            StderrTailBuffer tail,
            Throwable t) {
        // Credential isolation: connectionError IS returned to the tenant's own frontend and
        // persisted in plain text, so redact this registration's configured secret values.
        Map<String, String> secretEnv = config == null ? Map.of() : config.env();
        McpConnectionDiagnoser.Diagnosis diagnosis = diagnostics.diagnose(t, config, probe, tail);
        // Alertable signal, tagged by the bounded diagnosis phase (never registration/tenant ids).
        meterRegistry
                .counter(
                        "mcp.connection.failure",
                        "source",
                        "dynamic",
                        "phase",
                        diagnosis.phase() == null ? "UNKNOWN" : diagnosis.phase().name())
                .increment();
        // REDACT BEFORE THE LLM SEES IT: the self-healer ships this stderr to a model provider as
        // prompt text, and the env now holds resolved secret plaintext (vault expansion).
        SuggestedFix suggestedFix =
                (config != null && !config.isRemote() && tail != null)
                        ? selfHealer
                                .suggestFix(
                                        config,
                                        DynamicMcpConnectionManager.redactSecretsIn(
                                                tail.snapshot(), secretEnv))
                                .orElse(null)
                        : null;
        try {
            registryService.markErrorDiagnosis(
                    registrationId,
                    "Connection failed: "
                            + DynamicMcpConnectionManager.redactSecrets(
                                    diagnosis.detail(), secretEnv),
                    diagnosis.phase(),
                    DynamicMcpConnectionManager.redactSecrets(diagnosis.hint(), secretEnv),
                    suggestedFix);
        } catch (Throwable markErrorFailure) {
            log.error(
                    "MCP dynamic connection {} failed to connect AND failed to persist the failure status via markError - "
                            + "registration may be left in a stale CONNECTING state",
                    registrationId,
                    markErrorFailure);
        }
    }

    /** Health-check death detection: counts the loss and persists the "Connection lost" row. */
    void recordConnectionLost(UUID registrationId, Throwable t) {
        // Same credential-isolation posture as recordConnectFailure: a ping failure can echo a
        // bad credential just like a fresh connect attempt.
        Map<String, String> secretValues =
                registryService
                        .dynamicConfigFor(registrationId)
                        .map(McpDynamicConfig::env)
                        .orElse(Map.of());
        registryService.markErrorDiagnosis(
                registrationId,
                "Connection lost: "
                        + DynamicMcpConnectionManager.redactSecrets(
                                McpDiagnostics.describeFailure(t), secretValues),
                McpConnectionPhase.HANDSHAKE,
                "The server was connected but stopped responding - its process may have died or the remote endpoint went away. Use Test connection to re-verify, then re-enable.");
    }

    void countConnectionLost() {
        meterRegistry.counter("mcp.connection.lost").increment();
    }

    /**
     * Persists a FAILED diagnosis (best-effort - persistence failure never masks the result) and
     * returns it in the frozen /test contract shape.
     */
    McpConnectionTestResult persistAndReturnFailure(
            UUID registrationId, McpConnectionPhase phase, String detail, String hint) {
        return persistAndReturnFailure(registrationId, phase, detail, hint, null);
    }

    McpConnectionTestResult persistAndReturnFailure(
            UUID registrationId,
            McpConnectionPhase phase,
            String detail,
            String hint,
            SuggestedFix suggestedFix) {
        try {
            registryService.markErrorDiagnosis(registrationId, detail, phase, hint, suggestedFix);
        } catch (Throwable persistFailure) {
            log.warn("Could not persist MCP test diagnosis for {}", registrationId, persistFailure);
        }
        return suggestedFix == null
                ? McpConnectionTestResult.failure(phase, detail, hint)
                : McpConnectionTestResult.failureWithSuggestedFix(
                        phase, detail, hint, suggestedFix);
    }

    /**
     * The review-gate counterpart of {@link #persistAndReturnFailure}: routes through {@code
     * markConnectionBlockedByReview}, so a blocked test never touches the auto-retry backoff
     * bookkeeping. SPAWN is the closest existing phase - the gate refuses before any process exists.
     */
    McpConnectionTestResult persistBlockedByReviewAndReturnFailure(
            UUID registrationId, String hint) {
        try {
            registryService.markConnectionBlockedByReview(registrationId, hint);
        } catch (Throwable persistFailure) {
            log.warn(
                    "Could not persist MCP review-block diagnosis for {}",
                    registrationId,
                    persistFailure);
        }
        return McpConnectionTestResult.failure(
                McpConnectionPhase.SPAWN, "Connection blocked: platform review required.", hint);
    }
}
