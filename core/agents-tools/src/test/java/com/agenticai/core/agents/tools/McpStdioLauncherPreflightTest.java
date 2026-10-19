package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.ObjectProvider;

/** Fail-fast when the image lacks the launcher the chosen STDIO MCP routing needs. */
class McpStdioLauncherPreflightTest {

    private static final Map<String, String> STATICS =
            new TreeMap<>(
                    Map.of("filesystem", "npx", "memory", "npx", "sequential-thinking", "npx"));

    @Test
    void slimImageWithMcpProfileAndNoSidecarFailsWithAnActionableMessage() {
        Optional<String> problem = McpStdioLauncherPreflight.problem(false, STATICS, cmd -> false);

        assertThat(problem).isPresent();
        assertThat(problem.get())
                .contains("'npx' (needed by filesystem, memory, sequential-thinking)")
                .contains("MCP_STDIO_SIDECAR_ENABLED=true")
                .contains("MCP_TOOLCHAIN=full")
                .contains("SPRING_PROFILES_ACTIVE");
    }

    @Test
    void fullImageWithNpxOnPathPasses() {
        assertThat(McpStdioLauncherPreflight.problem(false, STATICS, "npx"::equals)).isEmpty();
    }

    @Test
    void noMcpProfileMeansNothingToCheck() {
        assertThat(McpStdioLauncherPreflight.problem(false, Map.of(), cmd -> false)).isEmpty();
    }

    @Test
    void sidecarModeOnlyNeedsNodeForTheRelayNotNpx() {
        assertThat(McpStdioLauncherPreflight.problem(true, STATICS, Set.of("node")::contains))
                .isEmpty();
        Optional<String> problem = McpStdioLauncherPreflight.problem(true, STATICS, cmd -> false);
        assertThat(problem)
                .hasValueSatisfying(msg -> assertThat(msg).contains("'node' is not on PATH"));
    }

    @Test
    void onlyTheMissingLauncherIsReported() {
        Map<String, String> mixed = new TreeMap<>(Map.of("fetch", "uvx", "memory", "npx"));
        Optional<String> problem = McpStdioLauncherPreflight.problem(false, mixed, "npx"::equals);
        assertThat(problem)
                .hasValueSatisfying(
                        msg ->
                                assertThat(msg)
                                        .contains("'uvx' (needed by fetch)")
                                        .doesNotContain("needed by memory"));
    }

    @Test
    void checkThrowsWhenFailFastAndOnlyWarnsWhenDisabled() {
        McpStdioClientProperties props = new McpStdioClientProperties();
        props.getConnections()
                .put(
                        "memory",
                        new McpStdioClientProperties.Parameters(
                                "definitely-not-a-launcher-" + System.nanoTime(),
                                List.of("-y", "x"),
                                Map.of()));
        @SuppressWarnings("unchecked")
        ObjectProvider<McpStdioClientProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(props);
        McpStdioSidecarProperties sidecarOff = new McpStdioSidecarProperties();

        assertThatThrownBy(() -> new McpStdioLauncherPreflight(provider, sidecarOff, true).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needed by memory");
        new McpStdioLauncherPreflight(provider, sidecarOff, false).check();
    }

    @Test
    void pathLookupFindsExecutablesAndHonoursExtensions(@TempDir Path dir) throws Exception {
        Path plain = Files.createFile(dir.resolve("npx"));
        plain.toFile().setExecutable(true);
        Path windows = Files.createFile(dir.resolve("uvx.CMD"));
        windows.toFile().setExecutable(true);
        String pathEnv = "/nonexistent" + File.pathSeparator + dir;

        assertThat(McpStdioLauncherPreflight.onPath("npx", pathEnv, List.of())).isTrue();
        assertThat(McpStdioLauncherPreflight.onPath("uvx", pathEnv, List.of(".CMD"))).isTrue();
        assertThat(McpStdioLauncherPreflight.onPath("uvx", pathEnv, List.of())).isFalse();
        assertThat(McpStdioLauncherPreflight.onPath("node", pathEnv, List.of())).isFalse();
        assertThat(McpStdioLauncherPreflight.onPath(plain.toString(), null, List.of())).isTrue();
        assertThat(McpStdioLauncherPreflight.onPath("npx", "", List.of())).isFalse();
    }
}
