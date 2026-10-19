package com.agenticai.core.agents.tools;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Startup preflight for STDIO MCP launchers - turns "the image has no MCP toolchain" into ONE clear
 * startup error instead of a cryptic spawn failure (or a 60s lazy-init timeout) the first time an
 * agent reaches for a tool.
 *
 * <ul>
 *   <li><b>Sidecar off</b> (core spawns STDIO servers itself): every static connection in
 *       application-mcp.yml ({@code mcp} profile) needs its launcher ({@code npx} by default) on
 *       PATH. The slim core image ({@code core/Dockerfile} {@code MCP_TOOLCHAIN=none}) has none.
 *   <li><b>Sidecar on</b> ({@code mcp-stdio-sidecar.enabled=true}): core only spawns the trusted
 *       relay {@code node /opt/mcp-stdio-proxy/proxy.js}, so {@code node} must be on PATH (both
 *       image variants carry it).
 * </ul>
 *
 * With no static connections and the sidecar off (e.g. {@code lite}, or any profile set without
 * {@code mcp}) there is nothing to check.
 *
 * <p>{@code app.mcp.stdio.preflight-fail-fast=false} ({@code MCP_STDIO_PREFLIGHT_FAIL_FAST})
 * downgrades the failure to a WARN for operators who deliberately run the {@code mcp} profile
 * without launchers.
 */
@Component
public class McpStdioLauncherPreflight {

    private static final Logger log = LoggerFactory.getLogger(McpStdioLauncherPreflight.class);

    static final String RELAY_LAUNCHER = "node";

    private final ObjectProvider<McpStdioClientProperties> stdioProperties;
    private final McpStdioSidecarProperties sidecarProperties;
    private final boolean failFast;

    public McpStdioLauncherPreflight(
            ObjectProvider<McpStdioClientProperties> stdioProperties,
            McpStdioSidecarProperties sidecarProperties,
            @Value("${app.mcp.stdio.preflight-fail-fast:true}") boolean failFast) {
        this.stdioProperties = stdioProperties;
        this.sidecarProperties = sidecarProperties;
        this.failFast = failFast;
    }

    @PostConstruct
    void check() {
        Map<String, String> staticCommands = new TreeMap<>();
        if (!sidecarProperties.isEnabled()) {
            McpStdioClientProperties props = stdioProperties.getIfAvailable();
            if (props != null) {
                props.toServerParameters()
                        .forEach((name, params) -> staticCommands.put(name, params.getCommand()));
            }
        }
        Optional<String> problem =
                problem(
                        sidecarProperties.isEnabled(),
                        staticCommands,
                        McpStdioLauncherPreflight::onPath);
        if (problem.isEmpty()) {
            return;
        }
        if (failFast) {
            log.error(problem.get());
            throw new IllegalStateException(problem.get());
        }
        log.warn("{} (continuing: app.mcp.stdio.preflight-fail-fast=false)", problem.get());
    }

    /**
     * Pure decision: the startup error message, or empty when every launcher this configuration
     * will spawn is present.
     *
     * @param sidecarEnabled {@code mcp-stdio-sidecar.enabled}
     * @param staticCommands static connection name -> command (ignored when the sidecar is on:
     *     {@link StaticMcpStdioSidecarRouting} relays them)
     * @param launcherOnPath whether a command resolves to an executable
     */
    static Optional<String> problem(
            boolean sidecarEnabled,
            Map<String, String> staticCommands,
            Predicate<String> launcherOnPath) {
        if (sidecarEnabled) {
            if (launcherOnPath.test(RELAY_LAUNCHER)) {
                return Optional.empty();
            }
            return Optional.of(
                    "MCP_STDIO_SIDECAR_ENABLED=true but '"
                            + RELAY_LAUNCHER
                            + "' is not on PATH in this "
                            + "container - core needs it to run the mcp-stdio-proxy relay (/opt/mcp-stdio-proxy/proxy.js) that "
                            + "forwards STDIO MCP servers to mcp-stdio-runner. Use an official core image (both MCP_TOOLCHAIN "
                            + "variants ship node) or install node, or set MCP_STDIO_SIDECAR_ENABLED=false.");
        }
        Map<String, List<String>> missing = new LinkedHashMap<>();
        staticCommands.forEach(
                (name, command) -> {
                    if (command == null || command.isBlank() || !launcherOnPath.test(command)) {
                        missing.computeIfAbsent(String.valueOf(command), k -> new ArrayList<>())
                                .add(name);
                    }
                });
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder which = new StringBuilder();
        missing.forEach(
                (command, names) -> {
                    if (which.length() > 0) {
                        which.append("; ");
                    }
                    which.append('\'')
                            .append(command)
                            .append("' (needed by ")
                            .append(String.join(", ", names))
                            .append(')');
                });
        return Optional.of(
                "The 'mcp' Spring profile is active, so core spawns the static STDIO MCP servers itself, "
                        + "but these launchers are not on PATH: "
                        + which
                        + ". This core image has no MCP toolchain "
                        + "(core/Dockerfile MCP_TOOLCHAIN=none, the default). Pick one: (1) run mcp-stdio-runner and set "
                        + "MCP_STDIO_SIDECAR_ENABLED=true (compose: `scripts/vegaduta up --profile tools`, or `browser` for "
                        + "puppeteer/playwright; helm: global.components.mcpStdioRunner=true); (2) use the core-full image "
                        + "(MCP_TOOLCHAIN=full); (3) drop 'mcp' from SPRING_PROFILES_ACTIVE. "
                        + "MCP_STDIO_PREFLIGHT_FAIL_FAST=false turns this into a warning.");
    }

    /** PATH lookup mirroring what ProcessBuilder will do (plus PATHEXT on Windows). */
    static boolean onPath(String command) {
        return onPath(command, System.getenv("PATH"), windowsExtensions());
    }

    static boolean onPath(String command, String pathEnv, List<String> extensions) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            if (command.contains("/") || command.contains("\\")) {
                return isExecutableFile(Path.of(command));
            }
            if (pathEnv == null || pathEnv.isBlank()) {
                return false;
            }
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                if (isExecutableFile(Path.of(dir, command))) {
                    return true;
                }
                for (String ext : extensions) {
                    if (isExecutableFile(Path.of(dir, command + ext))) {
                        return true;
                    }
                }
            }
        } catch (InvalidPathException e) {
            return false;
        }
        return false;
    }

    private static boolean isExecutableFile(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private static List<String> windowsExtensions() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of();
        }
        String pathExt = System.getenv("PATHEXT");
        return List.of(
                (pathExt == null || pathExt.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathExt)
                        .split(";"));
    }
}
