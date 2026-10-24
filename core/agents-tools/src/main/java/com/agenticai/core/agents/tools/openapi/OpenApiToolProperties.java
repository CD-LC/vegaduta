package com.agenticai.core.agents.tools.openapi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tools.openapi.*}: limits of the HTTP calls made for a tenant's OpenAPI tool sources ({@link
 * OpenApiToolExecutor}): spec fetch, OAuth2 token call and the API call a tool stands for. They used to be
 * the constants {@code CONNECT_TIMEOUT} (10 s), {@code REQUEST_TIMEOUT} (30 s), {@code MAX_RESPONSE_BYTES}
 * (1 MiB), {@code MAX_RESULT_CHARS} (16,000) and {@code MAX_SPEC_REDIRECTS} (3).
 *
 * <p>Defaults are read from {@code /tools/openapi.properties}; the same values are in {@code
 * application.yml}. Registered by {@link com.agenticai.core.agents.tools.ToolTransportConfiguration}.
 */
@ConfigurationProperties(prefix = "app.tools.openapi")
public class OpenApiToolProperties {

    private static final String DEFAULTS_RESOURCE = "/tools/openapi.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Seconds a call may take to connect. */
    private int connectTimeoutSeconds = Integer.parseInt(DEFAULTS.getProperty("connect-timeout-seconds"));

    /** Seconds a call may take to get its reply. */
    private int requestTimeoutSeconds = Integer.parseInt(DEFAULTS.getProperty("request-timeout-seconds"));

    /** Bytes of an API reply that are read. */
    private int maxResponseBytes = Integer.parseInt(DEFAULTS.getProperty("max-response-bytes"));

    /** Characters of an API reply handed to the model. */
    private int maxResultChars = Integer.parseInt(DEFAULTS.getProperty("max-result-chars"));

    /** Redirects followed when fetching a spec. */
    private int maxSpecRedirects = Integer.parseInt(DEFAULTS.getProperty("max-spec-redirects"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = OpenApiToolProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-tools module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = Math.max(1, connectTimeoutSeconds);
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = Math.max(1, maxResponseBytes);
    }

    public int getMaxResultChars() {
        return maxResultChars;
    }

    public void setMaxResultChars(int maxResultChars) {
        this.maxResultChars = Math.max(1, maxResultChars);
    }

    public int getMaxSpecRedirects() {
        return maxSpecRedirects;
    }

    public void setMaxSpecRedirects(int maxSpecRedirects) {
        this.maxSpecRedirects = Math.max(0, maxSpecRedirects);
    }

    /** {@link #getConnectTimeoutSeconds()} as a duration. */
    public Duration connectTimeout() {
        return Duration.ofSeconds(connectTimeoutSeconds);
    }

    /** {@link #getRequestTimeoutSeconds()} as a duration. */
    public Duration requestTimeout() {
        return Duration.ofSeconds(requestTimeoutSeconds);
    }
}
