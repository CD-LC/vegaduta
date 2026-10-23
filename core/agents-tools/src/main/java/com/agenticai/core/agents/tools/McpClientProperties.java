package com.agenticai.core.agents.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tools.mcp.*}: timeouts of the MCP client that a tenant's registered (dynamic) MCP servers are
 * called through ({@link DynamicMcpConnectionManager}). They used to be the constants {@code REQUEST_TIMEOUT}
 * (60 s) and {@code TEST_TIMEOUT} (20 s).
 *
 * <p>Defaults are read from {@code /tools/mcp-client.properties}; the same values are in {@code
 * application.yml}. Registered by {@link ToolTransportConfiguration}.
 */
@ConfigurationProperties(prefix = "app.tools.mcp")
public class McpClientProperties {

    private static final String DEFAULTS_RESOURCE = "/tools/mcp-client.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Seconds one request to a connected MCP server may wait for its reply. */
    private int requestTimeoutSeconds = Integer.parseInt(DEFAULTS.getProperty("request-timeout-seconds"));

    /** Seconds one request of the "test connection" action may wait for its reply. */
    private int testTimeoutSeconds = Integer.parseInt(DEFAULTS.getProperty("test-timeout-seconds"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = McpClientProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-tools module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    public int getTestTimeoutSeconds() {
        return testTimeoutSeconds;
    }

    public void setTestTimeoutSeconds(int testTimeoutSeconds) {
        this.testTimeoutSeconds = Math.max(1, testTimeoutSeconds);
    }

    /** {@link #getRequestTimeoutSeconds()} as a duration. */
    public Duration requestTimeout() {
        return Duration.ofSeconds(requestTimeoutSeconds);
    }

    /** {@link #getTestTimeoutSeconds()} as a duration. */
    public Duration testTimeout() {
        return Duration.ofSeconds(testTimeoutSeconds);
    }
}
