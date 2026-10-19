package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxMode;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxProperties;
import com.agenticai.core.agents.tools.stdiosandbox.StdioSandboxService;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import io.modelcontextprotocol.client.transport.ServerParameters;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * app.mcp.stdio.sandbox in {@link McpStdioParamsBuilder}: off is byte-identical to before, on wraps
 * the SAME approved command, and the guard still runs first.
 */
class McpStdioParamsSandboxTest {

    private static McpDynamicConfig config(String command, List<String> args, Map<String, String> env) {
        return new McpDynamicConfig(UUID.randomUUID(), "github", McpServerType.STDIO, command, args, env, null);
    }

    private static McpStdioSidecarProperties sidecarOff() {
        return new McpStdioSidecarProperties();
    }

    /** A real service whose mechanism probes succeed on this machine only when it is a Mac. */
    private static StdioSandboxService service(StdioSandboxMode mode, boolean required) {
        StdioSandboxProperties p = new StdioSandboxProperties();
        p.setSandbox(mode);
        p.setSandboxRequired(required);
        return new StdioSandboxService(p, true);
    }

    @Test
    void sandboxNoneIsIdenticalToTheBuilderWithoutAnySandbox(@TempDir Path base) {
        McpDynamicConfig c = config("npx", List.of("-y", "pkg"), Map.of("K", "v"));
        ServerParameters plain =
                new McpStdioParamsBuilder(new McpStdioCommandGuard(""), sidecarOff(), base).stdioParamsFor(c);
        ServerParameters none =
                new McpStdioParamsBuilder(
                                new McpStdioCommandGuard(""),
                                sidecarOff(),
                                base,
                                service(StdioSandboxMode.NONE, true))
                        .stdioParamsFor(c);
        assertThat(none.getCommand()).isEqualTo(plain.getCommand());
        assertThat(none.getArgs()).isEqualTo(plain.getArgs());
        assertThat(none.getEnv()).isEqualTo(plain.getEnv());
    }

    @Test
    void theGuardStillRejectsADisallowedCommandBeforeAnySandboxWrapping(@TempDir Path base) {
        McpStdioParamsBuilder b =
                new McpStdioParamsBuilder(
                        new McpStdioCommandGuard(""), sidecarOff(), base, service(StdioSandboxMode.OS, true));
        assertThatThrownBy(() -> b.stdioParamsFor(config("bash", List.of("-c", "id"), Map.of())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> b.stdioParamsFor(config("node", List.of("-e", "1"), Map.of())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void sidecarModeTakesPrecedenceAndIsNeverDoubleWrapped(@TempDir Path base) {
        McpStdioSidecarProperties sidecar = new McpStdioSidecarProperties();
        sidecar.setEnabled(true);
        sidecar.setSharedSecret("s");
        ServerParameters p =
                new McpStdioParamsBuilder(
                                new McpStdioCommandGuard(""), sidecar, base, service(StdioSandboxMode.CONTAINER, true))
                        .stdioParamsFor(config("npx", List.of("-y", "pkg"), Map.of()));
        assertThat(p.getCommand()).isEqualTo("node");
        assertThat(p.getArgs()).containsExactly("/opt/mcp-stdio-proxy/proxy.js");
    }

    @Test
    void anUnavailableRequiredSandboxRefusesTheSpawnWithAUserSafeMessage(@TempDir Path base) {
        // CONTAINER with no docker/podman daemon reachable in the test environment is the
        // "unavailable" case; skip if this machine happens to have a working one.
        StdioSandboxService s = service(StdioSandboxMode.CONTAINER, true);
        org.junit.jupiter.api.Assumptions.assumeFalse(s.status().available(), "a container runtime works here");
        McpStdioParamsBuilder b =
                new McpStdioParamsBuilder(new McpStdioCommandGuard(""), sidecarOff(), base, s);
        assertThatThrownBy(() -> b.stdioParamsFor(config("npx", List.of("-y", "pkg"), Map.of("K", "secret-value"))))
                .hasMessageContaining("refused")
                .hasMessageNotContaining("secret-value")
                .hasMessageNotContaining(base.toString());
    }

    @Test
    void anUnavailableOptionalSandboxFallsBackToThePlainSpawn(@TempDir Path base) {
        StdioSandboxService s = service(StdioSandboxMode.CONTAINER, false);
        org.junit.jupiter.api.Assumptions.assumeFalse(s.status().available(), "a container runtime works here");
        McpDynamicConfig c = config("npx", List.of("-y", "pkg"), Map.of());
        ServerParameters p =
                new McpStdioParamsBuilder(new McpStdioCommandGuard(""), sidecarOff(), base, s).stdioParamsFor(c);
        assertThat(p.getCommand()).isEqualTo("npx");
    }
}
