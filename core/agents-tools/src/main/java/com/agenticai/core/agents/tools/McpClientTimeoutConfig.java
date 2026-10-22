package com.agenticai.core.agents.tools;

import io.modelcontextprotocol.client.McpClient;
import java.time.Duration;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Direct incident, first real Quick Task retry after the {@code fetch}/{@code
 * time} catalog fix (core.log): {@code MCP tool discovery failed}, cause
 * {@code TimeoutException: Did not observe any item or terminal signal
 * within 20000ms}, from {@code io.modelcontextprotocol.client
 * .LifecycleInitializer.withInitialization} - NOT the same 20s default this
 * project already raised via {@code spring.ai.mcp.client.request-timeout:
 * 60s} in application-mcp.yml. Spring AI's {@code McpClientAutoConfiguration}
 * only ever calls {@code .requestTimeout(commonProperties.getRequestTimeout())}
 * on each client's {@code SyncSpec} - it never calls the SDK's separate
 * {@code .initializationTimeout(Duration)} builder method, so that step (the
 * connect+handshake LifecycleInitializer performs before a client's first
 * real use, since this app deliberately sets {@code initialized: false} for
 * lazy connection - see application-mcp.yml) is stuck on the SDK's own
 * short built-in default regardless of our {@code request-timeout} config.
 * That default is too short for a COLD {@code npx -y
 * @modelcontextprotocol/server-puppeteer} - puppeteer's own npm postinstall
 * downloads a full Chromium binary (100s of MB) the very first time it runs
 * on a machine, which routinely exceeds 20s even on a decent connection.
 *
 * <p>The one supported extension point for this is registering a {@code
 * McpClientCustomizer<McpClient.SyncSpec>} bean here - Spring AI applies every one found in
 * the context to each client's spec right before {@code .build()}. (Spring AI 1.1 had the
 * separate, non-generic {@code McpSyncClientCustomizer}; Spring AI 2.0 replaced both the
 * sync and async forms with the generic {@code McpClientCustomizer<B>}, ADR-0012 P3.)
 *
 * <p>This alone doesn't make a cold Chromium download fast - see
 * {@code scripts/dev/prewarm-mcp-servers.ps1} (given directly to the user
 * per standing "always PowerShell scripts" instruction) for actually
 * pre-populating npm's npx cache before core ever needs to connect for
 * real - but it does mean a genuinely slow-but-eventually-successful first
 * connection no longer gets killed early and forced to retry, which is what
 * produced this incident's second failure mode: a retried {@code connect()}
 * reusing a transport whose stdout sink was already subscribed by the timed-
 * out first attempt, crashing with "Sinks.many().unicast() sinks only allow
 * a single Subscriber".
 */
@Configuration
public class McpClientTimeoutConfig {

    @Bean
    public McpClientCustomizer<McpClient.SyncSpec> mcpSyncClientInitializationTimeoutCustomizer(
            @Value("${app.mcp.client-initialization-timeout-seconds:120}")
                    long initializationTimeoutSeconds) {
        Duration timeout = Duration.ofSeconds(initializationTimeoutSeconds);
        return (name, spec) -> spec.initializationTimeout(timeout);
    }
}
