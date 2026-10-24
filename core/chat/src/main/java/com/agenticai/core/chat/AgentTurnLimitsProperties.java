package com.agenticai.core.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.agent-turn.limits.*}: the bounds on one request to {@code POST /api/completions/agent-turn}
 * ({@link AgentTurnController}). An agent conversation is large, so these bound a request, not a task.
 * A request over a bound is rejected before anything is reserved or any model is called.
 *
 * <p>Defaults are read from {@code /agent-turn/limits.properties}; the same values are in {@code
 * application.yml}. Registered by {@link AgentTurnLimitsConfiguration}.
 */
@ConfigurationProperties(prefix = "app.agent-turn.limits")
public class AgentTurnLimitsProperties {

    private static final String DEFAULTS_RESOURCE = "/agent-turn/limits.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Messages in one conversation. */
    private int maxMessages = Integer.parseInt(DEFAULTS.getProperty("max-messages"));

    /** Characters of message content in the whole conversation (413 above it). */
    private int maxTotalChars = Integer.parseInt(DEFAULTS.getProperty("max-total-chars"));

    /** Tool definitions the client may offer. */
    private int maxTools = Integer.parseInt(DEFAULTS.getProperty("max-tools"));

    /** Tool calls on one assistant message. */
    private int maxToolCallsPerMessage = Integer.parseInt(DEFAULTS.getProperty("max-tool-calls-per-message"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = AgentTurnLimitsProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the chat module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = Math.max(1, maxMessages);
    }

    public int getMaxTotalChars() {
        return maxTotalChars;
    }

    public void setMaxTotalChars(int maxTotalChars) {
        this.maxTotalChars = Math.max(1, maxTotalChars);
    }

    public int getMaxTools() {
        return maxTools;
    }

    public void setMaxTools(int maxTools) {
        this.maxTools = Math.max(0, maxTools);
    }

    public int getMaxToolCallsPerMessage() {
        return maxToolCallsPerMessage;
    }

    public void setMaxToolCallsPerMessage(int maxToolCallsPerMessage) {
        this.maxToolCallsPerMessage = Math.max(0, maxToolCallsPerMessage);
    }
}
