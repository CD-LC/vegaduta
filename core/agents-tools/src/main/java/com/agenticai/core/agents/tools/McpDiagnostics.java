package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpConnectionPhase;
import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evidence-to-diagnosis dispatch shared by connect() and test(): maps a failure, the remote
 * pre-probe result and the STDIO stderr tail onto a structured {@link
 * McpConnectionDiagnoser.Diagnosis}. Null-config tolerant (the registry read itself may have
 * failed). Pure apart from the catalog lookup used to name missing required env vars.
 */
final class McpDiagnostics {

    private final McpCatalogService catalogService;

    McpDiagnostics(McpCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    McpConnectionDiagnoser.Diagnosis diagnose(
            Throwable t,
            McpDynamicConfig config,
            RemoteMcpPreProbe.ProbeResult probe,
            StderrTailBuffer stderrTail) {
        if (config == null) {
            return new McpConnectionDiagnoser.Diagnosis(
                    McpConnectionPhase.HANDSHAKE, describeFailure(t), null);
        }
        return config.isRemote()
                ? McpConnectionDiagnoser.diagnoseRemoteFailure(t, probe, config.type())
                : McpConnectionDiagnoser.diagnoseStdioFailure(
                        t,
                        stderrTail == null ? List.of() : stderrTail.snapshot(),
                        missingRequiredEnvVars(config));
    }

    /**
     * MCP-STDIO-SANDBOXING (2026-08-05) - names the SPECIFIC missing env var(s) in a STDIO timeout
     * diagnosis, only when a real schema exists (a catalog-sourced registration). Empty for remotes
     * and for hand-typed custom commands.
     */
    List<String> missingRequiredEnvVars(McpDynamicConfig config) {
        if (config == null || config.isRemote()) {
            return List.of();
        }
        Optional<McpCatalogEntry> entry = catalogService.find(config.name());
        if (entry.isEmpty()) {
            return List.of();
        }
        Map<String, String> env = config.env() == null ? Map.of() : config.env();
        return entry.get().requiredEnvVars().stream()
                .filter(name -> !env.containsKey(name))
                .toList();
    }

    static String describeFailure(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isBlank()) ? t.getClass().getSimpleName() : message;
    }
}
