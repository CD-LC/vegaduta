package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.McpReviewStatus;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.dto.McpConnectionTestResult;
import com.agenticai.core.mcp.dto.McpConnectionTestResult.SuggestedFix;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP-DOCTOR: the real, bounded connection test behind {@code POST
 * /api/mcp/registrations/{id}/test}. Same guards, transport construction and diagnosis as a normal
 * connect but on a TRANSIENT client (shorter timeout, {@code app.tools.mcp.test-timeout-seconds}, always closed, never registered
 * as live), so testing never disturbs a live connection. Outcome is persisted either way: pass
 * -&gt; usageStatus READY, fail -&gt; FAILED + structured diagnosis. Never throws. Replica routing
 * ({@code ownership.routeTest}) stays in the manager.
 */
final class McpConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionTester.class);

    private final McpRegistryService registryService;
    private final McpRemoteUrlGuard remoteUrlGuard;
    private final McpStdioCommandGuard stdioCommandGuard;
    private final McpStdioSelfHealer selfHealer;
    private final McpStdioSlots stdioSlots;
    private final McpConnectionLifecycle lifecycle;
    private final McpDiagnostics diagnostics;
    private final Supplier<McpTransportFactory> transportFactory;
    private final Function<McpDynamicConfig, ServerParameters> stdioParams;
    private final Supplier<String> tenantKey;

    /**
     * Bounded timeout for the explicit /test flow - deliberately shorter than the live request
     * timeout (a UI is waiting on it).
     */
    private final Supplier<Duration> testTimeout;

    McpConnectionTester(
            McpRegistryService registryService,
            McpRemoteUrlGuard remoteUrlGuard,
            McpStdioCommandGuard stdioCommandGuard,
            McpStdioSelfHealer selfHealer,
            McpStdioSlots stdioSlots,
            McpConnectionLifecycle lifecycle,
            McpDiagnostics diagnostics,
            Supplier<McpTransportFactory> transportFactory,
            Function<McpDynamicConfig, ServerParameters> stdioParams,
            Supplier<String> tenantKey,
            Supplier<Duration> testTimeout) {
        this.registryService = registryService;
        this.remoteUrlGuard = remoteUrlGuard;
        this.stdioCommandGuard = stdioCommandGuard;
        this.selfHealer = selfHealer;
        this.stdioSlots = stdioSlots;
        this.lifecycle = lifecycle;
        this.diagnostics = diagnostics;
        this.transportFactory = transportFactory;
        this.stdioParams = stdioParams;
        this.tenantKey = tenantKey;
        this.testTimeout = testTimeout;
    }

    private McpTransportFactory transports() {
        return transportFactory.get();
    }

    McpConnectionTestResult test(UUID registrationId) {
        McpDynamicConfig config = null;
        RemoteMcpPreProbe.ProbeResult probe = RemoteMcpPreProbe.ProbeResult.skipped();
        StderrTailBuffer stderrTail = new StderrTailBuffer();
        McpSyncClient client = null;
        String testSlotTenant =
                null; // non-null once an in-flight STDIO test slot is held (Pentest H3)
        try {
            config = registryService.dynamicConfigFor(registrationId).orElse(null);
            if (config == null) {
                return McpConnectionTestResult.failure(
                        McpConnectionPhase.HANDSHAKE,
                        "This registration has no dynamic connection config (it is static, or was deleted) - nothing to test.",
                        null);
            }

            // Decrypt-failure visibility - same reasoning as connect()'s
            // identical call right after resolving a fresh config.
            registryService.setCredentialsCorrupted(registrationId, config.credentialsCorrupted());

            // MCP-STDIO-SANDBOXING (2026-08-05) - same review gate as
            // connect(), checked first: a PENDING/REJECTED registration must
            // never spawn a process even for a "just testing" attempt.
            if (config.reviewStatus() == McpReviewStatus.PENDING) {
                return lifecycle.persistBlockedByReviewAndReturnFailure(
                        registrationId,
                        "Awaiting platform review before this custom server can connect. Self-service STDIO "
                                + "(process-launching) MCP servers that aren't from the built-in catalog are reviewed by "
                                + "platform staff before they're allowed to run.");
            }
            if (config.reviewStatus() == McpReviewStatus.REJECTED) {
                String reason = config.reviewRejectionReason();
                return lifecycle.persistBlockedByReviewAndReturnFailure(
                        registrationId,
                        "This registration was rejected by platform review"
                                + (reason == null || reason.isBlank() ? "." : ": " + reason)
                                + " Edit the command/args/env and it will be queued for review again.");
            }

            if (config.isRemote()) {
                // Pentest H2: checkResolved, not check - see rejectIfGuardBlocked's identical
                // comment in connect().
                Optional<String> blocked = remoteUrlGuard.checkResolved(config.url());
                if (blocked.isPresent()) {
                    return lifecycle.persistAndReturnFailure(
                            registrationId,
                            McpConnectionPhase.HTTP,
                            "Connection blocked: " + blocked.get(),
                            "Remote MCP servers must be public http(s) endpoints - URLs pointing inside the platform's own network are not allowed.");
                }
                probe = RemoteMcpPreProbe.probe(config.url(), transports().remoteHeaders(config), config.type());
                // Pentest H2: re-resolve and re-check immediately before the real connect -
                // see connect()'s identical re-check (rebinding window between the check
                // above / probe and the actual socket connect).
                Optional<String> blockedAfterProbe = remoteUrlGuard.checkResolved(config.url());
                if (blockedAfterProbe.isPresent()) {
                    return lifecycle.persistAndReturnFailure(
                            registrationId,
                            McpConnectionPhase.HTTP,
                            "Connection blocked: " + blockedAfterProbe.get(),
                            "This hostname's DNS now resolves somewhere disallowed - it either changed after the "
                                    + "initial check (DNS rebinding) or was never actually reachable at a safe address.");
                }
            } else {
                // env included - same reason as rejectIfGuardBlocked's copy.
                Optional<String> blocked =
                        stdioCommandGuard.check(config.command(), config.args(), config.env());
                if (blocked.isPresent()) {
                    return lifecycle.persistAndReturnFailure(
                            registrationId,
                            McpConnectionPhase.SPAWN,
                            "Connection blocked: " + blocked.get(),
                            "Local MCP servers must be launched via an allowed runtime, with no code-loading flags or "
                                    + "runtime-control environment variables - edit the registration's command, args or env.");
                }
                // Pentest H3: bound concurrent transient test subprocesses per tenant.
                String tenant = tenantKey.get();
                if (!stdioSlots.tryAcquireTestSlot(tenant)) {
                    return lifecycle.persistAndReturnFailure(
                            registrationId,
                            McpConnectionPhase.SPAWN,
                            "Too many connection tests are already running for this workspace (limit "
                                    + stdioSlots.maxPerTenant()
                                    + "). Wait for the others to finish and try again.",
                            "Connection tests each start a real subprocess - they're rate-bounded to protect the platform.");
                }
                testSlotTenant = tenant;
            }
            McpClientTransport transport =
                    config.isRemote()
                            ? transports().buildRemoteTransport(config)
                            : transports().buildStdioTransport(
                                    stdioParams.apply(config),
                                    config.name(),
                                    stderrTail,
                                    config.env() == null ? Map.of() : config.env());
            client = McpClient.sync(transport).requestTimeout(testTimeout.get()).build();
            client.initialize();
            var toolsResult = client.listTools();
            int toolCount =
                    toolsResult == null || toolsResult.tools() == null
                            ? 0
                            : toolsResult.tools().size();
            registryService.markTestPassed(registrationId);
            return McpConnectionTestResult.success(toolCount);
        } catch (Throwable t) {
            Map<String, String> secretEnv = config == null ? Map.of() : config.env();
            McpConnectionDiagnoser.Diagnosis d = diagnostics.diagnose(t, config, probe, stderrTail);
            String detail = McpSecretRedactor.redactSecrets(d.detail(), secretEnv);
            String hint = McpSecretRedactor.redactSecrets(d.hint(), secretEnv);
            log.warn(
                    "MCP connection test for {} failed in phase {}: {}",
                    registrationId,
                    d.phase(),
                    detail);
            // MCP-SELF-HEAL: STDIO only, and only worth trying when there is
            // real stderr to reason about - suggestFix() itself is fail-open
            // (never throws, returns empty on anything inconclusive), so this
            // can never turn a clean failure report into a broken one.
            SuggestedFix suggestedFix =
                    (config != null && !config.isRemote())
                            ? selfHealer
                                    .suggestFix(
                                            config,
                                            McpSecretRedactor.redactSecretsIn(stderrTail.snapshot(), secretEnv))
                                    .orElse(null)
                            : null;
            return lifecycle.persistAndReturnFailure(
                    registrationId, d.phase(), "Connection failed: " + detail, hint, suggestedFix);
        } finally {
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (Throwable closeFailure) {
                    log.warn(
                            "Error closing transient MCP test client for {}",
                            registrationId,
                            closeFailure);
                }
            }
            if (testSlotTenant != null) {
                stdioSlots.releaseTestSlot(testSlotTenant);
            }
        }
    }
}
