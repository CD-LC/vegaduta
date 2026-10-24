package com.agenticai.core.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.llm.request-snapshot.*}: the caps on one copy of a model request kept by the LLM request
 * inspector ({@link LlmRequestSnapshotter}). Display-only: nothing here changes what the model receives.
 *
 * <p>Defaults are read from {@code /chat/request-snapshot.properties}; the same values are in {@code
 * application.yml}. Registered by {@link LlmRequestSnapshotConfiguration}, which hands the bound values to
 * the snapshotter (it is called from {@link LlmMetricsAdvisor}, which every provider factory builds by hand,
 * so it cannot be injected).
 */
@ConfigurationProperties(prefix = "app.llm.request-snapshot")
public class LlmRequestSnapshotProperties {

    private static final String DEFAULTS_RESOURCE = "/chat/request-snapshot.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Characters of one message kept. */
    private int maxMessageChars = Integer.parseInt(DEFAULTS.getProperty("max-message-chars"));

    /** Characters of all messages kept. */
    private int maxTotalChars = Integer.parseInt(DEFAULTS.getProperty("max-total-chars"));

    /** Characters of one tool's input schema kept. */
    private int maxSchemaChars = Integer.parseInt(DEFAULTS.getProperty("max-schema-chars"));

    /** Tool definitions kept. */
    private int maxTools = Integer.parseInt(DEFAULTS.getProperty("max-tools"));

    /** Characters of one tool call's arguments, or of one tool result, kept. */
    private int maxCallChars = Integer.parseInt(DEFAULTS.getProperty("max-call-chars"));

    /** Appended to a message that was cut. */
    private String truncationMarker = DEFAULTS.getProperty("truncation-marker");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = LlmRequestSnapshotProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the chat module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public int getMaxMessageChars() {
        return maxMessageChars;
    }

    public void setMaxMessageChars(int maxMessageChars) {
        this.maxMessageChars = Math.max(0, maxMessageChars);
    }

    public int getMaxTotalChars() {
        return maxTotalChars;
    }

    public void setMaxTotalChars(int maxTotalChars) {
        this.maxTotalChars = Math.max(0, maxTotalChars);
    }

    public int getMaxSchemaChars() {
        return maxSchemaChars;
    }

    public void setMaxSchemaChars(int maxSchemaChars) {
        this.maxSchemaChars = Math.max(0, maxSchemaChars);
    }

    public int getMaxTools() {
        return maxTools;
    }

    public void setMaxTools(int maxTools) {
        this.maxTools = Math.max(0, maxTools);
    }

    public int getMaxCallChars() {
        return maxCallChars;
    }

    public void setMaxCallChars(int maxCallChars) {
        this.maxCallChars = Math.max(0, maxCallChars);
    }

    public String getTruncationMarker() {
        return truncationMarker;
    }

    public void setTruncationMarker(String truncationMarker) {
        this.truncationMarker = truncationMarker == null ? "" : truncationMarker;
    }
}
