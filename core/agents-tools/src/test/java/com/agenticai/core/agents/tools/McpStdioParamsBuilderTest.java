package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.McpStdioCommandGuard;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import io.modelcontextprotocol.client.transport.ServerParameters;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;

/**
 * SEC-030: the in-core relay ({@code node proxy.js}) must never run with the tenant's env as its
 * own process environment - the tenant env travels as JSON data ({@code
 * MCP_SIDECAR_TARGET_ENV_JSON}) that proxy.js puts in the runner's control frame.
 */
class McpStdioParamsBuilderTest {

    private static final Set<String> CONTROL_VARS =
            Set.of(
                    "MCP_SIDECAR_URL",
                    "MCP_SIDECAR_SECRET",
                    "MCP_SIDECAR_TARGET_COMMAND",
                    "MCP_SIDECAR_TARGET_ARGS_JSON",
                    "MCP_SIDECAR_TARGET_ENV_JSON");

    private static McpStdioSidecarProperties sidecar() {
        McpStdioSidecarProperties props = new McpStdioSidecarProperties();
        props.setEnabled(true);
        props.setAddress("mcp-stdio-runner:8097");
        props.setSharedSecret("s3cr3t");
        return props;
    }

    private static Map<String, String> json(String value) throws Exception {
        return com.agenticai.core.json.JsonMappers.plain()
                .readValue(value, new TypeReference<Map<String, String>>() {});
    }

    @Test
    void relayProcessEnvIsOnlyControlVarsPlusTheSdkDefaults(@TempDir Path base) throws Exception {
        McpStdioParamsBuilder builder =
                new McpStdioParamsBuilder(new McpStdioCommandGuard(""), sidecar(), base);
        McpDynamicConfig config =
                new McpDynamicConfig(
                        UUID.randomUUID(),
                        "github",
                        McpServerType.STDIO,
                        "npx",
                        List.of("-y", "@modelcontextprotocol/server-github"),
                        Map.of("GITHUB_TOKEN", "ghp_secret", "API_KEY", "k"),
                        null);

        ServerParameters params = builder.stdioParamsFor(config);

        Set<String> sdkDefaults = ServerParameters.builder("node").build().getEnv().keySet();
        Set<String> extra = new HashSet<>(params.getEnv().keySet());
        extra.removeAll(sdkDefaults);
        assertThat(extra).containsExactlyInAnyOrderElementsOf(CONTROL_VARS);
        assertThat(params.getEnv())
                .doesNotContainKeys("GITHUB_TOKEN", "API_KEY", "npm_config_cache", "UV_CACHE_DIR");
        assertThat(params.getEnv().values()).doesNotContain("ghp_secret");

        assertThat(json(params.getEnv().get("MCP_SIDECAR_TARGET_ENV_JSON")))
                .isEqualTo(Map.of("GITHUB_TOKEN", "ghp_secret", "API_KEY", "k"));
    }

    @Test
    void tenantEnvCannotOverrideControlVarsOrSmuggleRunnerOwnedNames() throws Exception {
        ServerParameters params =
                McpStdioParamsBuilder.wrapForSidecar(
                        sidecar(),
                        "npx",
                        List.of("-y", "pkg"),
                        Map.of(
                                "MCP_SIDECAR_SECRET",
                                "attacker",
                                "mcp_sidecar_url",
                                "evil:1",
                                "Path",
                                "/evil",
                                "home",
                                "/evil",
                                "NPM_CONFIG_CACHE",
                                "/evil",
                                "KEEP_ME",
                                "v"));

        assertThat(params.getEnv())
                .containsEntry("MCP_SIDECAR_SECRET", "s3cr3t")
                .containsEntry("MCP_SIDECAR_URL", "mcp-stdio-runner:8097")
                .doesNotContainKeys("mcp_sidecar_url", "KEEP_ME", "NPM_CONFIG_CACHE");
        assertThat(json(params.getEnv().get("MCP_SIDECAR_TARGET_ENV_JSON")))
                .isEqualTo(Map.of("KEEP_ME", "v"));
    }

    @Test
    void directSpawnModeStillGetsTheTenantEnvAndIsolatedCaches(@TempDir Path base) {
        McpStdioSidecarProperties off = new McpStdioSidecarProperties();
        McpStdioParamsBuilder builder =
                new McpStdioParamsBuilder(new McpStdioCommandGuard(""), off, base);
        UUID id = UUID.randomUUID();
        McpDynamicConfig config =
                new McpDynamicConfig(
                        id,
                        "x",
                        McpServerType.STDIO,
                        "uvx",
                        List.of("some-pkg"),
                        Map.of("API_KEY", "k"),
                        null);

        ServerParameters params = builder.stdioParamsFor(config);

        // java:S5443 follow-up: under the configured owner-only base, never a predictable /tmp one.
        assertThat(params.getEnv())
                .containsEntry("API_KEY", "k")
                .containsEntry("npm_config_cache", under(base, "npm-cache", id))
                .containsEntry("UV_CACHE_DIR", under(base, "uv-cache", id))
                .containsEntry("UV_TOOL_DIR", under(base, "uv-tools", id))
                .containsEntry("UV_TOOL_BIN_DIR", under(base, "uv-bin", id));
    }

    private static String under(Path base, String root, UUID id) {
        return base.resolve(root).resolve(id.toString()).toString();
    }

    @Test
    void resolvePackageCacheDirCreatesAnOwnerOnlyDirectory(@TempDir Path tmp) throws Exception {
        String configured = tmp.resolve("mcp-cache").toString();

        Path resolved = McpStdioParamsBuilder.resolvePackageCacheDir(configured);

        assertThat(resolved).isDirectory();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(resolved))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void resolvePackageCacheDirRefusesAnExplicitPathThatIsNotADirectory(@TempDir Path tmp)
            throws Exception {
        String file = Files.writeString(tmp.resolve("not-a-dir"), "x").toString();

        assertThatThrownBy(() -> McpStdioParamsBuilder.resolvePackageCacheDir(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_PACKAGE_CACHE_DIR");
    }
}
