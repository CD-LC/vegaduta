package com.agenticai.core.agents.tools;

import com.agenticai.core.json.JsonMappers;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routes the STATIC, YAML-wired STDIO MCP built-ins (application-mcp.yml's {@code
 * spring.ai.mcp.client.stdio.connections}: filesystem, memory, sequential-thinking, puppeteer,
 * playwright-vision) through the {@code mcp-stdio-runner} sidecar when {@code
 * mcp-stdio-sidecar.enabled=true} - the same relay the dynamic, tenant-registered STDIO servers
 * already use (see {@link McpStdioParamsBuilder#wrapForSidecar}). This is what lets the slim core
 * image ({@code MCP_TOOLCHAIN=none}: no npm/npx/uv/browsers) still offer the built-ins: the real
 * {@code npx <pkg>} runs in the runner, core only spawns the trusted {@code
 * /opt/mcp-stdio-proxy/proxy.js} relay.
 *
 * <p><b>Mechanism.</b> Spring AI's {@code StdioTransportAutoConfiguration} turns {@link
 * McpStdioClientProperties} into {@code stdioTransports} (a plain, NON-env-isolated {@code
 * StdioClientTransport} per connection) and {@code McpClientAutoConfiguration} builds one MCP
 * client per {@link NamedClientMcpTransport} found across EVERY {@code
 * List<NamedClientMcpTransport>} bean. So, with the sidecar on:
 *
 * <ol>
 *   <li>{@link StaticStdioConnectionCapture} (a {@link BeanPostProcessor}) snapshots the resolved
 *       connections the moment the properties bean is bound, then empties it - the starter's own
 *       {@code stdioTransports} therefore yields nothing and nothing spawns {@code npx} locally.
 *   <li>{@link #sidecarRoutedStaticStdioTransports} re-contributes the SAME connection names, each
 *       wrapped for the relay and built on {@link EnvIsolatedStdioClientTransport}. Env isolation
 *       is load-bearing here: the relay forwards its whole process env to the runner's child, and
 *       the starter's plain transport would have handed it core's entire environment
 *       (APP_ENCRYPTION_KEY, datasource password, ...).
 * </ol>
 *
 * Connection names are unchanged, so {@link StaticMcpExposurePolicy}'s stateless allowlist, lazy
 * init ({@code initialized: false}) and AgentToolProvider's per-client isolation behave exactly as
 * before. With the sidecar off this class is not registered at all - behaviour is today's, byte for
 * byte.
 *
 * <p>Semantics that DO move with the process: {@code filesystem}'s root ({@code
 * MCP_FILESYSTEM_ROOT}) is resolved inside the runner container, and the browser servers need the
 * browser runner image ({@code mcp-stdio-runner-browser}, built with {@code WITH_BROWSERS=true}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(McpStdioClientProperties.class)
@ConditionalOnProperty(prefix = "mcp-stdio-sidecar", name = "enabled", havingValue = "true")
public class StaticMcpStdioSidecarRouting {

    private static final Logger log = LoggerFactory.getLogger(StaticMcpStdioSidecarRouting.class);

    @Bean
    static StaticStdioConnectionCapture staticStdioConnectionCapture() {
        return new StaticStdioConnectionCapture();
    }

    /**
     * {@code stdioProperties} is injected only to force it to be bound (and so captured) before
     * this method reads the capture. {@code packageCacheDir} is the same {@code
     * app.mcp.package-cache-dir} {@link DynamicMcpConnectionManager} resolves (idempotent).
     */
    @Bean
    List<NamedClientMcpTransport> sidecarRoutedStaticStdioTransports(
            McpStdioClientProperties stdioProperties,
            StaticStdioConnectionCapture capture,
            McpStdioSidecarProperties sidecarProperties,
            @org.springframework.beans.factory.annotation.Value("${app.mcp.package-cache-dir:}")
                    String packageCacheDir) {
        if (!stdioProperties.getConnections().isEmpty()) {
            // Only possible if the properties bean was created before this
            // post-processor was registered - then the starter spawns locally.
            log.error(
                    "mcp-stdio-sidecar is enabled but the static STDIO MCP connections {} were not captured - "
                            + "they will spawn inside core instead of mcp-stdio-runner",
                    stdioProperties.getConnections().keySet());
        }
        Map<String, ServerParameters> routed =
                routeThroughSidecar(
                        capture.captured(),
                        sidecarProperties,
                        McpStdioParamsBuilder.resolvePackageCacheDir(packageCacheDir));
        if (!routed.isEmpty()) {
            log.info(
                    "Static STDIO MCP servers {} are routed through mcp-stdio-runner at {}",
                    routed.keySet(),
                    sidecarProperties.getAddress());
        }
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMappers.plain());
        return routed.entrySet().stream()
                .map(
                        e ->
                                new NamedClientMcpTransport(
                                        e.getKey(),
                                        new EnvIsolatedStdioClientTransport(
                                                e.getValue(), jsonMapper)))
                .toList();
    }

    /**
     * Pure routing decision - one relay {@link ServerParameters} per static connection, same names,
     * in the same order. Unit-tested directly.
     */
    static Map<String, ServerParameters> routeThroughSidecar(
            Map<String, ServerParameters> staticConnections,
            McpStdioSidecarProperties sidecarProperties,
            Path packageCacheBase) {
        Map<String, ServerParameters> routed = new LinkedHashMap<>();
        staticConnections.forEach(
                (name, params) -> {
                    Map<String, String> env =
                            new LinkedHashMap<>(
                                    params.getEnv() == null ? Map.of() : params.getEnv());
                    // PATH/HOME (ServerParameters' inherited defaults - core's values) are
                    // stripped by the relay and overridden by the runner; same as the dynamic path.
                    env =
                            McpStdioParamsBuilder.isolatedPackageCacheEnv(
                                    packageCacheBase, "static-" + name, env);
                    List<String> args = params.getArgs() == null ? List.of() : params.getArgs();
                    routed.put(
                            name,
                            McpStdioParamsBuilder.wrapForSidecar(
                                    sidecarProperties,
                                    runnerCommand(params.getCommand()),
                                    args,
                                    env));
                });
        return routed;
    }

    /**
     * The runner is Linux and allowlists bare launcher names ({@code npx}, {@code uvx}, ...).
     * McpWindowsCommandEnvironmentPostProcessor rewrites the static commands to {@code npx.cmd} on
     * a Windows dev host - undo that for the runner.
     */
    static String runnerCommand(String command) {
        if (command == null) {
            return null;
        }
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : List.of(".cmd", ".exe", ".bat")) {
            if (lower.endsWith(suffix)) {
                return command.substring(0, command.length() - suffix.length());
            }
        }
        return command;
    }

    /**
     * Snapshots the static STDIO connections as soon as Spring AI binds them, then clears them so
     * the starter's own (local-spawn) transports are empty.
     */
    static final class StaticStdioConnectionCapture implements BeanPostProcessor {

        private volatile Map<String, ServerParameters> captured = Map.of();

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof McpStdioClientProperties properties) {
                capture(properties);
            }
            return bean;
        }

        void capture(McpStdioClientProperties properties) {
            Map<String, ServerParameters> snapshot =
                    new LinkedHashMap<>(properties.toServerParameters());
            if (!snapshot.isEmpty()) {
                captured = java.util.Collections.unmodifiableMap(snapshot);
            }
            properties.getConnections().clear();
            properties.setServersConfiguration(null);
        }

        Map<String, ServerParameters> captured() {
            return captured;
        }
    }
}
