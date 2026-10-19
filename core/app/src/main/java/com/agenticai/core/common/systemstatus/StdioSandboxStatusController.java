package com.agenticai.core.common.systemstatus;

import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxService;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/system/stdio-sandbox} - whether STDIO MCP servers are confined on this host and
 * with what ({@code app.mcp.stdio.sandbox}), for the system-status/doctor view. Reports the mode,
 * the mechanism, whether it actually works here (the preflight probe, cached) and a one-line fix
 * when it does not. No path, env or secret value is included.
 *
 * <p>A separate controller rather than a new field on {@link SystemStatusResponse}: that record is
 * built positionally by the existing controller and its tests, and this probe must not slow or
 * blank the main status panel. Covered by the {@code anyRequest().authenticated()} catch-all like
 * {@code /api/system/status}.
 */
@RestController
@RequestMapping("/api/system/stdio-sandbox")
public class StdioSandboxStatusController {

    private final StdioSandboxService sandbox;

    public StdioSandboxStatusController(StdioSandboxService sandbox) {
        this.sandbox = sandbox;
    }

    @GetMapping
    public StdioSandboxStatus get() {
        return sandbox.status();
    }
}
