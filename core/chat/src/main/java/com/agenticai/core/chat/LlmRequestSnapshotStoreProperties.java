package com.agenticai.core.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.llm.request-snapshot-store.*}: where the LLM request inspector keeps its redacted copies of
 * recent model requests and for how long. (The caps on ONE copy are {@code app.llm.request-snapshot.*},
 * {@link LlmRequestSnapshotProperties}.) Display-only: nothing here changes what the model receives.
 *
 * <p>Defaults are read from {@code /chat/request-snapshot-store.properties}; the same values are in {@code
 * application.yml}. Registered by {@link LlmRequestSnapshotStoreConfiguration}, which hands the bound values
 * to the store (it is a static singleton because {@link LlmMetricsAdvisor} is built by hand in every provider
 * factory and cannot be injected).
 */
@ConfigurationProperties(prefix = "app.llm.request-snapshot-store")
public class LlmRequestSnapshotStoreProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/request-snapshot-store.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** {@code redis} (shared between replicas, survives a restart for the ttl) or {@code memory} (this JVM only). */
    private String mode = DEFAULTS.getProperty("mode");

    /** How long a copy is kept. */
    private Duration ttl = Duration.parse(DEFAULTS.getProperty("ttl"));

    /** Sessions kept in memory. */
    private int maxSessions = Integer.parseInt(DEFAULTS.getProperty("max-sessions"));

    /** Model calls kept per session. */
    private int maxPerSession = Integer.parseInt(DEFAULTS.getProperty("max-per-session"));

    /** Copies waiting to be written to Redis. */
    private int writeQueueSize = Integer.parseInt(DEFAULTS.getProperty("write-queue-size"));

    /** Redis key prefix. */
    private String keyPrefix = DEFAULTS.getProperty("key-prefix");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = LlmRequestSnapshotStoreProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the chat module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode == null || mode.isBlank() ? DEFAULTS.getProperty("mode") : mode.strip().toLowerCase(Locale.ROOT);
    }

    /** True for {@code redis}; any other value (including a typo) keeps the copies in this JVM. */
    public boolean isRedis() {
        return "redis".equals(mode);
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.parse(DEFAULTS.getProperty("ttl")) : ttl;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = Math.max(1, maxSessions);
    }

    public int getMaxPerSession() {
        return maxPerSession;
    }

    public void setMaxPerSession(int maxPerSession) {
        this.maxPerSession = Math.max(1, maxPerSession);
    }

    public int getWriteQueueSize() {
        return writeQueueSize;
    }

    public void setWriteQueueSize(int writeQueueSize) {
        this.writeQueueSize = Math.max(1, writeQueueSize);
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? DEFAULTS.getProperty("key-prefix") : keyPrefix;
    }
}
