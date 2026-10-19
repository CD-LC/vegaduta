package com.agenticai.core.agents.tools.localshell;

import com.agenticai.core.agents.tools.stdiosandbox.SandboxLaunch;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxException;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs the command under the same OS sandbox the STDIO MCP servers use ({@link StdioSandboxService}):
 * filesystem read-only except the workspace folder, credential folders hidden, no network unless the
 * operator listed {@value #SANDBOX_NAME} (or {@code *}) in {@code app.mcp.stdio.sandbox-network-allow}.
 * Refuses, never falls back to a plain subprocess, when the sandbox is off or unavailable.
 */
final class SandboxedShellRunner implements ShellRunner {

    /** The name the sandbox's per-server network allow-list knows this runner by. */
    static final String SANDBOX_NAME = "local-shell";

    private static final String SHELL = "/bin/sh";
    private static final String DEFAULT_PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin";

    private final StdioSandboxService sandbox;
    private final Path workspace;
    private final int maxOutputChars;

    SandboxedShellRunner(StdioSandboxService sandbox, Path workspace, int maxOutputChars) {
        this.sandbox = sandbox;
        this.workspace = workspace.toAbsolutePath().normalize();
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String workspace() {
        return workspace.toString();
    }

    @Override
    public Result run(String command, Duration timeout) throws IOException, InterruptedException {
        Files.createDirectories(workspace);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", DEFAULT_PATH);
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "dumb");
        Optional<SandboxLaunch> wrapped = sandbox.wrap(SANDBOX_NAME, SHELL, List.of("-c", command), env, workspace);
        if (wrapped.isEmpty()) {
            throw new StdioSandboxException(
                    "The terminal is off: it only runs inside the OS sandbox (app.mcp.stdio.sandbox=os or container).");
        }
        SandboxLaunch launch = wrapped.get();
        if (launch.degraded()) {
            throw new StdioSandboxException(
                    "The terminal is off: this host's sandbox does not confine the filesystem (install bubblewrap).");
        }
        List<String> argv = new ArrayList<>();
        argv.add(launch.command());
        argv.addAll(launch.args());
        ProcessBuilder pb = new ProcessBuilder(argv).directory(workspace.toFile()).redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        pb.environment().clear();
        pb.environment().putAll(launch.env());
        Process process = pb.start();
        // Read on this thread, polling: no helper thread to hand a tenant to, and the deadline is
        // enforced even while the command keeps printing.
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        boolean timedOut = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        byte[] buf = new byte[4096];
        try (InputStream in = process.getInputStream()) {
            while (true) {
                int available = in.available();
                if (available > 0) {
                    int n = in.read(buf, 0, Math.min(available, buf.length));
                    if (n < 0) {
                        break;
                    }
                    if (out.length() < maxOutputChars) {
                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (out.length() > maxOutputChars) {
                            out.setLength(maxOutputChars);
                            truncated = true;
                        }
                    } else {
                        truncated = true;
                    }
                } else if (!process.isAlive()) {
                    if (in.available() == 0) {
                        break;
                    }
                } else {
                    Thread.sleep(15);
                }
                if (System.nanoTime() > deadline && process.isAlive()) {
                    timedOut = true;
                    break;
                }
            }
        } finally {
            if (process.isAlive() && !timedOut) {
                // Output closed first: give the command what is left of its time to exit.
                long leftMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                process.waitFor(leftMs, TimeUnit.MILLISECONDS);
            }
            if (process.isAlive()) {
                timedOut = true;
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
        return new Result(timedOut ? -1 : process.exitValue(), out.toString(), truncated, timedOut);
    }
}
