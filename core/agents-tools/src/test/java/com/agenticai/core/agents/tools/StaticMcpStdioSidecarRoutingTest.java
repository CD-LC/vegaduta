package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.transport.ServerParameters;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;

/**
 * Routing decision for the static (application-mcp.yml) STDIO built-ins: relayed through
 * mcp-stdio-runner when mcp-stdio-sidecar.enabled=true, untouched (local spawn) otherwise.
 */
class StaticMcpStdioSidecarRoutingTest {

    private static final ResolvableType TRANSPORT_LIST =
            ResolvableType.forClassWithGenerics(List.class, NamedClientMcpTransport.class);

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(StdioTransportAutoConfiguration.class))
                    .withUserConfiguration(
                            McpStdioSidecarConfiguration.class, StaticMcpStdioSidecarRouting.class)
                    .withPropertyValues(
                            "spring.ai.mcp.client.stdio.connections.sequential-thinking.command=npx",
                            "spring.ai.mcp.client.stdio.connections.sequential-thinking.args[0]=-y",
                            "spring.ai.mcp.client.stdio.connections.sequential-thinking.args[1]=@modelcontextprotocol/server-sequential-thinking",
                            "spring.ai.mcp.client.stdio.connections.memory.command=npx.cmd",
                            "spring.ai.mcp.client.stdio.connections.memory.args[0]=-y",
                            "spring.ai.mcp.client.stdio.connections.memory.args[1]=@modelcontextprotocol/server-memory");

    @Test
    void sidecarOffLeavesTheStartersLocalSpawnTransportsUntouched() {
        runner.withPropertyValues("mcp-stdio-sidecar.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).doesNotHaveBean(StaticMcpStdioSidecarRouting.class);
                            @SuppressWarnings("unchecked")
                            List<NamedClientMcpTransport> local =
                                    (List<NamedClientMcpTransport>) ctx.getBean("stdioTransports");
                            assertThat(local)
                                    .extracting(NamedClientMcpTransport::name)
                                    .containsExactlyInAnyOrder("sequential-thinking", "memory");
                        });
    }

    @Test
    void sidecarOnMovesEveryStaticConnectionToTheRelayUnderTheSameName(@TempDir Path cacheBase) {
        runner.withPropertyValues(
                        "mcp-stdio-sidecar.enabled=true",
                        "mcp-stdio-sidecar.address=mcp-stdio-runner:8097",
                        "mcp-stdio-sidecar.shared-secret=s3cr3t",
                        "app.mcp.package-cache-dir=" + cacheBase)
                .run(
                        ctx -> {
                            @SuppressWarnings("unchecked")
                            List<NamedClientMcpTransport> local =
                                    (List<NamedClientMcpTransport>) ctx.getBean("stdioTransports");
                            assertThat(local).as("nothing may spawn npx inside core").isEmpty();

                            @SuppressWarnings("unchecked")
                            List<NamedClientMcpTransport> routed =
                                    (List<NamedClientMcpTransport>)
                                            ctx.getBean("sidecarRoutedStaticStdioTransports");
                            assertThat(routed)
                                    .extracting(NamedClientMcpTransport::name)
                                    .containsExactlyInAnyOrder("sequential-thinking", "memory");
                            assertThat(routed)
                                    .allSatisfy(
                                            t ->
                                                    assertThat(t.transport())
                                                            .isInstanceOf(
                                                                    EnvIsolatedStdioClientTransport
                                                                            .class));

                            // Both lists are what McpClientAutoConfiguration aggregates - exactly
                            // one client per name.
                            List<String> allNames =
                                    ctx.getBeanProvider(TRANSPORT_LIST).stream()
                                            .flatMap(o -> ((List<?>) o).stream())
                                            .map(o -> ((NamedClientMcpTransport) o).name())
                                            .toList();
                            assertThat(allNames)
                                    .containsExactlyInAnyOrder("sequential-thinking", "memory");
                        });
    }

    @Test
    void routedParamsPointAtTheRelayAndCarryTheRealTarget(@TempDir Path cacheBase) {
        McpStdioSidecarProperties sidecar = new McpStdioSidecarProperties();
        sidecar.setEnabled(true);
        sidecar.setAddress("mcp-stdio-runner:8097");
        sidecar.setSharedSecret("s3cr3t");
        Map<String, ServerParameters> statics = new LinkedHashMap<>();
        statics.put(
                "playwright-vision",
                ServerParameters.builder("npx.cmd")
                        .args("-y", "@playwright/mcp@0.0.79", "--caps=vision")
                        .env(Map.of("FOO", "bar"))
                        .build());

        Map<String, ServerParameters> routed =
                StaticMcpStdioSidecarRouting.routeThroughSidecar(statics, sidecar, cacheBase);

        ServerParameters p = routed.get("playwright-vision");
        assertThat(p.getCommand()).isEqualTo("node");
        assertThat(p.getArgs()).containsExactly("/opt/mcp-stdio-proxy/proxy.js");
        Map<String, String> env = p.getEnv();
        assertThat(env)
                .containsEntry("MCP_SIDECAR_URL", "mcp-stdio-runner:8097")
                .containsEntry("MCP_SIDECAR_SECRET", "s3cr3t")
                .containsEntry("MCP_SIDECAR_TARGET_COMMAND", "npx")
                .containsEntry(
                        "MCP_SIDECAR_TARGET_ARGS_JSON",
                        "[\"-y\",\"@playwright/mcp@0.0.79\",\"--caps=vision\"]")
                // SEC-030: the target env is carried as JSON for the runner, not as the relay's own
                // process env; runner-owned cache dirs are left to the runner.
                .doesNotContainKey("FOO")
                .doesNotContainKey("npm_config_cache");
        Map<String, String> targetEnv = readJsonMap(env.get("MCP_SIDECAR_TARGET_ENV_JSON"));
        assertThat(targetEnv)
                .containsEntry("FOO", "bar")
                .doesNotContainKey("npm_config_cache")
                .doesNotContainKey("PATH")
                .doesNotContainKey("HOME");
    }

    private static Map<String, String> readJsonMap(String json) {
        try {
            return new tools.jackson.databind.ObjectMapper()
                    .readValue(
                            json,
                            new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (tools.jackson.core.JacksonException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void windowsLauncherSuffixIsStrippedForTheLinuxRunner() {
        assertThat(StaticMcpStdioSidecarRouting.runnerCommand("npx.cmd")).isEqualTo("npx");
        assertThat(StaticMcpStdioSidecarRouting.runnerCommand("uvx.EXE")).isEqualTo("uvx");
        assertThat(StaticMcpStdioSidecarRouting.runnerCommand("npx")).isEqualTo("npx");
    }
}
