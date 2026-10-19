package com.agenticai.core.agents.tools.localshell;

import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxService;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxStatus;
import com.agenticai.core.agentspi.WorkspaceToolSource;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Offers {@code local_shell_read} and {@code local_shell} (the Assistant's terminal) to every agent
 * of a single-user install, through the same {@link WorkspaceToolSource} port the Google tools use,
 * so each tool is wrapped in the tool guard like every other tool.
 *
 * <p>Offered only when ALL of: {@code app.assistant.local-shell.enabled=true}; this is a one-person
 * install ({@code personal} profile, or the explicit {@code
 * app.assistant.local-shell.acknowledge-shared-host=true}, because on a shared server any tenant's
 * agent would run commands on the host); and the OS sandbox is on, available and confining the
 * filesystem. Otherwise {@link #rawTools()} is empty and nothing about a turn changes.
 */
@Component
public class LocalShellToolSource implements WorkspaceToolSource {

    private static final Logger log = LoggerFactory.getLogger(LocalShellToolSource.class);

    private final boolean enabled;
    private final boolean singleUser;
    private final StdioSandboxService sandbox;
    private final ShellRunner runner;
    private final boolean usesRunner;
    private final boolean runnerSecretMissing;
    private final boolean approvalRequired;
    private final List<ToolCallback> tools;
    private volatile String lastRefusal;

    public LocalShellToolSource(
            StdioSandboxService sandbox,
            Environment environment,
            @Value("${app.assistant.local-shell.enabled:false}") boolean enabled,
            @Value("${app.assistant.local-shell.acknowledge-shared-host:false}") boolean acknowledgeSharedHost,
            @Value("${app.assistant.local-shell.workspace:${user.home}/vegaduta-workspace}") String workspace,
            @Value("${app.assistant.local-shell.default-timeout-seconds:60}") int defaultTimeoutSeconds,
            @Value("${app.assistant.local-shell.max-timeout-seconds:300}") int maxTimeoutSeconds,
            @Value("${app.assistant.local-shell.max-output-chars:20000}") int maxOutputChars,
            @Value("${app.assistant.local-shell.runner-url:}") String runnerUrl,
            @Value("${app.assistant.local-shell.runner-secret:}") String runnerSecret,
            @Value("${app.assistant.local-shell.approval-required:true}") boolean approvalRequired) {
        this.sandbox = sandbox;
        this.enabled = enabled;
        this.approvalRequired = approvalRequired;
        this.singleUser = acknowledgeSharedHost || environment.acceptsProfiles(Profiles.of("personal"));
        // A configured workspace-runner (services/workspace-runner) replaces the OS sandbox: that is
        // how the terminal works when core itself runs in a container, where there is no host
        // sandbox to use.
        this.usesRunner = runnerUrl != null && !runnerUrl.isBlank();
        this.runnerSecretMissing = usesRunner && (runnerSecret == null || runnerSecret.isBlank());
        this.runner =
                usesRunner
                        ? new RunnerShellRunner(
                                runnerUrl.strip(),
                                runnerSecret == null ? "" : runnerSecret.strip(),
                                workspace,
                                Math.max(1_000, maxOutputChars),
                                Duration.ofSeconds(Math.max(1, maxTimeoutSeconds)))
                        : new SandboxedShellRunner(sandbox, Path.of(workspace), Math.max(1_000, maxOutputChars));
        this.tools =
                List.of(
                        new LocalShellToolCallback(runner, true, defaultTimeoutSeconds, maxTimeoutSeconds),
                        new LocalShellToolCallback(runner, false, defaultTimeoutSeconds, maxTimeoutSeconds, approvalRequired));
    }

    @PostConstruct
    void report() {
        if (!enabled) {
            return;
        }
        String refusal = refusal();
        if (refusal == null) {
            log.info(
                    "Assistant terminal on: local_shell_read and local_shell run in {}, workspace {}"
                            + (approvalRequired
                                    ? " (each command waits for approval)"
                                    : " (commands run without per-call approval)"),
                    usesRunner ? "the workspace-runner container" : "the OS sandbox",
                    runner.workspace());
        } else {
            log.warn("app.assistant.local-shell.enabled=true but the terminal is not offered: {}", refusal);
        }
    }

    /** The terminal is offered to agents right now. */
    public boolean offered() {
        return refusal() == null;
    }

    /** Why the terminal is not offered, or null when it is. */
    String refusal() {
        if (!enabled) {
            return "it is switched off (app.assistant.local-shell.enabled)";
        }
        if (!singleUser) {
            return "this is not a single-user install (personal profile or app.assistant.local-shell.acknowledge-shared-host=true)";
        }
        if (usesRunner) {
            return runnerSecretMissing ? "the workspace runner has no secret (app.assistant.local-shell.runner-secret)" : null;
        }
        if (!sandbox.enabled()) {
            return "the OS sandbox is off (app.mcp.stdio.sandbox=none)";
        }
        StdioSandboxStatus status = sandbox.status();
        if (!status.available()) {
            return "the OS sandbox is unavailable on this host: " + status.detail();
        }
        if (status.degraded()) {
            return "this host's sandbox does not confine the filesystem (install bubblewrap)";
        }
        return null;
    }

    /** The dev servers the Assistant has running (workspace-runner only); empty elsewhere. */
    public List<ShellRunner.Preview> previews() throws java.io.IOException {
        return usesRunner && refusal() == null ? runner.previews() : List.of();
    }

    public boolean stopPreview(int port) throws java.io.IOException {
        return usesRunner && refusal() == null && runner.stopPreview(port);
    }

    @Override
    public List<ToolCallback> rawTools() {
        String refusal = refusal();
        if (refusal != null) {
            if (enabled && !refusal.equals(lastRefusal)) {
                lastRefusal = refusal;
                log.warn("Assistant terminal not offered: {}", refusal);
            }
            return List.of();
        }
        lastRefusal = null;
        return tools;
    }
}
